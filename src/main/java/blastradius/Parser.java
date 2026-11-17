package blastradius;

import blastradius.models.PermissionRequest;
import blastradius.models.RequestType;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns raw prompt text into a classified {@link PermissionRequest}.
 *
 * <p>Agent profiles supply a type hint and a target; the parser cleans the target and refines
 * the type (sudo becomes privilege escalation, paths outside the project become
 * outside-project access, bare URLs become network access).
 */
public final class Parser {

    private static final Pattern URL = Pattern.compile("^(?:https?|ftp|wss?)://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCALATE =
            Pattern.compile("^(?:\\w+=\\S*\\s+)*(?:sudo|doas|pkexec|runas)\\b");
    private static final Pattern EDGE_NOISE = Pattern.compile("^[\\s│┃]+|[\\s│┃]+$");

    private static final Map<String, RequestType> PREFIXES = Map.of(
            "cmd:", RequestType.SHELL_COMMAND,
            "edit:", RequestType.FILE_EDIT,
            "create:", RequestType.FILE_CREATE,
            "delete:", RequestType.FILE_DELETE,
            "net:", RequestType.NETWORK_ACCESS,
            "tool:", RequestType.TOOL_CALL);

    private final Path projectDir;

    /** @param projectDir the folder the agent works in (null = unknown) */
    public Parser(Path projectDir) {
        this.projectDir = projectDir == null ? null : projectDir.toAbsolutePath().normalize();
    }

    /** Build a request from a detected prompt. */
    public PermissionRequest fromPrompt(String agent, RequestType hint, String target, String raw) {
        String cleaned = clean(target);
        return PermissionRequest.of(agent, classify(hint, cleaned), cleaned, raw);
    }

    /**
     * Dry-run parsing for {@code blastradius explain}. A prefix picks the type
     * ({@code edit:}, {@code create:}, {@code delete:}, {@code net:}, {@code tool:}, {@code cmd:});
     * otherwise a URL is network access and anything else is a shell command.
     */
    public PermissionRequest parseFreeText(String agent, String text) {
        String s = clean(text);
        RequestType hint = null;
        String lower = s.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, RequestType> e : PREFIXES.entrySet()) {
            if (lower.startsWith(e.getKey())) {
                hint = e.getValue();
                s = s.substring(e.getKey().length()).strip();
                break;
            }
        }
        if (hint == null) {
            hint = URL.matcher(s).matches() ? RequestType.NETWORK_ACCESS : RequestType.SHELL_COMMAND;
        }
        return fromPrompt(agent, hint, s, text);
    }

    /** Refine a type hint using the target. */
    public RequestType classify(RequestType hint, String target) {
        RequestType t = hint == null ? RequestType.UNKNOWN : hint;
        if (t == RequestType.UNKNOWN && URL.matcher(target).matches()) {
            return RequestType.NETWORK_ACCESS;
        }
        if (t == RequestType.SHELL_COMMAND && ESCALATE.matcher(target).find()) {
            return RequestType.PRIVILEGE_ESCALATION;
        }
        boolean fileOp = t == RequestType.FILE_EDIT || t == RequestType.FILE_CREATE || t == RequestType.FILE_DELETE;
        if (fileOp && isOutsideProject(target)) {
            return RequestType.OUTSIDE_PROJECT_ACCESS;
        }
        return t;
    }

    /** Strip box-drawing borders and surrounding spaces that TUIs add. */
    static String clean(String s) {
        return s == null ? "" : EDGE_NOISE.matcher(s).replaceAll("");
    }

    boolean isOutsideProject(String target) {
        if (projectDir == null || target.isBlank()) {
            return false;
        }
        try {
            String t = target;
            if (t.equals("~") || t.startsWith("~/") || t.startsWith("~\\")) {
                t = System.getProperty("user.home", "") + t.substring(1);
            }
            Path p = Path.of(t);
            Path abs = p.isAbsolute() ? p.normalize() : projectDir.resolve(p).normalize();
            return !abs.startsWith(projectDir);
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
