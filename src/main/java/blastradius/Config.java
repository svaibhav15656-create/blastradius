package blastradius;

import blastradius.models.Severity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User settings from {@code .blastradius.yaml}. Read from the home folder first, then the
 * project folder (project wins). Never written by blastradius.
 *
 * <pre>
 * timeout_seconds: 60        # popup auto-close (0 = never)
 * show_safe: false           # set true to show green popups for SAFE commands too (default false)
 * disable: [sudo]            # rule ids to switch off
 * severity: {read-env-file: critical}
 * rules:                     # extra rules, same schema as rules/default.yaml, checked first
 *   - id: my-rule
 *     applies_to: [shell]
 *     pattern: 'terraform destroy'
 *     severity: critical
 *     what: Tears down infrastructure.
 *     why: Real servers and data can be removed.
 *     affects: [system]
 * </pre>
 */
public record Config(
        Path projectDir,
        Set<String> disabledRules,
        Map<String, Severity> severityOverrides,
        List<Map<String, Object>> extraRules,
        int timeoutSeconds,
        boolean showSafe) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final String FILE_NAME = ".blastradius.yaml";

    public static Config defaults(Path projectDir) {
        return new Config(projectDir, Set.of(), Map.of(), List.of(), DEFAULT_TIMEOUT_SECONDS, true);
    }

    /** Loads settings; a missing or broken file is ignored (fail silently). */
    public static Config load(Path projectDir) {
        Set<String> disabled = new HashSet<>();
        Map<String, Severity> sev = new HashMap<>();
        List<Map<String, Object>> extra = new ArrayList<>();
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        boolean showSafe = true;

        Path home = Path.of(System.getProperty("user.home", "."));
        for (Path f : List.of(home.resolve(FILE_NAME), projectDir.resolve(FILE_NAME))) {
            try {
                if (!Files.isRegularFile(f)) {
                    continue;
                }
                Map<String, Object> y = Yamls.readFile(f);
                timeout  = Yamls.integer(y.get("timeout_seconds"), timeout);
                showSafe = Yamls.bool(y.get("show_safe"), showSafe);
                disabled.addAll(Yamls.strList(y.get("disable")));
                for (Map.Entry<String, Object> e : Yamls.map(y.get("severity")).entrySet()) {
                    sev.put(e.getKey(), Severity.valueOf(String.valueOf(e.getValue()).toUpperCase()));
                }
                extra.addAll(0, Yamls.mapList(y.get("rules"))); // later files go first
            } catch (Exception ignored) {
                // bad user config must never break the agent
            }
        }
        return new Config(projectDir, disabled, sev, extra, timeout, showSafe);
    }
}
