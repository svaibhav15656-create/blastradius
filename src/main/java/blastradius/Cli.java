package blastradius;

import blastradius.agents.AgentProfile;
import blastradius.agents.ProfileDetector;
import blastradius.daemon.DaemonManager;
import blastradius.models.Explanation;
import blastradius.models.PermissionRequest;
import blastradius.readers.LogReader;
import blastradius.readers.PtyReader;
import blastradius.readers.Reader;
import blastradius.readers.ScreenDetector;
import blastradius.readers.ScreenReader;
import blastradius.ui.Presenter;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Entry point: start, stop, status, run, watch, screen, explain, agents.
 *
 * <p>The three new lifecycle commands are:
 * <ul>
 *   <li>{@code blastradius start}  — spawn background screen-watcher daemon</li>
 *   <li>{@code blastradius stop}   — terminate the daemon</li>
 *   <li>{@code blastradius status} — report daemon health</li>
 * </ul>
 *
 * {@code start-daemon} is an internal sub-command invoked by the daemon process itself;
 * it is not documented in the user-visible help.
 */
public final class Cli {

    private Cli() {}

    public static void main(String[] args) {
        // macOS: run AWT as a background "UI element" app so the popup does not take focus.
        System.setProperty("apple.awt.UIElement", "true");
        int code;
        try {
            code = dispatch(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.err.println("blastradius: " + t.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    private static int dispatch(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            usage();
            return 0;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0]) {
            // ---- new lifecycle commands
            case "start"        -> start();
            case "stop"         -> stop();
            case "status"       -> status();
            case "diagnose"     -> { Diagnose.main(rest); yield 0; }
            case "start-daemon" -> startDaemon();   // internal; called by DaemonManager

            // ---- existing commands
            case "run"     -> run(rest);
            case "watch"   -> watch(rest);
            case "screen"  -> screen(rest);
            case "explain" -> explain(rest);
            case "agents"  -> agents();
            default -> {
                usage();
                yield 2;
            }
        };
    }

    private static void usage() {
        System.err.println("""
                blastradius: read-only explainer for AI agent permission requests

                  blastradius start                                   start background watcher (all agents)
                  blastradius stop                                    stop background watcher
                  blastradius status                                  check watcher health

                  blastradius run [--agent NAME] [--] <cmd...>       wrap a terminal agent (PTY)
                  blastradius watch <agent> [--file PATH]...         tail agent log file(s)
                  blastradius screen [agent]                         EXPERIMENTAL foreground screen reader
                  blastradius explain "<text>"                       dry run
                  blastradius agents                                 list known agent profiles

                explain prefixes: edit: create: delete: net: tool: cmd:  (default: shell command, or net for a URL)""");
    }

    // ---- start / stop / status (daemon lifecycle) ------------------------------------------

    /**
     * Starts the blastradius daemon in the background.
     * Uses javaw.exe on Windows so there is no console window.
     */
    private static int start() throws IOException {
        Optional<Long> pid = DaemonManager.runningPid();
        if (pid.isPresent()) {
            System.out.println("blastradius daemon is already running (PID " + pid.get() + ").");
            System.out.println("Use 'blastradius status' to check health or 'blastradius stop' to stop it.");
            return 0;
        }

        // Find the JAR we are running from.
        Path jar;
        try {
            jar = Path.of(Cli.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            System.err.println("blastradius: cannot locate JAR to start daemon: " + e.getMessage());
            return 1;
        }

        long newPid = DaemonManager.start(jar);
        System.out.println("blastradius daemon started (PID " + newPid + ").");
        System.out.println("Watching your screen for permission prompts from any AI agent.");
        System.out.println("  blastradius status   — check daemon health");
        System.out.println("  blastradius stop     — stop the daemon");
        return 0;
    }

    private static int stop() throws IOException {
        boolean stopped = DaemonManager.stop();
        if (stopped) {
            System.out.println("blastradius daemon stopped.");
        } else {
            System.out.println("blastradius daemon is not running.");
        }
        return 0;
    }

    private static int status() {
        System.out.println(DaemonManager.status());
        return 0;
    }

    /**
     * Internal: called inside the daemon JVM.  Acquires the lock file, then runs the
     * screen reader forever (until the process is killed by {@code stop}).
     */
    private static int startDaemon() throws Exception {
        // Acquire the lock file — this is what lets status/stop know we are alive.
        FileLock lock = DaemonManager.acquireDaemonLock();
        if (lock == null) {
            System.err.println("blastradius: could not acquire daemon lock — another instance is running.");
            return 1;
        }

        Path project = Path.of(System.getProperty("user.home", "."));
        Config cfg = Config.load(project);
        Explainer explainer = Explainer.load(cfg);
        Parser parser = new Parser(project);

        // Load all known agent profiles as profile-specific detectors.
        List<ProfileDetector> profileDetectors = new ArrayList<>();
        for (AgentProfile p : AgentProfile.loadAll()) {
            profileDetectors.add(new ProfileDetector(p, parser));
        }

        // Compose the universal screen detector.
        ScreenDetector detector = ScreenDetector.of(profileDetectors, project);

        Presenter presenter = new Presenter(explainer, cfg.timeoutSeconds(), cfg.showSafe());

        System.out.println("blastradius daemon running — watching screen for AI permission prompts.");
        System.out.println("All agents (Antigravity · Codex · Cursor · generic) are covered.");
        System.out.println("Nothing is sent to any network or stored. Press Ctrl-C or run 'blastradius stop'.");

        try (ScreenReader reader = new ScreenReader(null, detector, 300)) {
            reader.start(presenter);
            Thread.currentThread().join(); // run until killed
        }
        return 0;
    }

    // ---- run ---------------------------------------------------------------------------------

    private static int run(String[] a) throws Exception {
        List<String> cmd = new ArrayList<>(Arrays.asList(a));
        String agent = null;
        if (cmd.size() >= 2 && cmd.get(0).equals("--agent")) {
            agent = cmd.get(1);
            cmd.subList(0, 2).clear();
        }
        if (!cmd.isEmpty() && cmd.get(0).equals("--")) {
            cmd.remove(0);
        }
        if (cmd.isEmpty()) {
            usage();
            return 2;
        }

        // Setup problems must never stop the agent: run it unwatched instead.
        Reader.Detector detector = NO_DETECTION;
        Reader.Listener listener = new Reader.Listener() {
            @Override
            public void onRequest(PermissionRequest request) {}
        };
        try {
            Path project = Path.of("").toAbsolutePath();
            Config cfg = Config.load(project);
            Explainer explainer = Explainer.load(cfg);
            AgentProfile profile = AgentProfile.load(agent != null ? agent : AgentProfile.guessFor(cmd.get(0)));
            detector = new ProfileDetector(profile, new Parser(project));
            listener = new Presenter(explainer, cfg.timeoutSeconds(), cfg.showSafe());
        } catch (Throwable setup) {
            System.err.println("blastradius: running without explanations (" + setup.getMessage() + ")");
        }

        try (PtyReader reader = new PtyReader(cmd, detector)) {
            reader.start(listener);
            return reader.awaitExit(); // the agent's own exit code, unchanged
        }
    }

    // ---- watch / screen -----------------------------------------------------------------------

    private static int watch(String[] a) throws Exception {
        if (a.length == 0) {
            usage();
            return 2;
        }
        AgentProfile profile = AgentProfile.load(a[0]);
        List<Path> files = new ArrayList<>();
        for (int i = 1; i < a.length - 1; i++) {
            if (a[i].equals("--file")) {
                files.add(Path.of(a[++i]));
            }
        }
        if (files.isEmpty()) {
            for (String p : profile.logPaths()) {
                newest(AgentProfile.expand(p)).ifPresent(files::add);
            }
        }
        if (files.isEmpty()) {
            System.err.println("No log file found for '" + profile.name() + "'. Pass --file PATH.");
            return 2;
        }
        if (!profile.logVerified()) {
            System.err.println("blastradius: log detection for '" + profile.name() + "' is UNVERIFIED; "
                    + "'blastradius run' is the more reliable option.");
        }
        Path project = Path.of("").toAbsolutePath();
        Config cfg = Config.load(project);
        Explainer explainer = Explainer.load(cfg);
        try (LogReader reader = new LogReader(files, new ProfileDetector(profile, new Parser(project)))) {
            reader.start(new Presenter(explainer, cfg.timeoutSeconds(), cfg.showSafe()));
            Thread.currentThread().join(); // until Ctrl-C
        }
        return 0;
    }

    private static int screen(String[] a) throws Exception {
        System.err.println("blastradius screen: EXPERIMENTAL. Uses screenshots + OCR and may miss or misread prompts.");
        Path project = Path.of("").toAbsolutePath();
        Config cfg = Config.load(project);
        Explainer explainer = Explainer.load(cfg);
        Parser parser = new Parser(project);

        // Load all profile detectors for the universal screen detector.
        List<ProfileDetector> profileDetectors = new ArrayList<>();
        for (AgentProfile p : AgentProfile.loadAll()) {
            profileDetectors.add(new ProfileDetector(p, parser));
        }
        ScreenDetector detector = ScreenDetector.of(profileDetectors, project);

        try (ScreenReader reader = new ScreenReader(null, detector, 300)) {
            reader.start(new Presenter(explainer, cfg.timeoutSeconds(), cfg.showSafe()));
            Thread.currentThread().join();
        }
        return 0;
    }

    /** Newest *.jsonl under a directory, or the file itself. */
    private static Optional<Path> newest(Path p) {
        try {
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p, 5)) {
                    return s.filter(f -> f.toString().endsWith(".jsonl") && Files.isRegularFile(f))
                            .max(Comparator.comparingLong(f -> f.toFile().lastModified()));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // treat as not found
        }
        return Optional.empty();
    }

    // ---- explain / agents ---------------------------------------------------------------------

    private static int explain(String[] a) throws Exception {
        if (a.length == 0) {
            usage();
            return 2;
        }
        Path project = Path.of("").toAbsolutePath();
        Config cfg = Config.load(project);
        Explainer explainer = Explainer.load(cfg);
        for (String err : explainer.loadErrors()) {
            System.err.println("warning: " + err);
        }
        PermissionRequest req = new Parser(project).parseFreeText("explain", String.join(" ", a));
        Explanation ex = explainer.explain(req);

        boolean color = System.console() != null;
        String c = !color ? "" : switch (ex.severity()) {
            case CRITICAL -> "\u001B[1;31m";
            case WARNING  -> "\u001B[1;33m";
            case SAFE     -> "\u001B[1;32m";
        };
        String reset = color ? "\u001B[0m" : "";
        System.out.println(c + ex.severity() + reset + "  (" + req.type().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + ", rule: " + ex.ruleId() + ")");
        if (!req.target().isBlank()) {
            System.out.println("Target:         " + req.target());
        }
        System.out.println("What it does:   " + ex.what());
        System.out.println("Why it matters: " + ex.why());
        if (!ex.affects().isEmpty()) {
            System.out.println("Affects:        " + String.join(", ", ex.affects()));
        }
        return 0;
    }

    private static int agents() {
        System.out.printf("%-13s %-11s %-7s %s%n", "AGENT", "STATUS", "READER", "DESCRIPTION");
        for (AgentProfile p : AgentProfile.loadAll()) {
            System.out.printf("%-13s %-11s %-7s %s%n", p.name(), p.status().name().toLowerCase(Locale.ROOT),
                    p.reader().name().toLowerCase(Locale.ROOT), p.description());
            long ok = p.prompts().stream().filter(AgentProfile.PromptSpec::verified).count();
            System.out.printf("%-13s prompts verified: %d of %d; log detection verified: %s%n", "",
                    ok, p.prompts().size(), p.logVerified() ? "yes" : "no");
        }
        return 0;
    }

    private static final Reader.Detector NO_DETECTION = new Reader.Detector() {
        @Override
        public Optional<PermissionRequest> detect(String recentText) { return Optional.empty(); }

        @Override
        public boolean promptGone(String recentText) { return false; }
    };
}
