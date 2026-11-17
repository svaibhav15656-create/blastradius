package blastradius.ui;

import blastradius.Explainer;
import blastradius.models.Explanation;
import blastradius.models.PermissionRequest;
import blastradius.models.Severity;
import blastradius.readers.Reader;

/**
 * Reader listener that explains each request and shows it: popup first, then native
 * notification, then one terminal line. Purely informational; any error is swallowed.
 *
 * <p>When {@code showSafe} is false (the default), SAFE-classified requests produce no
 * popup, which prevents alert fatigue from read-only commands like {@code git status}.
 */
public final class Presenter implements Reader.Listener {

    private final Explainer explainer;
    private final int timeoutSeconds;
    private final boolean showSafe;

    public Presenter(Explainer explainer, int timeoutSeconds, boolean showSafe) {
        this.explainer      = explainer;
        this.timeoutSeconds = timeoutSeconds;
        this.showSafe       = showSafe;
    }

    /** Backwards-compatible constructor: showSafe defaults to false. */
    public Presenter(Explainer explainer, int timeoutSeconds) {
        this(explainer, timeoutSeconds, false);
    }

    @Override
    public void onRequest(PermissionRequest request) {
        Reader.safe(() -> {
            Explanation ex = explainer.explain(request);

            // Suppress green (SAFE) popups unless the user opted into show_safe: true.
            if (!showSafe && ex.severity() == Severity.SAFE) {
                return;
            }

            if (Popup.isAvailable()) {
                try {
                    Popup.show(request, ex, timeoutSeconds);
                    return;
                } catch (Throwable popupFailed) {
                    // fall through to the notification
                }
            }
            Fallback.notify(request, ex);
        });
    }

    @Override
    public void onPromptGone() {
        Reader.safe(Popup::close);
    }
}
