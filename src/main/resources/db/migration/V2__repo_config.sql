-- Per-repo hotspot rule configuration (thresholds + disabled categories) and the last-indexed
-- source root, so a config change can be re-applied without the user re-entering the path.
-- Column defaults mirror RuleConfig.defaults() so partial upserts (e.g. saving just the root) work.
CREATE TABLE IF NOT EXISTS repo_config (
    repo             TEXT PRIMARY KEY,
    god_class_loc    INT  NOT NULL DEFAULT 500,
    god_class_methods INT NOT NULL DEFAULT 20,
    long_method_loc  INT  NOT NULL DEFAULT 80,
    high_complexity  INT  NOT NULL DEFAULT 10,
    deep_nesting     INT  NOT NULL DEFAULT 4,
    too_many_params  INT  NOT NULL DEFAULT 5,
    disabled_rules   TEXT NOT NULL DEFAULT '',   -- comma-separated hotspot category names
    root             TEXT,                       -- absolute source path last indexed for this repo
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);
