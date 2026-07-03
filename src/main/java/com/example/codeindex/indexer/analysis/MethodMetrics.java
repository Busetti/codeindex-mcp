package com.example.codeindex.indexer.analysis;

import com.example.codeindex.indexer.model.MethodMetric;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Computes per-method quality metrics from a JavaParser {@link MethodDeclaration}. Pure AST inspection,
 * no execution — deterministic and cheap. Called from the existing parse pass so no extra parse is needed.
 */
public final class MethodMetrics {

    private static final Pattern SECRET_NAME =
            Pattern.compile("(?i)(password|passwd|secret|api[_-]?key|apikey|access[_-]?token|token|credential)");

    private static final Set<String> SQL_SINKS = Set.of(
            "createNativeQuery", "createQuery", "execute", "executeQuery", "executeUpdate", "prepareStatement");

    private MethodMetrics() {
    }

    public static MethodMetric compute(String methodFqn, String filePath, MethodDeclaration md) {
        int begin = md.getBegin().map(p -> p.line).orElse(0);
        int end = md.getEnd().map(p -> p.line).orElse(begin);
        int loc = Math.max(1, end - begin + 1);

        int cyclomatic = 1
                + md.findAll(IfStmt.class).size()
                + md.findAll(ForStmt.class).size()
                + md.findAll(ForEachStmt.class).size()
                + md.findAll(WhileStmt.class).size()
                + md.findAll(DoStmt.class).size()
                + md.findAll(CatchClause.class).size()
                + md.findAll(SwitchEntry.class).size()
                + md.findAll(ConditionalExpr.class).size()
                + (int) md.findAll(BinaryExpr.class).stream()
                        .filter(b -> b.getOperator() == BinaryExpr.Operator.AND
                                || b.getOperator() == BinaryExpr.Operator.OR)
                        .count();

        int maxNesting = md.getBody().map(b -> maxNesting(b, null, 0)).orElse(0);

        boolean emptyCatch = md.findAll(CatchClause.class).stream()
                .anyMatch(c -> c.getBody().getStatements().isEmpty());

        boolean hasTry = !md.findAll(TryStmt.class).isEmpty();

        boolean stringConcatInLoop = detectStringConcatInLoop(md);
        boolean secretHit = detectSecret(md);
        boolean sqlConcatHit = detectSqlConcat(md);

        return new MethodMetric(methodFqn, filePath, begin, loc, cyclomatic, maxNesting,
                md.getParameters().size(), emptyCatch, stringConcatInLoop, hasTry, secretHit, sqlConcatHit);
    }

    // ---- nesting depth ----------------------------------------------------

    private static int maxNesting(Node node, Node parent, int depth) {
        int max = depth;
        for (Node child : node.getChildNodes()) {
            int d = isNestingBlock(child, parent, node) ? depth + 1 : depth;
            max = Math.max(max, maxNesting(child, node, d));
        }
        return max;
    }

    /** A control-flow block that increases nesting. An {@code else if} chain does not add a level. */
    private static boolean isNestingBlock(Node child, Node grandparent, Node parent) {
        if (child instanceof IfStmt) {
            if (parent instanceof IfStmt p && p.getElseStmt().orElse(null) == child) {
                return false; // else-if
            }
            return true;
        }
        return child instanceof ForStmt || child instanceof ForEachStmt
                || child instanceof WhileStmt || child instanceof DoStmt
                || child instanceof TryStmt || child instanceof SwitchStmt;
    }

    // ---- heuristics -------------------------------------------------------

    private static boolean detectStringConcatInLoop(MethodDeclaration md) {
        for (BinaryExpr b : md.findAll(BinaryExpr.class)) {
            if (b.getOperator() == BinaryExpr.Operator.PLUS
                    && !b.findAll(StringLiteralExpr.class).isEmpty()
                    && insideLoop(b)) {
                return true;
            }
        }
        for (AssignExpr a : md.findAll(AssignExpr.class)) {
            if (a.getOperator() == AssignExpr.Operator.PLUS
                    && a.getTarget() instanceof NameExpr
                    && insideLoop(a)
                    && (!a.getValue().findAll(StringLiteralExpr.class).isEmpty()
                        || a.getValue() instanceof NameExpr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean detectSecret(MethodDeclaration md) {
        for (VariableDeclarator v : md.findAll(VariableDeclarator.class)) {
            if (SECRET_NAME.matcher(v.getNameAsString()).find()
                    && v.getInitializer().map(MethodMetrics::isNonBlankLiteral).orElse(false)) {
                return true;
            }
        }
        for (AssignExpr a : md.findAll(AssignExpr.class)) {
            if (a.getTarget() instanceof NameExpr ne
                    && SECRET_NAME.matcher(ne.getNameAsString()).find()
                    && isNonBlankLiteral(a.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean detectSqlConcat(MethodDeclaration md) {
        for (MethodCallExpr c : md.findAll(MethodCallExpr.class)) {
            if (!SQL_SINKS.contains(c.getNameAsString())) {
                continue;
            }
            for (Expression arg : c.getArguments()) {
                if (arg instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS
                        && !b.findAll(StringLiteralExpr.class).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNonBlankLiteral(Expression e) {
        return e instanceof StringLiteralExpr s && !s.getValue().isBlank();
    }

    /** True if a variable/field name looks like a secret (password, api_key, token, …). */
    public static boolean secretName(String name) {
        return SECRET_NAME.matcher(name).find();
    }

    /** True if the expression is a non-blank string literal (a hardcoded value). */
    public static boolean nonBlankStringLiteral(Expression e) {
        return isNonBlankLiteral(e);
    }

    /** Walks parents up to the enclosing method looking for a loop. */
    private static boolean insideLoop(Node n) {
        Node cur = n.getParentNode().orElse(null);
        while (cur != null && !(cur instanceof MethodDeclaration)) {
            if (cur instanceof ForStmt || cur instanceof ForEachStmt
                    || cur instanceof WhileStmt || cur instanceof DoStmt) {
                return true;
            }
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }
}
