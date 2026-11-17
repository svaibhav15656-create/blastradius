package blastradius.readers;

import blastradius.Parser;
import blastradius.agents.ProfileDetector;
import blastradius.models.PermissionRequest;
import blastradius.models.RequestType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent-agnostic screen-text detector for the background screen watcher.
 *
 * <p>
 * It does NOT care which agent produced the text. Instead it matches the
 * structural
 * shape that every permission approval prompt shares:
 * <ol>
 * <li>A question line containing keywords like Allow / Run / Approve / Execute
 * / Proceed.</li>
 * <li>Optionally a command line nearby ($ …, ` … `, code blocks, or labelled
 * "Command:…").</li>
 * <li>Choice markers like Yes / No / Allow / Deny / [y/n].</li>
 * </ol>
 *
 * <p>
 * Agent-specific profile detectors are tried first (more accurate); this falls
 * back to
 * the generic detector only when nothing matches.
 *
 * <p>
 * All matching is local and in-memory. Nothing is logged, stored, or sent
 * anywhere.
 */
public final class ScreenDetector implements Reader.Detector {

    // ---- Approval question patterns
    // ---------------------------------------------------------

    /** Matches the kind of question that appears in every agent's approval card. */
    private static final Pattern APPROVAL_QUESTION = Pattern.compile(
            "(?i)(?:"
                    // Google Antigravity CLI and IDE
                    + "Allow\\s+(?:run\\s+)?[^?\\n]*\\?"
                    + "|Do\\s+you\\s+want\\s+to\\s+proceed\\?"
                    + "|Allow\\s+sandbox\\s+bypass"
                    + "|Run\\s+command\\?"
                    // OpenAI Codex
                    + "|Would you like to run the following command\\?"
                    + "|Would you like to make the following edits\\?"
                    + "|Do you want to approve (?:network )?access"
                    + "|needs your approval"
                    // Cursor / Windsurf / VS Code agents
                    + "|(?:Execute|Run)\\s+(?:terminal\\s+)?command\\?"
                    + "|Allow\\s+(?:the\\s+)?(?:command|action|tool)\\s+to\\s+run"
                    + "|terminal command execution"
                    + "|Approve\\s+(?:this\\s+)?(?:command|action|change)"
                    // Claude Code
                    + "|Do you want to execute"
                    + "|Confirm command execution"
                    // Generic / fallback
                    + "|\\[y/n\\]|\\(y/n\\)|\\[yes/no\\]|\\(yes/no\\)"
                    + "|do you want to (?:run|allow|execute|proceed)\\b[^\\n]*\\?"
                    + ")",
            Pattern.MULTILINE);

    /**
     * Choices confirm we are looking at an approval dialog, not a random question.
     */
    private static final Pattern CHOICES = Pattern.compile(
            "(?i)\\b(?:yes|no|allow|deny|reject|cancel|approve|proceed|always allow)\\b",
            Pattern.MULTILINE);

    // ---- Command extraction patterns
    // --------------------------------------------------------

    private static final List<Pattern> COMMAND_EXTRACTORS = List.of(
            // Code block between question and "1 Yes":
            Pattern.compile("(?s)(?i)(?:Allow\\s+[^?\\n]*\\?|Do you want to proceed\\?)\\s*\\n\\s*(?<target>(?:git|rm|npm|cargo|mvn|docker|python|node|Remove-Item|Stop-Process|Set-|iex|iwr|curl|cat|dir|ls|type)[^\\n]*?(?:\\n[^\\n]+?)*?)\\s*\\n\\s*1\\s*Yes"),
            // always allow 'command' (Antigravity IDE card, even if wrapped)
            Pattern.compile("(?s)(?i)always allow\\s*['\"`](?<target>.+?)['\"`](?:\\s+in this conversation)?"),
            // $ command (most terminal agents)
            Pattern.compile("(?m)^\\s*\\$\\s+(?<target>\\S.+?)\\s*$"),
            // `command` (backtick block)
            Pattern.compile("['\"`](?<target>[^'\"`\\n]{3,})['\"`]"),
            // Command: <value>
            Pattern.compile("(?i)(?:Command|Run|Execute|Cmd)\\s*:\\s*['\"`]?(?<target>[^'\"`\\n]{3,})['\"`]?"),
            // Quoted command after "commands that start with"
            Pattern.compile("(?s)(?i)commands that start with\\s*['\"`](?<target>.+?)['\"`]")
    );

    // ---- Prompt-gone patterns
    // ---------------------------------------------------------------

    private static final Pattern PROMPT_GONE = Pattern.compile(
            "(?i)\\b(?:you approved|you denied|you declined|you canceled)\\b",
            Pattern.MULTILINE);

    // ---- Profile detectors (tried first)
    // ----------------------------------------------------

    private final List<ProfileDetector> profileDetectors;
    private final Parser parser;

    /**
     * @param profileDetectors agent-specific detectors tried before the generic
     *                         logic
     * @param parser           for classifying targets extracted by the generic
     *                         logic
     */
    public ScreenDetector(List<ProfileDetector> profileDetectors, Parser parser) {
        this.profileDetectors = List.copyOf(profileDetectors);
        this.parser = parser;
    }

    // ---- Reader.Detector implementation
    // -----------------------------------------------------

    @Override
    public Optional<PermissionRequest> detect(String text) {
        // 1. Try profile-specific detectors first (they know the exact wording).
        for (ProfileDetector pd : profileDetectors) {
            Optional<PermissionRequest> r = pd.detect(text);
            if (r.isPresent())
                return r;
        }

        // 2. Generic structural detection.
        return genericDetect(text);
    }

    @Override
    public boolean promptGone(String text) {
        // Profile detectors may have their own gone-patterns.
        for (ProfileDetector pd : profileDetectors) {
            if (pd.promptGone(text))
                return true;
        }
        return PROMPT_GONE.matcher(text).find();
    }

    // ---- generic detection
    // ------------------------------------------------------------------

    private Optional<PermissionRequest> genericDetect(String text) {
        Matcher qm = APPROVAL_QUESTION.matcher(text);
        if (!qm.find())
            return Optional.empty();

        // Require at least two distinct choice words to reduce false positives.
        Matcher cm = CHOICES.matcher(text);
        int choiceCount = 0;
        while (cm.find())
            choiceCount++;
        if (choiceCount < 2)
            return Optional.empty();

        // Extract the command (look in a 600-char window around the question line).
        int start = Math.max(0, qm.start() - 300);
        int end = Math.min(text.length(), qm.end() + 300);
        String window = text.substring(start, end);

        String target = extractTarget(window);
        RequestType type = target.isBlank() ? RequestType.UNKNOWN : RequestType.SHELL_COMMAND;
        PermissionRequest req = parser.fromPrompt("screen", type, target, window);
        return Optional.of(req);
    }

    private static String extractTarget(String window) {
        for (Pattern p : COMMAND_EXTRACTORS) {
            Matcher m = p.matcher(window);
            if (m.find()) {
                return m.group("target").replaceAll("[\\r\\n]+", " ").replaceAll("[ \\t]+", " ").strip();
            }
        }
        return "";
    }

    // ---- factory
    // ----------------------------------------------------------------------------

    /**
     * Builds a {@link ScreenDetector} from a list of already-loaded
     * {@link ProfileDetector}s.
     * Pass an empty list to use only the generic detector.
     */
    public static ScreenDetector of(List<ProfileDetector> profiles, Path projectDir) {
        return new ScreenDetector(new ArrayList<>(profiles), new Parser(projectDir));
    }
}
