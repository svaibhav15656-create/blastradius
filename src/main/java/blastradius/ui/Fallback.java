package blastradius.ui;

import blastradius.models.Explanation;
import blastradius.models.PermissionRequest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Used when the popup cannot be shown: a native OS notification (osascript / notify-send /
 * Windows toast), and if that fails too, a single terminal line. Never throws.
 *
 * <p>The Windows toast script and the notify-send / osascript calls are untested here.
 */
public final class Fallback {

    private Fallback() {}

    public static void notify(PermissionRequest req, Explanation ex) {
        try {
            String title = "blastradius: " + ex.severity() + " - " + req.type().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            String msg = (req.target().isBlank() ? "" : trim(req.target(), 80) + " - ") + ex.what();
            if (!nativeNotification(clean(title), clean(trim(msg, 220)))) {
                terminalLine("[" + title + "] " + clean(trim(msg, 200)));
            }
        } catch (Throwable ignored) {
            // fail silently
        }
    }

    /** Exposed for tests: the one-line form. */
    public static String line(PermissionRequest req, Explanation ex) {
        return "[blastradius " + ex.severity() + "] " + req.type().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + (req.target().isBlank() ? "" : ": " + trim(clean(req.target()), 80)) + " - " + ex.what();
    }

    private static boolean nativeNotification(String title, String msg) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd;
        if (os.contains("mac")) {
            cmd = List.of("osascript", "-e",
                    "display notification \"" + esc(msg) + "\" with title \"" + esc(title) + "\"");
        } else if (os.contains("win")) {
            String script = "$ErrorActionPreference='Stop';"
                    + "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null;"
                    + "$t=[Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02);"
                    + "$n=$t.GetElementsByTagName('text');"
                    + "$n.Item(0).AppendChild($t.CreateTextNode('" + ps(title) + "')) | Out-Null;"
                    + "$n.Item(1).AppendChild($t.CreateTextNode('" + ps(msg) + "')) | Out-Null;"
                    + "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('blastradius').Show([Windows.UI.Notifications.ToastNotification]::new($t))";
            cmd = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        } else {
            cmd = List.of("notify-send", "--app-name=blastradius", title, msg);
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** One line to stderr. \r\n so it also looks right while the terminal is in raw mode. */
    private static void terminalLine(String line) {
        System.err.print("\r\n" + line + "\r\n");
        System.err.flush();
    }

    private static String clean(String s) {
        return s.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String ps(String s) {
        return s.replace("'", "''");
    }
}
