package blastradius.readers;

import blastradius.models.PermissionRequest;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tails agent session/approval log files (like {@code tail -f}) and emits an event when a
 * permission request appears. Starts at the END of each file so old requests are ignored.
 * Handles files that appear late, are truncated, or are rotated. Read-only.
 */
public final class LogReader implements Reader {

    private static final int WINDOW_LINES = 8;

    private final List<Path> files;
    private final Detector detector;
    private final long pollMillis;

    private volatile boolean running;
    private Thread thread;

    /** Per-file tail state. */
    private static final class Tail {
        long pos = -1;                       // -1 = not initialised yet
        Object fileKey;
        final StringBuilder partial = new StringBuilder();
        final Deque<String> lines = new ArrayDeque<>();
        boolean promptShown;
    }

    private final Map<Path, Tail> state = new HashMap<>();

    public LogReader(List<Path> files, Detector detector, long pollMillis) {
        this.files = List.copyOf(files);
        this.detector = detector;
        this.pollMillis = Math.max(50, pollMillis);
    }

    public LogReader(List<Path> files, Detector detector) {
        this(files, detector, 250);
    }

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void start(Listener listener) {
        running = true;
        for (Path f : files) {
            state.put(f, new Tail());
        }
        thread = new Thread(() -> loop(listener), "blastradius-log");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop(Listener listener) {
        while (running) {
            for (Path f : files) {
                Reader.safe(() -> poll(f, state.get(f), listener));
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void poll(Path file, Tail t, Listener listener) throws IOException {
        if (!Files.isRegularFile(file)) {
            t.pos = -1; // wait for it to (re)appear
            return;
        }
        long size = Files.size(file);
        Object key = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey();

        if (t.pos < 0) {
            t.pos = size; // first sight: skip existing content
            t.fileKey = key;
            return;
        }
        boolean rotated = key != null && t.fileKey != null && !key.equals(t.fileKey);
        if (rotated || size < t.pos) {
            t.pos = 0; // new or truncated file: read from the start
            t.partial.setLength(0);
            t.fileKey = key;
        }
        if (size == t.pos) {
            return;
        }

        byte[] data;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(t.pos);
            data = new byte[(int) Math.min(size - t.pos, 1 << 20)];
            raf.readFully(data);
        }
        t.pos += data.length;
        t.partial.append(new String(data, StandardCharsets.UTF_8));

        int nl;
        while ((nl = t.partial.indexOf("\n")) >= 0) {
            String line = t.partial.substring(0, nl).stripTrailing();
            t.partial.delete(0, nl + 1);
            handleLine(t, line, listener);
        }
    }

    private void handleLine(Tail t, String line, Listener listener) {
        t.lines.addLast(line);
        while (t.lines.size() > WINDOW_LINES) {
            t.lines.removeFirst();
        }
        String text = String.join("\n", t.lines);

        if (t.promptShown && detector.promptGone(text)) {
            t.promptShown = false;
            Reader.safe(listener::onPromptGone);
        }
        Optional<PermissionRequest> req = detector.detect(text);
        if (req.isPresent()) {
            t.promptShown = true;
            t.lines.clear(); // don't re-detect the same request on the next line
            Reader.safe(() -> listener.onRequest(req.get()));
        }
    }
}
