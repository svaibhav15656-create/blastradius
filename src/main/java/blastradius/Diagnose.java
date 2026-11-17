package blastradius;

import blastradius.agents.AgentProfile;
import blastradius.agents.ProfileDetector;
import blastradius.models.Explanation;
import blastradius.models.PermissionRequest;
import blastradius.readers.ScreenDetector;
import blastradius.ui.Popup;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.sourceforge.tess4j.Tesseract;

/**
 * Diagnostic tool to verify screen capture, Tesseract OCR, card detection, and popup display live.
 */
public final class Diagnose {

    public static void main(String[] args) {
        System.out.println("=== blastradius live diagnostic ===");
        try {
            // 1. Test Popup directly
            System.out.println("Step 1: Testing Popup UI display...");
            PermissionRequest testReq = PermissionRequest.of("antigravity",
                    blastradius.models.RequestType.SHELL_COMMAND, "git status", "test raw");
            Explanation testEx = new Explanation("test-rule", blastradius.models.Severity.SAFE,
                    "Checking git repository status (SAFE).",
                    "This test verifies the popup window displays correctly on your screen.",
                    List.of("files"));
            Popup.show(testReq, testEx, 10);
            System.out.println(">> POPUP SHOWN at top-right! Look at your top-right screen corner.");
            Thread.sleep(2000);

            // 2. Test Tesseract OCR initialization
            System.out.println("\nStep 2: Testing Tesseract OCR engine...");
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath("C:\\Program Files\\Tesseract-OCR\\tessdata");
            tesseract.setLanguage("eng");
            System.out.println(">> Tesseract initialized OK.");

            // 3. Test Screen Capture
            System.out.println("\nStep 3: Capturing screen...");
            Robot robot = new Robot();
            Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage img = robot.createScreenCapture(screen);
            System.out.println(">> Captured screen size: " + img.getWidth() + "x" + img.getHeight());

            // 4. Test OCR on captured screen
            System.out.println("\nStep 4: Running OCR on screen capture with speed optimizations...");
            // Convert to grayscale for 2x faster OCR
            BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            java.awt.Graphics2D g2 = gray.createGraphics();
            g2.drawImage(img, 0, 0, null);
            g2.dispose();

            // Set PageSegMode 11 (Sparse text) which is significantly faster for UI screens
            tesseract.setPageSegMode(11);
            long t0 = System.currentTimeMillis();
            String ocrText = tesseract.doOCR(gray);
            long elapsed = System.currentTimeMillis() - t0;
            System.out.println(">> Fast OCR finished in " + elapsed + " ms. Characters read: " + ocrText.length());

            // 5. Test ScreenDetector against the OCR text
            System.out.println("\nStep 5: Testing detection against current screen content...");
            Path project = Path.of("").toAbsolutePath();
            Parser parser = new Parser(project);
            List<ProfileDetector> pds = new ArrayList<>();
            for (AgentProfile p : AgentProfile.loadAll()) {
                pds.add(new ProfileDetector(p, parser));
            }
            ScreenDetector detector = ScreenDetector.of(pds, project);

            Optional<PermissionRequest> detected = detector.detect(ocrText);
            if (detected.isPresent()) {
                System.out.println(">> DETECTED PROMPT! " + detected.get());
                Config cfg = Config.load(project);
                Explainer expl = Explainer.load(cfg);
                Explanation ex = expl.explain(detected.get());
                Popup.show(detected.get(), ex, 15);
                System.out.println(">> Displayed live popup for detected prompt!");
            } else {
                System.out.println(">> No permission prompt currently visible on screen.");
                System.out.println(">> Sample OCR text excerpt (first 500 chars):");
                System.out.println(ocrText.substring(0, Math.min(500, ocrText.length())));
            }

            System.out.println("\n=== Diagnostic complete ===");
        } catch (Throwable t) {
            System.err.println("DIAGNOSTIC FAILED with error:");
            t.printStackTrace();
        }
    }
}
