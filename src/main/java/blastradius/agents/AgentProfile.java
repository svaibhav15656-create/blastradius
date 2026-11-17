package blastradius.agents;

import blastradius.Yamls;
import blastradius.models.RequestType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One agent profile (agents/*.yaml): reader type, prompt-detection regexes, how to extract
 * the target, and prompt-disappeared regexes.
 *
 * <p>To add an agent, drop {@code <name>.yaml} into {@code ~/.blastradius/agents/}; it takes
 * priority over a built-in profile of the same name.
 */
public record AgentProfile(
        String name,
        String description,
        ReaderKind reader,
        Status status,
        List<PromptSpec> prompts,
        List<Pattern> promptGone,
        List<String> logPaths,
        boolean logVerified,
        List<String> notes) {

    /** How the profile expects to read the agent. */
    public enum ReaderKind { PTY, LOG, SCREEN }

    /** How much of the profile has been checked against real agent behaviour. */
    public enum Status { VERIFIED, PARTIAL, UNVERIFIED }

    /**
     * One kind of prompt.
     *
     * @param targets regexes tried in order; each needs a named group {@code target}
     * @param targetBefore true when the command/path is printed BEFORE the prompt title
     */
    public record PromptSpec(
            String id, RequestType type, Pattern detect, List<Pattern> targets, boolean verified, boolean targetBefore) {}

    public static final List<String> BUILTIN = List.of("codex", "antigravity", "cursor", "claudecode", "generic");

    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9_-]+");

    /** Loads a profile by name (user folder first, then built-in). */
    public static AgentProfile load(String name) throws IOException {
        String n = name.toLowerCase(Locale.ROOT);
        if (!SAFE_NAME.matcher(n).matches()) {
            throw new IOException("invalid agent name: " + name);
        }
        Path user = Path.of(System.getProperty("user.home", "."), ".blastradius", "agents", n + ".yaml");
        Map<String, Object> y;
        if (Files.isRegularFile(user)) {
            y = Yamls.readFile(user);
        } else if (BUILTIN.contains(n)) {
            y = Yamls.readResource("/blastradius/agents/" + n + ".yaml");
        } else {
            throw new IOException("unknown agent '" + name + "' (try: blastradius agents)");
        }
        return parse(n, y);
    }

    /** Loads every built-in profile plus any in the user folder. */
    public static List<AgentProfile> loadAll() {
        List<String> names = new ArrayList<>(BUILTIN);
        Path dir = Path.of(System.getProperty("user.home", "."), ".blastradius", "agents");
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                s.map(p -> p.getFileName().toString())
                        .filter(f -> f.endsWith(".yaml"))
                        .map(f -> f.substring(0, f.length() - 5).toLowerCase(Locale.ROOT))
                        .filter(f -> SAFE_NAME.matcher(f).matches() && !names.contains(f))
                        .forEach(names::add);
            } catch (IOException ignored) {
                // list what we have
            }
        }
        List<AgentProfile> out = new ArrayList<>();
        for (String n : names) {
            try {
                out.add(load(n));
            } catch (IOException | RuntimeException ignored) {
                // skip broken profiles
            }
        }
        return out;
    }

    /** Pick a profile name from the agent's executable, falling back to "generic". */
    public static String guessFor(String executable) {
        String base = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT);
        if (base.startsWith("codex"))       return "codex";
        if (base.contains("antigravity"))   return "antigravity";
        if (base.contains("cursor"))        return "cursor";
        if (base.contains("claude"))        return "claudecode";
        return "generic";
    }

    /** Expands {@code ~}, {@code $VAR} and {@code ${VAR}} in a profile path ($CODEX_HOME defaults to ~/.codex). */
    public static Path expand(String s) {
        String home = System.getProperty("user.home", ".");
        String out = s.startsWith("~") ? home + s.substring(1) : s;
        for (String var : List.of("CODEX_HOME")) {
            String val = System.getenv(var);
            if (val == null || val.isBlank()) {
                val = home + "/.codex";
            }
            out = out.replace("${" + var + "}", val).replace("$" + var, val);
        }
        return Path.of(out);
    }

    private static AgentProfile parse(String name, Map<String, Object> y) throws IOException {
        try {
            List<PromptSpec> prompts = new ArrayList<>();
            for (Map<String, Object> p : Yamls.mapList(y.get("prompts"))) {
                List<Pattern> targets = new ArrayList<>();
                for (String t : Yamls.strList(p.get("target"))) {
                    if (!t.contains("(?<target>")) {
                        throw new IOException("target regex needs a (?<target>...) group: " + t);
                    }
                    targets.add(Pattern.compile(t, Pattern.MULTILINE));
                }
                prompts.add(new PromptSpec(
                        Yamls.str(p.get("id"), "prompt"),
                        RequestType.valueOf(Yamls.str(p.get("type"), "unknown").toUpperCase(Locale.ROOT)),
                        Pattern.compile(Yamls.str(p.get("detect"), "(?!)"), Pattern.MULTILINE),
                        targets,
                        Yamls.bool(p.get("verified"), false),
                        Yamls.bool(p.get("target_before"), false)));
            }
            List<Pattern> gone = new ArrayList<>();
            for (String g : Yamls.strList(y.get("prompt_gone"))) {
                gone.add(Pattern.compile(g, Pattern.MULTILINE));
            }
            Map<String, Object> log = Yamls.map(y.get("log"));
            return new AgentProfile(
                    Yamls.str(y.get("name"), name),
                    Yamls.str(y.get("description"), ""),
                    ReaderKind.valueOf(Yamls.str(y.get("reader"), "pty").toUpperCase(Locale.ROOT)),
                    Status.valueOf(Yamls.str(y.get("status"), "unverified").toUpperCase(Locale.ROOT)),
                    prompts, gone,
                    Yamls.strList(log.get("paths")),
                    Yamls.bool(log.get("verified"), false),
                    Yamls.strList(y.get("notes")));
        } catch (IllegalArgumentException e) {
            throw new IOException("bad profile '" + name + "': " + e.getMessage(), e);
        }
    }
}
