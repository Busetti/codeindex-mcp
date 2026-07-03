package com.example.codeindex.rules;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Persists per-repo {@link RuleConfig} (as columns) plus the last-indexed source root. */
@Repository
public class RuleConfigStore {

    private final JdbcTemplate jdbc;

    public RuleConfigStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RuleConfig> MAPPER = (rs, i) -> new RuleConfig(
            rs.getInt("god_class_loc"),
            rs.getInt("god_class_methods"),
            rs.getInt("long_method_loc"),
            rs.getInt("high_complexity"),
            rs.getInt("deep_nesting"),
            rs.getInt("too_many_params"),
            parseRules(rs.getString("disabled_rules")));

    /** Saved config for the repo, or {@link RuleConfig#defaults()} when none exists. */
    public RuleConfig get(String repo) {
        List<RuleConfig> rows = jdbc.query("""
                SELECT god_class_loc, god_class_methods, long_method_loc, high_complexity,
                       deep_nesting, too_many_params, disabled_rules
                FROM repo_config WHERE repo = ?""", MAPPER, repo);
        return rows.isEmpty() ? RuleConfig.defaults() : rows.get(0);
    }

    /** Upsert the rule config, leaving any stored root untouched. */
    public void save(String repo, RuleConfig c) {
        jdbc.update("""
                INSERT INTO repo_config
                    (repo, god_class_loc, god_class_methods, long_method_loc, high_complexity,
                     deep_nesting, too_many_params, disabled_rules, updated_at)
                VALUES (?,?,?,?,?,?,?,?, now())
                ON CONFLICT (repo) DO UPDATE SET
                    god_class_loc = excluded.god_class_loc,
                    god_class_methods = excluded.god_class_methods,
                    long_method_loc = excluded.long_method_loc,
                    high_complexity = excluded.high_complexity,
                    deep_nesting = excluded.deep_nesting,
                    too_many_params = excluded.too_many_params,
                    disabled_rules = excluded.disabled_rules,
                    updated_at = now()""",
                repo, c.godClassLoc(), c.godClassMethods(), c.longMethodLoc(), c.highComplexity(),
                c.deepNesting(), c.tooManyParams(), joinRules(c.disabledRulesOrEmpty()));
    }

    /** Remember where a repo was indexed from (so config re-apply can reparse without re-entering it). */
    public void saveRoot(String repo, String root) {
        jdbc.update("""
                INSERT INTO repo_config (repo, root, updated_at) VALUES (?, ?, now())
                ON CONFLICT (repo) DO UPDATE SET root = excluded.root, updated_at = now()""",
                repo, root);
    }

    public String getRoot(String repo) {
        List<String> rows = jdbc.query("SELECT root FROM repo_config WHERE repo = ?",
                (rs, i) -> rs.getString(1), repo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Set<String> parseRules(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String joinRules(Set<String> rules) {
        return String.join(",", rules);
    }
}
