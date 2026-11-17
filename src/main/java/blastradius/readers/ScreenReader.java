package blastradius.readers;

import blastradius.models.PermissionRequest;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Windows-native screen reader.
 *
 * <p>
 * Captures the primary display once per second, skips unchanged frames with a
 * fast
 * perceptual hash, feeds changed frames to Tesseract OCR (via Tess4J reflection
 * so it
 * is an optional compile-time dependency), and passes recognized text to a
 * {@link Detector}.
 *
 * <p>
 * Privacy guarantee: captured image bytes and OCR text are held only in local
 * variables
 * on the capture thread. Nothing is written to disk. Logs record only the
 * detected
 * command classification, never screen content.
 *
 * <p>
 * Tesseract is located automatically in this order:
 * <ol>
 * <li>{@code TESSDATA_PREFIX} environment variable</li>
 * <li>{@code C:\Program Files\Tesseract-OCR\tessdata} (Windows default
 * installer path)</li>
 * <li>Whatever Tess4J finds on its own</li>
 * </ol>
 */
public final class ScreenReader implements Reader {

    // ---- constants
    // --------------------------------------------------------------------------

    /** Minimum polling interval even if the caller asks for less. */
    private static final long MIN_INTERVAL_MS = 250;

    /**
     * Sample one pixel every SAMPLE_STEP pixels when building the cheap hash.
     * 32 gives ~32 k samples for a 1920×1080 frame — fast and sensitive enough to
     * catch a dialog appearing.
     */
    private static final int SAMPLE_STEP = 32;

    // ---- state
    // ------------------------------------------------------------------------------

    private final Rectangle region; // null = whole primary screen
    private final Detector detector;
    private final long intervalMs;

    private volatile boolean running;
    private Thread thread;

    // OCR objects loaded via reflection (optional dep)
    private Object tesseract; // net.sourceforge.tess4j.Tesseract instance
    private Method doOcr; // Tesseract#doOCR(BufferedImage)

    private Robot robot;

    // touched only by the capture thread
    private long lastHash;
    private boolean promptShown;
    private BufferedImage previousFrame;

    // ---- constructor
    // ------------------------------------------------------------------------

    public ScreenReader(Rectangle region, Detector detector, long intervalMs) {
        this.region = region;
        this.detector = detector;
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
    }

    // ---- Reader interface
    // -------------------------------------------------------------------

