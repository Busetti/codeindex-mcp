package com.example.codeindex.indexer;

import com.example.codeindex.indexer.analysis.MethodMetrics;
import com.example.codeindex.indexer.model.CallEdge;
import com.example.codeindex.indexer.model.DataAccessFact;
import com.example.codeindex.indexer.model.EndpointInfo;
import com.example.codeindex.indexer.model.MethodMetric;
import com.example.codeindex.indexer.model.ExternalCallFact;
import com.example.codeindex.indexer.model.ScanResult;
import com.example.codeindex.indexer.model.Symbol;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses a Java source tree into structural facts (symbols, endpoints, call edges, data-access and
 * external-call facts).
 *
 * <p>Intentionally avoids JavaParser's SymbolSolver so it can index a codebase we cannot compile
 * (the target's dependencies are not on our classpath). Callee resolution is done manually by
 * mapping a call's receiver name to its declared type and then to a known symbol FQN — good enough
 * to reconstruct endpoint→service→repository chains.
 */
@Component
public class JavaCodeIndexer {

    private static final Logger log = LoggerFactory.getLogger(JavaCodeIndexer.class);

    private static final Set<String> HTTP_MAPPINGS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping");
    private static final Set<String> REST_CLIENT_TYPES = Set.of("RestTemplate", "WebClient", "RestClient");

    private final JavaParser parser;

    public JavaCodeIndexer() {
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.parser = new JavaParser(cfg);
    }

    /** Global metadata gathered in pass 1 and consumed during pass 2. */
    private record Ctx(Map<String, String> declaredBySimple,
                       Map<String, String> stereotypeByFqn,
                       Set<String> repositoryFqns) {
    }

    /** Directory names whose subtrees are pruned during the walk (build output / VCS metadata). */
    private static final Set<String> PRUNE_DIR_NAMES = Set.of(
            "target", "build", "out", "bin", ".git", "node_modules", "generated", "generated-sources");

    public ScanResult scan(Path root) {
        return scan(root, List.of(), List.of(), ProgressListener.NOOP);
    }

    public ScanResult scan(Path root, Collection<String> excludeGlobs) {
        return scan(root, excludeGlobs, List.of(), ProgressListener.NOOP);
    }

    /**
     * @param excludeGlobs   glob patterns applied to file paths
     * @param excludedPaths  absolute directory prefixes to skip entirely (e.g. user-deselected folders)
     * @param listener       progress callback (phases: "scanning", "analyzing")
     */
    public ScanResult scan(Path root, Collection<String> excludeGlobs,
                           Collection<Path> excludedPaths, ProgressListener listener) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Source root is not a directory: " + root);
        }
        ProgressListener pl = listener == null ? ProgressListener.NOOP : listener;
        List<PathMatcher> globs = new ArrayList<>();
        for (String g : excludeGlobs) {
            globs.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        }
        List<Path> excludedDirs = new ArrayList<>();
        for (Path p : excludedPaths) {
            excludedDirs.add(p.toAbsolutePath().normalize());
        }

        List<Path> javaFiles = collectJavaFiles(root, globs, excludedDirs);
        int total = javaFiles.size();

        // Pass 1: parse each file; collect declared types, stereotypes, repository types.
        List<ParsedFile> files = new ArrayList<>();
        Ctx ctx = new Ctx(new HashMap<>(), new HashMap<>(), new HashSet<>());
        int scanned = 0;
        for (Path p : javaFiles) {
            parseFile(p).ifPresent(cu -> {
                files.add(new ParsedFile(p, cu));
                collectDeclared(cu, ctx);
            });
            pl.onProgress("scanning", ++scanned, total);
        }

        // Pass 2: build facts.
        ScanResult result = new ScanResult();
        int analyzed = 0;
        for (ParsedFile pf : files) {
            processFile(pf, ctx, result);
            pl.onProgress("analyzing", ++analyzed, files.size());
        }
        log.info("Indexed {} files: {} symbols, {} endpoints, {} call edges, {} data-access, {} external-calls",
                files.size(), result.symbols().size(), result.endpoints().size(), result.callEdges().size(),
                result.dataAccess().size(), result.externalCalls().size());
        return result;
    }

    /** Walk the tree, pruning build/VCS and excluded subtrees; return the .java files to parse. */
    private List<Path> collectJavaFiles(Path root, List<PathMatcher> globs, List<Path> excludedDirs) {
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (name != null && PRUNE_DIR_NAMES.contains(name.toString())) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    Path abs = dir.toAbsolutePath().normalize();
                    for (Path ex : excludedDirs) {
                        if (abs.startsWith(ex)) {
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")
                            && globs.stream().noneMatch(m -> m.matches(file))) {
                        out.add(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(java.util.Comparator.comparing(Path::toString));
        return out;
    }

    private void collectDeclared(CompilationUnit cu, Ctx ctx) {
        for (ClassOrInterfaceDeclaration td : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqn = td.getFullyQualifiedName().orElse(td.getNameAsString());
            ctx.declaredBySimple().putIfAbsent(td.getNameAsString(), fqn);
            String stereotype = stereotypeOf(td.getAnnotations());
            if (stereotype != null) {
                ctx.stereotypeByFqn().put(fqn, stereotype);
            }
            if ("REPOSITORY".equals(stereotype) || extendsRepository(td)) {
                ctx.repositoryFqns().add(fqn);
            }
        }
        for (EnumDeclaration td : cu.findAll(EnumDeclaration.class)) {
            ctx.declaredBySimple().putIfAbsent(td.getNameAsString(),
                    td.getFullyQualifiedName().orElse(td.getNameAsString()));
        }
    }

    private Optional<CompilationUnit> parseFile(Path path) {
        try {
            ParseResult<CompilationUnit> pr = parser.parse(path);
            if (pr.isSuccessful() && pr.getResult().isPresent()) {
                return pr.getResult();
            }
            log.warn("Skipping unparseable file {} ({} problems)", path, pr.getProblems().size());
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", path, e.getMessage());
        }
        return Optional.empty();
    }

    private void processFile(ParsedFile pf, Ctx ctx, ScanResult out) {
        CompilationUnit cu = pf.cu();
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        String filePath = pf.path().toString();

        Map<String, String> imports = new HashMap<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                String full = imp.getNameAsString();
                imports.put(simpleOf(full), full);
            }
        }
        TypeResolver resolver = new TypeResolver(imports, pkg, ctx.declaredBySimple());

        for (ClassOrInterfaceDeclaration td : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            processType(td, td.getFullyQualifiedName().orElse(td.getNameAsString()), td.getNameAsString(),
                    td.getAnnotations(), td.isInterface() ? "INTERFACE" : "CLASS", filePath, resolver, ctx, out);
        }
        for (EnumDeclaration td : cu.findAll(EnumDeclaration.class)) {
            processType(td, td.getFullyQualifiedName().orElse(td.getNameAsString()), td.getNameAsString(),
                    td.getAnnotations(), "ENUM", filePath, resolver, ctx, out);
        }
    }

    // Note: deliberately avoids TypeDeclaration#getFields()/getMethods() (NodeWithMembers) — calling those,
    // or inferring TypeDeclaration<?> via Stream.concat, trips a javac generics check on a JavaParser library
    // override (JDK 23+). We read direct children via Node#getChildNodes() instead.
    private void processType(Node typeNode, String typeFqn, String typeName,
                             List<AnnotationExpr> annos, String kind, String filePath,
                             TypeResolver resolver, Ctx ctx, ScanResult out) {
        String stereotype = stereotypeOf(annos);
        String basePath = classLevelPath(annos);

        out.symbols().add(new Symbol(kind, typeFqn, typeName, null, null,
                stereotype, annotationNames(annos), filePath,
                lineOf(typeNode, true), lineOf(typeNode, false), null));

        List<FieldDeclaration> fields = directChildren(typeNode, FieldDeclaration.class);
        List<MethodDeclaration> methods = directChildren(typeNode, MethodDeclaration.class);

        // Field name -> declared simple type, used later to resolve call receivers.
        Map<String, String> fieldTypes = new HashMap<>();
        for (FieldDeclaration fd : fields) {
            for (VariableDeclarator v : fd.getVariables()) {
                String simpleType = simpleTypeName(v.getTypeAsString());
                fieldTypes.put(v.getNameAsString(), simpleType);
                out.symbols().add(new Symbol("FIELD", typeFqn + "#" + v.getNameAsString(), v.getNameAsString(),
                        v.getTypeAsString(), typeFqn, null, annotationNames(fd.getAnnotations()),
                        filePath, lineOf(fd, true), lineOf(fd, false), null));

                // Heuristic: a secret-looking field assigned a hardcoded string literal. Recorded as a
                // metric (secretHit) so the detector emits it — keeping all config gating in one place.
                if (MethodMetrics.secretName(v.getNameAsString())
                        && v.getInitializer().map(MethodMetrics::nonBlankStringLiteral).orElse(false)) {
                    out.metrics().add(new MethodMetric(typeFqn + "#" + v.getNameAsString(), filePath,
                            lineOf(fd, true), 0, 0, 0, 0, false, false, false, true, false));
                }
            }
        }

        for (MethodDeclaration md : methods) {
            processMethod(md, typeFqn, stereotype, basePath, fieldTypes, filePath, resolver, ctx, out);
        }
    }

    private void processMethod(MethodDeclaration md, String typeFqn, String stereotype, String basePath,
                               Map<String, String> fieldTypes, String filePath,
                               TypeResolver resolver, Ctx ctx, ScanResult out) {
        String methodFqn = typeFqn + "#" + md.getNameAsString();
        out.symbols().add(new Symbol("METHOD", methodFqn, md.getNameAsString(),
                md.getDeclarationAsString(true, false, true), typeFqn, stereotype,
                annotationNames(md.getAnnotations()), filePath,
                lineOf(md, true), lineOf(md, false), md.toString()));

        // Per-method quality metrics (complexity, nesting, error-handling, security heuristics).
        out.metrics().add(MethodMetrics.compute(methodFqn, filePath, md));

        for (AnnotationExpr anno : md.getAnnotations()) {
            String simple = simpleOf(anno.getNameAsString());
            // Endpoints
            if (HTTP_MAPPINGS.contains(simple)) {
                out.endpoints().add(new EndpointInfo(
                        httpMethodFor(simple, anno),
                        joinPath(basePath, firstStringMember(anno, "value", "path")),
                        methodFqn, typeFqn));
            }
            // @Query on a repository method -> JPQL / native SQL data-access fact.
            if (simple.equals("Query")) {
                boolean nativeQuery = "true".equalsIgnoreCase(namedMember(anno, "nativeQuery"));
                String sql = firstStringMember(anno, "value");
                out.dataAccess().add(new DataAccessFact(methodFqn,
                        nativeQuery ? "NATIVE_QUERY" : "JPQL",
                        sql == null ? "@Query" : sql, lineOf(md, true), false));
            }
        }

        // Local name -> simple type map (fields, then params + locals which shadow fields).
        Map<String, String> nameTypes = new HashMap<>(fieldTypes);
        md.getParameters().forEach(p -> nameTypes.put(p.getNameAsString(), simpleTypeName(p.getTypeAsString())));
        md.findAll(VariableDeclarator.class)
          .forEach(v -> nameTypes.put(v.getNameAsString(), simpleTypeName(v.getTypeAsString())));

        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            int line = call.getBegin().map(pos -> pos.line).orElse(lineOf(md, true));
            boolean inLoop = insideLoop(call, md);
            String calleeFqn = null;
            String calleeSimple;
            String recvType = null;
            Optional<Expression> scope = call.getScope();
            if (scope.isEmpty() || scope.get() instanceof ThisExpr) {
                calleeFqn = typeFqn + "#" + name;
                calleeSimple = name;
            } else if (scope.get() instanceof NameExpr ne) {
                String receiver = ne.getNameAsString();
                recvType = nameTypes.get(receiver);
                String recvFqn = recvType == null ? null : resolver.resolve(recvType);
                if (recvFqn != null) {
                    calleeFqn = recvFqn + "#" + name;
                }
                calleeSimple = receiver + "." + name;
            } else {
                calleeSimple = scope.get().toString() + "." + name;
            }
            out.callEdges().add(new CallEdge(methodFqn, calleeFqn, calleeSimple, line, inLoop));

            // Data-access fact: a call into a repository-stereotyped type.
            if (calleeFqn != null && ctx.repositoryFqns().contains(owner(calleeFqn))) {
                out.dataAccess().add(new DataAccessFact(methodFqn, "REPOSITORY_METHOD",
                        simpleOf(owner(calleeFqn)) + "." + name, line, inLoop));
            }
            // External-call fact: a call on a REST client type.
            if (recvType != null && REST_CLIENT_TYPES.contains(recvType)) {
                out.externalCalls().add(new ExternalCallFact(methodFqn,
                        recvType.equals("RestTemplate") ? "REST_TEMPLATE" : "WEB_CLIENT",
                        calleeSimple, line, inLoop));
            }
        }
    }

    // ---- helpers -----------------------------------------------------------

    private <X extends Node> List<X> directChildren(Node parent, Class<X> type) {
        List<X> out = new ArrayList<>();
        for (Node child : parent.getChildNodes()) {
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
        }
        return out;
    }

    private boolean extendsRepository(ClassOrInterfaceDeclaration td) {
        return td.getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().endsWith("Repository"));
    }

    /** True if {@code call} is inside a loop body that lies within {@code method}. */
    private boolean insideLoop(Node call, Node method) {
        Node cur = call.getParentNode().orElse(null);
        while (cur != null && cur != method) {
            if (cur instanceof ForStmt || cur instanceof ForEachStmt
                    || cur instanceof WhileStmt || cur instanceof DoStmt) {
                return true;
            }
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private String stereotypeOf(List<AnnotationExpr> annos) {
        for (AnnotationExpr a : annos) {
            switch (simpleOf(a.getNameAsString())) {
                case "RestController", "Controller" -> { return "CONTROLLER"; }
                case "Service" -> { return "SERVICE"; }
                case "Repository" -> { return "REPOSITORY"; }
                case "Component" -> { return "COMPONENT"; }
                default -> { }
            }
        }
        return null;
    }

    private String classLevelPath(List<AnnotationExpr> annos) {
        for (AnnotationExpr a : annos) {
            if (simpleOf(a.getNameAsString()).equals("RequestMapping")) {
                String p = firstStringMember(a, "value", "path");
                return p == null ? "" : p;
            }
        }
        return "";
    }

    private String httpMethodFor(String mappingSimpleName, AnnotationExpr anno) {
        return switch (mappingSimpleName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> {
                String m = firstStringMember(anno, "method");
                yield m == null ? "ANY" : m.replace("RequestMethod.", "").toUpperCase();
            }
        };
    }

    private String annotationNames(List<AnnotationExpr> annos) {
        if (annos.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        annos.forEach(a -> names.add(simpleOf(a.getNameAsString())));
        return String.join(",", names);
    }

    /** First string-literal value of the named annotation members (handles marker/single/normal). */
    private String firstStringMember(AnnotationExpr anno, String... memberNames) {
        if (anno instanceof SingleMemberAnnotationExpr sm) {
            return stringValue(sm.getMemberValue());
        }
        if (anno instanceof NormalAnnotationExpr na) {
            for (String want : memberNames) {
                for (var pair : na.getPairs()) {
                    if (pair.getNameAsString().equals(want)) {
                        String v = stringValue(pair.getValue());
                        if (v != null) {
                            return v;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Raw text of a named member (not necessarily a string literal), e.g. nativeQuery = true. */
    private String namedMember(AnnotationExpr anno, String member) {
        if (anno instanceof NormalAnnotationExpr na) {
            for (var pair : na.getPairs()) {
                if (pair.getNameAsString().equals(member)) {
                    return pair.getValue().toString();
                }
            }
        }
        return null;
    }

    private String stringValue(Expression expr) {
        if (expr instanceof StringLiteralExpr s) {
            return s.getValue();
        }
        if (expr instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr
                && !arr.getValues().isEmpty()) {
            return stringValue(arr.getValues().get(0));
        }
        return expr == null ? null : expr.toString();
    }

    private String joinPath(String base, String sub) {
        String b = base == null ? "" : base.trim();
        String s = sub == null ? "" : sub.trim();
        String joined = (b + "/" + s).replaceAll("/{2,}", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined.isEmpty() ? "/" : joined;
    }

    private String simpleOf(String qualified) {
        int idx = qualified.lastIndexOf('.');
        return idx < 0 ? qualified : qualified.substring(idx + 1);
    }

    /** Owner type FQN of a method/field FQN ({@code a.b.C#m} -> {@code a.b.C}). */
    private String owner(String memberFqn) {
        int idx = memberFqn.indexOf('#');
        return idx < 0 ? memberFqn : memberFqn.substring(0, idx);
    }

    private String simpleTypeName(String typeString) {
        String t = typeString;
        int lt = t.indexOf('<');
        if (lt >= 0) {
            t = t.substring(0, lt);
        }
        t = t.replace("[]", "").trim();
        return simpleOf(t);
    }

    private int lineOf(Node node, boolean begin) {
        return (begin ? node.getBegin() : node.getEnd()).map(p -> p.line).orElse(0);
    }

    private record ParsedFile(Path path, CompilationUnit cu) {
    }

    /** Resolves a simple type name to an FQN via imports, same-package, then any declared type. */
    private record TypeResolver(Map<String, String> imports, String pkg, Map<String, String> declaredBySimple) {
        String resolve(String simpleName) {
            if (simpleName == null) {
                return null;
            }
            if (imports.containsKey(simpleName)) {
                return imports.get(simpleName);
            }
            String samePkg = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
            if (declaredBySimple.containsValue(samePkg)) {
                return samePkg;
            }
            return declaredBySimple.get(simpleName);
        }
    }
}
