package blastradius.readers;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import blastradius.models.PermissionRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs a terminal agent inside a pseudo-terminal and passes everything through unchanged
 * (keystrokes, colours, resize, exit code), while scanning a COPY of the output for prompts.
 *
 * <p>Output is written to the real terminal before it is analysed, and analysis errors are
 * swallowed, so detection can never alter or delay what the agent shows.
 */
public final class PtyReader implements Reader {

    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]");
    /** Cursor jumps (position / down / next line): a TUI uses these instead of newlines. */
    private static final Pattern CURSOR_NEWLINE = Pattern.compile("\u001B\\[[0-9;]*[HfBEd]");
    /** Cursor forward: usually stands for blank space. */
    private static final Pattern CURSOR_FORWARD = Pattern.compile("\u001B\\[[0-9;]*C");
    private static final int WINDOW_CHARS = 4000;
    /** Wait this long after a prompt first appears so its command/path lines have arrived too. */
    private static final long SETTLE_MILLIS = 150;

    private final List<String> command;
    private final Detector detector;
    private final boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");

    private volatile Listener listener;
    private volatile boolean running;
    private PtyProcess process;
    private Thread outThread;
    private String savedStty;

    // touched only by the output thread
    private final StringBuilder window = new StringBuilder();
    private boolean promptShown;
    private boolean settling;
    private final ScheduledExecutorService settler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "blastradius-settle");
        t.setDaemon(true);
        return t;
    });

    public PtyReader(List<String> command, Detector detector) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("agent command is empty");
        }
        this.command = List.copyOf(command);
        this.detector = detector;
    }

    @Override
    public String name() {
        return "pty";
    }

    @Override
    public void start(Listener l) throws IOException {
        this.listener = l;
        Map<String, String> env = new HashMap<>(System.getenv());
        env.putIfAbsent("TERM", "xterm-256color");

        int[] size = terminalSize();
        process = new PtyProcessBuilder(command.toArray(new String[0]))
                .setEnvironment(env)
                .setInitialColumns(size[0])
                .setInitialRows(size[1])
                .start();

        running = true;
        enterRawMode();
        Runtime.getRuntime().addShutdownHook(new Thread(this::restoreTerminal));

        outThread = daemon("blastradius-out", this::pumpOutput);
        daemon("blastradius-in", this::pumpInput);
        if (!windows) {
            daemon("blastradius-resize", this::pollResize);
        }
    }

    /** Waits for the agent to exit and returns ITS exit code unchanged. */
    public int awaitExit() throws InterruptedException {
        int code = process.waitFor();
        try {
            if (outThread != null) {
                outThread.join(2000); // let the last output flush before restoring the terminal
            }
        } finally {
            stop();
        }
        return code;
    }

    @Override
    public void stop() {
        running = false;
        settler.shutdownNow();
        restoreTerminal();
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    // ---- passthrough pumps ------------------------------------------------------------------

    private void pumpOutput() {
        byte[] buf = new byte[8192];
        try {
            InputStream in = process.getInputStream();
            OutputStream out = System.out;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
                final String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                Reader.safe(() -> scan(chunk)); // analysis happens AFTER passthrough
            }
        } catch (IOException ignored) {
            // child closed the pty
        }
    }

    private void pumpInput() {
        byte[] buf = new byte[4096];
        try {
            OutputStream toChild = process.getOutputStream();
            int n;
            while (running && (n = System.in.read(buf)) != -1) {
                toChild.write(buf, 0, n);
                toChild.flush();
            }
        } catch (IOException ignored) {
            // stdin closed or child gone
        }
    }

    private void pollResize() {
        int[] last = terminalSize();
        while (running && process.isAlive()) {
            try {
                Thread.sleep(400);
                int[] now = terminalSize();
                if (now[0] != last[0] || now[1] != last[1]) {
                    process.setWinSize(new WinSize(now[0], now[1]));
                    last = now;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                // keep going
            }
        }
    }

    // ---- scanning (copy of output only) -----------------------------------------------------

    /** Text for detection only (the real terminal always gets the raw bytes). */
    public static String plain(String chunk) {
        String s = CURSOR_NEWLINE.matcher(chunk).replaceAll("\n");
        s = CURSOR_FORWARD.matcher(s).replaceAll(" ");
        return ANSI.matcher(s).replaceAll("").replace("\r", "");
    }

    private synchronized void scan(String chunk) {
        window.append(plain(chunk));
        if (window.length() > WINDOW_CHARS) {
            window.delete(0, window.length() - WINDOW_CHARS);
        }
        Listener l = listener;
        if (l == null) {
            return;
        }
        String text = window.toString();
        if (!promptShown) {
            if (!settling && detector.detect(text).isPresent()) {
                settling = true;
                settler.schedule(() -> Reader.safe(this::emitSettled), SETTLE_MILLIS, TimeUnit.MILLISECONDS);
            }
        } else if (detector.promptGone(text)) {
            promptShown = false;
            window.setLength(0);
            Reader.safe(l::onPromptGone);
        }
    }

    /** Runs shortly after a prompt appeared: re-reads the text, then reports the request once. */
    private void emitSettled() {
        PermissionRequest req = null;
        synchronized (this) {
            settling = false;
            Optional<PermissionRequest> found = detector.detect(window.toString());
            if (found.isPresent()) {
                promptShown = true;
                window.setLength(0);
                req = found.get();
            }
        }
        Listener l = listener;
        if (req != null && l != null) {
            final PermissionRequest fin = req;
            Reader.safe(() -> l.onRequest(fin));
        }
    }

    // ---- terminal handling (Unix: via stty on /dev/tty) -------------------------------------

    private void enterRawMode() {
        if (windows) {
            return; // TODO: Windows console raw mode
        }
        try {
            savedStty = sh("stty -g < /dev/tty");
            if (savedStty != null && !savedStty.isBlank()) {
                sh("stty raw -echo < /dev/tty");
            } else {
                savedStty = null; // stdin is not a TTY (piped); nothing to change
            }
        } catch (Throwable ignored) {
            savedStty = null;
        }
    }

    private synchronized void restoreTerminal() {
        if (savedStty != null) {
            try {
                sh("stty " + savedStty + " < /dev/tty");
            } catch (Throwable ignored) {
                // best effort
            }
            savedStty = null;
        }
    }

    /** Returns {columns, rows}; falls back to 120x30. */
    private int[] terminalSize() {
        if (!windows) {
            try {
                String out = sh("stty size < /dev/tty"); // "rows cols"
                if (out != null) {
                    String[] p = out.trim().split("\\s+");
                    if (p.length == 2) {
                        return new int[] {Integer.parseInt(p[1]), Integer.parseInt(p[0])};
                    }
                }
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return new int[] {120, 30};
    }

    private static String sh(String cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return p.exitValue() == 0 ? out : null;
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
