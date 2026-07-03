package com.example.codeindex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Binds {@code codeindex.*} settings. */
@ConfigurationProperties(prefix = "codeindex")
public class CodeindexProperties {

    /** Default source root indexed when reindex is called without an explicit path. */
    private String defaultRoot = "./examples/sample-target";

    /** Logical name used to namespace multiple indexed codebases in the DB. */
    private String defaultRepo = "sample-target";

    /** When true, index the configured repos once on startup so the tools are usable immediately. */
    private boolean reindexOnStartup = true;

    /**
     * Codebases to index on startup and on the schedule. If empty, the single default-root/default-repo
     * pair is used. Configure multiple to serve several services from one server.
     */
    private List<RepoConfig> repos = new ArrayList<>();

    /** Scheduled reindex settings. */
    private Schedule schedule = new Schedule();

    /** Glob patterns (relative to the source root) whose matching .java files are skipped. */
    private List<String> excludeGlobs = new ArrayList<>(List.of(
            "**/target/**", "**/build/**", "**/out/**", "**/bin/**",
            "**/.git/**", "**/generated-sources/**", "**/generated/**"));

    /** When false, test sources ({@code **}/src/test/{@code **}) are also excluded. */
    private boolean includeTests = false;

    /** One indexed codebase: a logical name plus its source root. */
    public static class RepoConfig {
        private String name;
        private String root;

        public RepoConfig() {
        }

        public RepoConfig(String name, String root) {
            this.name = name;
            this.root = root;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
    }

    /** Scheduled reindex configuration. */
    public static class Schedule {
        /** Master switch; when false the scheduled job does nothing even if a cron is set. */
        private boolean enabled = false;
        /** Spring cron expression. Default "-" means disabled. Example: "0 0 * * * *" = hourly. */
        private String cron = "-";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
    }

    public String getDefaultRoot() { return defaultRoot; }
    public void setDefaultRoot(String defaultRoot) { this.defaultRoot = defaultRoot; }

    public String getDefaultRepo() { return defaultRepo; }
    public void setDefaultRepo(String defaultRepo) { this.defaultRepo = defaultRepo; }

    public boolean isReindexOnStartup() { return reindexOnStartup; }
    public void setReindexOnStartup(boolean reindexOnStartup) { this.reindexOnStartup = reindexOnStartup; }

    public List<RepoConfig> getRepos() { return repos; }
    public void setRepos(List<RepoConfig> repos) { this.repos = repos; }

    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule schedule) { this.schedule = schedule; }

    public List<String> getExcludeGlobs() { return excludeGlobs; }
    public void setExcludeGlobs(List<String> excludeGlobs) { this.excludeGlobs = excludeGlobs; }

    public boolean isIncludeTests() { return includeTests; }
    public void setIncludeTests(boolean includeTests) { this.includeTests = includeTests; }

    /** The repos to index: the explicit list if set, otherwise the single default pair. */
    public List<RepoConfig> effectiveRepos() {
        if (repos != null && !repos.isEmpty()) {
            return repos;
        }
        return List.of(new RepoConfig(defaultRepo, defaultRoot));
    }

    /** Effective exclude list, adding the test-sources glob unless tests are explicitly included. */
    public List<String> effectiveExcludes() {
        List<String> all = new ArrayList<>(excludeGlobs);
        if (!includeTests) {
            all.add("**/src/test/**");
        }
        return all;
    }
}
