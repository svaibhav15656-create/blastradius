package blastradius.daemon;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Manages the blastradius background daemon lifecycle.
 *
 * <p>State is kept in {@code ~/.blastradius/daemon.pid} (PID) and
 * {@code ~/.blastradius/daemon.lock} (file lock held by the running JVM). No network calls.
 */
public final class DaemonManager {

    private static final String STATE_DIR_NAME  = ".blastradius";
    private static final String PID_FILE        = "daemon.pid";
    private static final String LOCK_FILE       = "daemon.lock";
    private static final String LOG_FILE        = "daemon.log";
    private static final String START_FILE      = "daemon.started";

    private DaemonManager() {}

    // ---- public API -------------------------------------------------------------------------

    /**
     * Start the blastradius screen-watcher daemon in the background.
     *
     * @param jarPath absolute path to the blastradius fat JAR
     * @return PID of the newly started process, or the existing PID if already running
     */
    public static long start(Path jarPath) throws IOException {
        Optional<Long> existing = runningPid();
        if (existing.isPresent()) {
            return existing.get();
        }
        Path dir = stateDir();
        Path log = dir.resolve(LOG_FILE);

        // Build the command: use javaw.exe on Windows so there is no console window,
        // java everywhere else.
        String javaExe = isWindows() ? "javaw.exe" : "java";
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path javaFull = javaHome.resolve("bin").resolve(javaExe);
        if (!Files.isRegularFile(javaFull)) {
            javaFull = javaHome.resolve("bin").resolve("java"); // fallback
        }

        ProcessBuilder pb = new ProcessBuilder(
                javaFull.toString(),
                "-Dblastradius.daemon=true",
                "-Dapple.awt.UIElement=true",
                "-jar", jarPath.toAbsolutePath().toString(),
                "start-daemon"            // internal sub-command handled in Cli
        );
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        pb.directory(dir.toFile());

        Process p = pb.start();
        long pid = p.pid();

        // Give the daemon a moment to write its own PID, then verify.
        try { Thread.sleep(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // If the daemon wrote its own PID use that, otherwise store the one we have.
        if (!pidFile().toFile().exists()) {
            writePid(pid);
        }
        return pid;
    }

    /** Stop the daemon. Returns true if a running daemon was found and stopped. */
    public static boolean stop() throws IOException {
        Optional<Long> pid = runningPid();
        if (pid.isEmpty()) {
            return false;
        }
        Optional<ProcessHandle> ph = ProcessHandle.of(pid.get());
        if (ph.isPresent()) {
            ph.get().destroy();
            // Wait up to 3 s for clean exit.
            try {
                long deadline = System.currentTimeMillis() + 3000;
                while (ph.get().isAlive() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
                if (ph.get().isAlive()) {
                    ph.get().destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        cleanup();
        return true;
    }

    /** Status string shown by {@code blastradius status}. */
    public static String status() {
        try {
            Optional<Long> pid = runningPid();
            if (pid.isEmpty()) {
                return "blastradius daemon is NOT running.";
            }
            long p = pid.get();
            Optional<ProcessHandle> ph = ProcessHandle.of(p);
            if (ph.isEmpty() || !ph.get().isAlive()) {
                cleanup();
                return "blastradius daemon is NOT running (stale PID " + p + " removed).";
            }
            String uptime = "";
            try {
                Instant started = readStartTime();
                Duration d = Duration.between(started, Instant.now());
                long h = d.toHours(), m = d.toMinutesPart(), s = d.toSecondsPart();
                uptime = String.format(" | uptime %dh %02dm %02ds", h, m, s);
            } catch (Exception ignored) {}
            return "blastradius daemon is RUNNING (PID " + p + uptime + ")\n"
                    + "  Watching: entire screen, all agents (Antigravity · Codex · Cursor · generic)\n"
                    + "  Log: " + stateDir().resolve(LOG_FILE);
        } catch (Exception e) {
            return "blastradius status error: " + e.getMessage();
        }
    }

    /**
     * Called by the daemon process itself immediately on startup: writes PID + start-time
     * and acquires the lock file so we can detect stale PIDs without kill.
     *
     * @return a {@link FileLock} that keeps the lock open; hold it for the daemon's lifetime.
     */
    public static FileLock acquireDaemonLock() throws IOException {
        Path dir = stateDir();
        writePid(ProcessHandle.current().pid());
        Files.writeString(dir.resolve(START_FILE),
                String.valueOf(System.currentTimeMillis()), StandardCharsets.UTF_8);
        return FileChannel.open(dir.resolve(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                .tryLock();
    }

    // ---- internals --------------------------------------------------------------------------

    public static Optional<Long> runningPid() {
        try {
            Path f = pidFile();
            if (!Files.isRegularFile(f)) return Optional.empty();
            long pid = Long.parseLong(Files.readString(f, StandardCharsets.UTF_8).strip());
            // Check if lock file is held (meaning the daemon is alive).
            Path lock = stateDir().resolve(LOCK_FILE);
            if (Files.exists(lock)) {
                try (FileChannel fc = FileChannel.open(lock,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    FileLock tryLock = fc.tryLock();
                    if (tryLock == null) {
                        // Lock is held by another process — daemon is running.
                        return Optional.of(pid);
                    }
                    tryLock.release();
                }
            }
            // Lock file exists but not held: also check OS process table.
            return ProcessHandle.of(pid).map(ph -> ph.isAlive() ? pid : null);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static void writePid(long pid) throws IOException {
        Files.writeString(pidFile(), String.valueOf(pid), StandardCharsets.UTF_8);
    }

    private static Path pidFile() throws IOException {
        return stateDir().resolve(PID_FILE);
    }

    private static Path stateDir() throws IOException {
        Path dir = Path.of(System.getProperty("user.home", "."), STATE_DIR_NAME);
        Files.createDirectories(dir);
        return dir;
    }

    private static Instant readStartTime() throws IOException {
        Path f = stateDir().resolve(START_FILE);
        return Instant.ofEpochMilli(Long.parseLong(Files.readString(f, StandardCharsets.UTF_8).strip()));
    }

    private static void cleanup() {
        try {
            Files.deleteIfExists(pidFile());
            Files.deleteIfExists(stateDir().resolve(LOCK_FILE));
            Files.deleteIfExists(stateDir().resolve(START_FILE));
        } catch (Exception ignored) {}
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
