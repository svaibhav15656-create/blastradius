package blastradius.ui;

import blastradius.models.Explanation;
import blastradius.models.PermissionRequest;
import blastradius.models.Severity;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Small, read-only, always-on-top corner window that explains one request.
 *
 * <p>It never takes keyboard focus (the window is not focusable), so keystrokes keep going to
 * the agent. There are no action buttons; the only control is a small close mark. It closes
 * itself when {@link #close()} is called (prompt gone) or after the timeout.
 *
 * <p>Untested on real desktops: on macOS the JVM is started as a background "UI element"
 * app (see Cli) so it should not steal activation; some Linux window managers may still
 * focus new windows.
 */
public final class Popup {

    private static final int WIDTH_PX = 460;
    private static JWindow window;   // EDT only
    private static Timer timer;      // EDT only

    private Popup() {}

    /** False on headless machines (no display). */
    public static boolean isAvailable() {
        return !GraphicsEnvironment.isHeadless();
    }

    /**
     * Shows (or replaces) the popup. Throws if it could not be shown so the caller can fall
     * back to a notification.
     *
     * @param timeoutSeconds auto-close delay; 0 or less means never
     */
    public static void show(PermissionRequest req, Explanation ex, int timeoutSeconds) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            build(req, ex, timeoutSeconds);
        } else {
            SwingUtilities.invokeAndWait(() -> build(req, ex, timeoutSeconds));
        }
    }

    /** Closes the popup if one is showing. Safe from any thread. */
    public static void close() {
        if (isAvailable()) {
            SwingUtilities.invokeLater(Popup::dispose);
        }
    }

    // ---- EDT only ---------------------------------------------------------------------------

    private static void build(PermissionRequest req, Explanation ex, int timeoutSeconds) {
        dispose();
        Color head = headerColor(ex.severity());
        Color headText = ex.severity() == Severity.WARNING ? new Color(0x33, 0x2B, 0x00) : Color.WHITE;

        JWindow w = new JWindow();
        w.setType(Window.Type.POPUP);
        w.setFocusableWindowState(false); // never take keyboard focus
        w.setAutoRequestFocus(false);
        w.setAlwaysOnTop(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createLineBorder(head.darker(), 2));

        // header: colour = severity (larger, higher contrast for recordings)
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(head);
        header.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 12));
        JLabel title = new JLabel(label(ex.severity()) + "  \u00B7  " + typeLabel(req));
        title.setForeground(headText);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        JLabel x = new JLabel("\u2715");
        x.setForeground(headText);
        x.setFont(x.getFont().deriveFont(Font.BOLD, 15f));
        x.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        x.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dispose();
            }
        });
        header.add(title, BorderLayout.WEST);
        header.add(x, BorderLayout.EAST);

        // body (generous padding, larger readable fonts for video recordings)
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        body.setBackground(new Color(0xFA, 0xFA, 0xFA));
        if (!req.target().isBlank()) {
            body.add(section("Target", req.target(), true));
        }
        body.add(section("What it does", ex.what(), false));
        body.add(section("Why it matters", ex.why(), false));
        if (!ex.affects().isEmpty()) {
            body.add(section("Affects", String.join("  \u00B7  ", ex.affects()), false));
        }

        // privacy assurance footer
        JLabel privacyFooter = new JLabel("<html><span style='font-size:10px; color:#888888;'>&#128274; 100% offline &middot; memory-only &middot; zero network calls</span></html>");
        privacyFooter.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        privacyFooter.setAlignmentX(0f);
        body.add(privacyFooter);

        root.add(header, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        w.setContentPane(root);
        w.pack();

        Rectangle area = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        // Top-right corner so it never covers bottom-right IDE approval cards or terminal prompts.
        w.setLocation(area.x + area.width - w.getWidth() - 28, area.y + 36);
        w.setVisible(true);
        window = w;

        if (timeoutSeconds > 0) {
            timer = new Timer(timeoutSeconds * 1000, e -> dispose());
            timer.setRepeats(false);
            timer.start();
        }
    }

    private static void dispose() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        if (window != null) {
            window.dispose();
            window = null;
        }
    }

    private static JLabel section(String heading, String text, boolean mono) {
        String shown = text.length() > 300 ? text.substring(0, 300) + "\u2026" : text;
        String style = mono 
                ? "font-family:Consolas, 'Courier New', monospace; font-size:12px; background:#EEEEEE; padding:4px 6px;" 
                : "font-family:sans-serif; font-size:13px; color:#222222; line-height:1.3;";
        JLabel l = new JLabel("<html><body style='width:" + (WIDTH_PX - 44) + "px;'>"
                + "<div style='font-family:sans-serif; font-size:11px; font-weight:bold; color:#666666; margin-bottom:2px;'>" 
                + escape(heading).toUpperCase() + "</div>"
                + "<div style='" + style + "'>" + escape(shown) + "</div></body></html>");
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        l.setAlignmentX(0f);
        return l;
    }

    private static Color headerColor(Severity s) {
        return switch (s) {
            case CRITICAL -> new Color(0xC6, 0x28, 0x28); // red
            case WARNING -> new Color(0xF9, 0xA8, 0x25);  // yellow
            case SAFE -> new Color(0x2E, 0x7D, 0x32);     // green
        };
    }

    private static String label(Severity s) {
        return s.name();
    }

    private static String typeLabel(PermissionRequest r) {
        return r.type().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