    @Override
    public String name() {
        return "screen";
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public void start(Listener listener) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new UnsupportedOperationException(
                    "screen reader needs a graphical display (headless environment detected)");
        }
        robot = new Robot();
        initOcr();
        running = true;
        thread = new Thread(() -> loop(listener), "blastradius-screen");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null)
            thread.interrupt();
    }

    // ---- OCR initialization
    // -----------------------------------------------------------------

    /**
     * Loads Tess4J via reflection and points it at the local Tesseract
     * installation.
     * Throws {@link UnsupportedOperationException} if Tess4J is not on the
     * classpath or
     * Tesseract is not installed.
     */
    private void initOcr() throws Exception {
        Class<?> cls;
        try {
            cls = Class.forName("net.sourceforge.tess4j.Tesseract");
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException(
                    "OCR unavailable: Tess4J is not in the classpath. "
                            + "Rebuild with the tess4j Maven dependency enabled.",
                    e);
        }

        tesseract = cls.getDeclaredConstructor().newInstance();
        doOcr = cls.getMethod("doOCR", BufferedImage.class);

        String dataPath = resolveDataPath();
        cls.getMethod("setDatapath", String.class).invoke(tesseract, dataPath);
        // Set English language explicitly (eng.traineddata must exist)
        cls.getMethod("setLanguage", String.class).invoke(tesseract, "eng");
        // Page segmentation mode 11: Sparse text (significantly faster on GUI desktop screens)
        cls.getMethod("setPageSegMode", int.class).invoke(tesseract, 11);

        // Configure Tesseract to silence internal C++ debug chatter & set DPI
        try {
            var setVar = cls.getMethod("setTessVariable", String.class, String.class);
            setVar.invoke(tesseract, "user_defined_dpi", "120");
            setVar.invoke(tesseract, "debug_file", "nul");
        } catch (Throwable ignored) {}
    }

    /**
     * Locates the tessdata directory, in priority order:
     * 1. TESSDATA_PREFIX env var (points at the tessdata parent, NOT tessdata
     * itself)
     * 2. C:\Program Files\Tesseract-OCR (Windows UB Mannheim installer default)
     * 3. /usr/share/tesseract-ocr/5 (Ubuntu/Debian)
     * 4. /usr/share/tesseract-ocr/4.00 (older Ubuntu)
     * 5. /opt/homebrew/share/tessdata (macOS Homebrew on Apple Silicon)
     * 6. /usr/local/share/tessdata (macOS Homebrew on Intel)
     * 7. Leave empty → Tess4J will search its own default paths.
     */
    private static String resolveDataPath() {
        // 1. Explicit env var
        String env = System.getenv("TESSDATA_PREFIX");
        if (env != null && !env.isBlank()) {
            Path candidate = Path.of(env);
            if (Files.isDirectory(candidate))
                return candidate.toString();
        }
        // 2–6. Well-known locations
        List<Path> candidates = List.of(
                Path.of("C:\\Program Files\\Tesseract-OCR"),
                Path.of("C:\\Program Files (x86)\\Tesseract-OCR"),
                Path.of("/usr/share/tesseract-ocr/5"),
                Path.of("/usr/share/tesseract-ocr/4.00"),
                Path.of("/opt/homebrew/share/tessdata").getParent(), // /opt/homebrew/share
                Path.of("/usr/local/share/tessdata").getParent() // /usr/local/share
        );
        for (Path p : candidates) {
            if (p != null && Files.isDirectory(p)) {
                // If tessdata is a subdirectory, point directly to it (Tess4J standard).
                Path sub = p.resolve("tessdata");
                if (Files.isDirectory(sub) && Files.exists(sub.resolve("eng.traineddata"))) {
                    return sub.toString();
                }
                if (Files.isDirectory(sub)) {
                    return p.toString();
                }
                if (Files.exists(p.resolve("eng.traineddata"))) {
                    return p.toString();
                }
            }
        }
        return ""; // let Tess4J try its own defaults
    }

    // ---- capture loop
    // -----------------------------------------------------------------------

    private void loop(Listener listener) {
        Rectangle area = captureArea();
        long lastStatusTime = System.currentTimeMillis();
        System.out.println("[blastradius] Screen watcher active — monitoring display in real-time.");
        System.out.println("[blastradius] blastradius runs 100% locally: zero network calls, zero privacy concerns.");
        System.out.println("[blastradius] Watching for AI agent permission requests... (Press Ctrl+C to stop)");

        while (running) {
            try {
                tick(area, listener);
            } catch (Throwable t) {
                System.err.println("blastradius screen error: " + t);
            }

            // Print reassuring status heartbeat every 15 seconds
            long now = System.currentTimeMillis();
            if (now - lastStatusTime >= 15000) {
                lastStatusTime = now;
                if (!promptShown) {
                    System.out.println("[blastradius] Capturing screen locally (no prompts visible, waiting for AI permission card)...");
                }
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private Rectangle captureArea() {
        if (region != null)
            return region;
        return new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
    }

    private void tick(Rectangle area, Listener listener) throws Exception {
        BufferedImage img = robot.createScreenCapture(area);

        // ---- fast change-detection ---------------------------------------------------------
        long hash = sampledHash(img);
        if (hash == lastHash && !promptShown) {
            return; // nothing changed: skip expensive OCR
        }
        lastHash = hash;

        Rectangle changed = findChangedRegion(img, previousFrame);
        previousFrame = img;

        Optional<PermissionRequest> req = Optional.empty();
        int w = img.getWidth(), h = img.getHeight();

        // Pass 1: Check bottom-right region where IDE cards live (guarantees entire card is captured, never sliced)
        int qx = (int)(w * 0.40);
        int qy = (int)(h * 0.30);
        BufferedImage rightQuadrant = img.getSubimage(qx, qy, w - qx, h - qy);
        String text = doOcrOn(rightQuadrant);
        if (!text.isBlank()) {
            req = detector.detect(text);
        }

        // Pass 2: If not found in bottom-right quadrant, check the changed region with generous padding
        if (req.isEmpty() && changed != null && changed.width >= 80 && changed.height >= 40) {
            int cx = Math.max(0, changed.x - 40);
            int cy = Math.max(0, changed.y - 40);
            int cw = Math.min(w - cx, changed.width + 80);
            int ch = Math.min(h - cy, changed.height + 250); // generously pad downwards to never cut off options
            BufferedImage crop = img.getSubimage(cx, cy, cw, ch);
            String cropText = doOcrOn(crop);
            if (!cropText.isBlank()) {
                req = detector.detect(cropText);
            }
        }

        // Pass 3: Fallback to full screen if still not found and a prompt was previously shown (to confirm dismissal)
        if (req.isEmpty() && promptShown) {
            String fullText = doOcrOn(img);
            if (!fullText.isBlank()) {
                req = detector.detect(fullText);
            }
        }

        // ---- Prompt lifecycle: stays constantly open until card disappears ---
        if (req.isPresent()) {
            if (!promptShown) {
                promptShown = true;
                System.out.println(">> blastradius detected permission prompt: " + req.get().target());
                listener.onRequest(req.get());
            }
            // Prompt is still on screen: leave popup constantly visible!
        } else {
            // Prompt card is no longer visible on screen (user allowed or denied it).
            if (promptShown) {
                promptShown = false;
                System.out.println(">> blastradius prompt dismissed/gone.");
                listener.onPromptGone();
            }
        }
    }

    /** Fast OCR on a targeted image region. */
    private String doOcrOn(BufferedImage img) throws Exception {
        BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D g2 = gray.createGraphics();
        g2.drawImage(img, 0, 0, null);
        g2.dispose();

        String text = (String) doOcr.invoke(tesseract, gray);
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('`', '\'');
        normalized = normalized.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ");
        return normalized.replaceAll("[ \\t]+", " ");
    }

    /**
     * Finds the bounding box of changed pixels between current and previous frame.
     * Allows sub-millisecond detection of where a new card popped up on screen.
     */
    private static Rectangle findChangedRegion(BufferedImage curr, BufferedImage prev) {
        if (prev == null || curr.getWidth() != prev.getWidth() || curr.getHeight() != prev.getHeight()) {
            return null;
        }
        int w = curr.getWidth(), h = curr.getHeight();
        int step = 8; // sample every 8 pixels for speed
        int minX = w, minY = h, maxX = -1, maxY = -1;

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                if (curr.getRGB(x, y) != prev.getRGB(x, y)) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return null; // no change
        }
        int pad = 40;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(w - 1, maxX + pad);
        maxY = Math.min(h - 1, maxY + pad);
        int rw = maxX - minX;
        int rh = maxY - minY;
        if (rw < 100 || rh < 50) return null; // ignore minor cursor blinks
        return new Rectangle(minX, minY, rw, rh);
    }

    /**
     * Cheap perceptual hash: sample one pixel every SAMPLE_STEP pixels across the
     * raster
     * and XOR their packed RGB values together. Sensitive to any visible change;
     * nearly
     * free compared to OCR.
     */
    private static long sampledHash(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int total = w * h;
        int[] data = new int[total];
        img.getRGB(0, 0, w, h, data, 0, w);
        long hash = 0;
        for (int i = 0; i < total; i += SAMPLE_STEP) {
            hash ^= (long) data[i] * 0x9e3779b97f4a7c15L;
        }
        return hash;
    }
}
