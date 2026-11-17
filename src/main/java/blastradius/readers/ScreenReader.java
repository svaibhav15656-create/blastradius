package blastradius.readers;

import blastradius.models.PermissionRequest;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

/**
 * EXPERIMENTAL screen reader for GUI/IDE agents that expose no terminal text.
 *
 * <p>Periodically captures a screen region and runs OCR on it. Frames that did not change
 * are skipped. OCR uses Tess4J via reflection, so it is an optional dependency.
 *
 * <p>NOT IMPLEMENTED: OS accessibility APIs. Java has no portable one; adding it would need
 * JNA/JNI per OS (macOS AX API, Windows UI Automation, Linux AT-SPI).
 *
 * <p>macOS needs Screen Recording permission for the terminal/JVM. May be slow, inaccurate,
 * or break entirely depending on theme, scaling and font.
 */
public final class ScreenReader implements Reader {

    private final Rectangle region;       // null = primary screen
    private final Detector detector;
    private final long intervalMillis;

    private volatile boolean running;
    private Thread thread;
    private Object tesseract;
    private Method doOcr;
    private Robot robot;

    // touched only by the capture thread
    private int lastHash;
    private boolean promptShown;

    public ScreenReader(Rectangle region, Detector detector, long intervalMillis) {
        this.region = region;
        this.detector = detector;
        this.intervalMillis = Math.max(500, intervalMillis);
    }

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
            throw new UnsupportedOperationException("screen reader needs a graphical display");
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
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void initOcr() throws Exception {
        try {
            Class<?> cls = Class.forName("net.sourceforge.tess4j.Tesseract");
            tesseract = cls.getDeclaredConstructor().newInstance();
            String data = System.getenv("TESSDATA_PREFIX");
            if (data != null && !data.isBlank()) {
                cls.getMethod("setDatapath", String.class).invoke(tesseract, data);
            }
            doOcr = cls.getMethod("doOCR", BufferedImage.class);
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException(
                    "OCR unavailable: add Tess4J and Tesseract language data (set TESSDATA_PREFIX)", e);
        }
    }

    private void loop(Listener listener) {
        Rectangle area = region != null ? region : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        while (running) {
            Reader.safe(() -> tick(area, listener));
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void tick(Rectangle area, Listener listener) throws Exception {
        BufferedImage img = robot.createScreenCapture(area);
        int hash = Arrays.hashCode(img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth()));
        if (hash == lastHash) {
            return; // nothing changed, skip the expensive OCR
        }
        lastHash = hash;

        String text = (String) doOcr.invoke(tesseract, img);
        if (text == null || text.isBlank()) {
            return;
        }
        if (!promptShown) {
            Optional<PermissionRequest> req = detector.detect(text);
            if (req.isPresent()) {
                promptShown = true;
                Reader.safe(() -> listener.onRequest(req.get()));
            }
        } else if (detector.promptGone(text)) {
            promptShown = false;
            Reader.safe(listener::onPromptGone);
        }
    }
}
