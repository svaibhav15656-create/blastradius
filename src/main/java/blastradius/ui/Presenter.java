package blastradius.ui;

import blastradius.Explainer;
import blastradius.models.Explanation;
import blastradius.models.PermissionRequest;
import blastradius.readers.Reader;

/**
 * Reader listener that explains each request and shows it: popup first, then native
 * notification, then one terminal line. Purely informational; any error is swallowed.
 */
public final class Presenter implements Reader.Listener {

    private final Explainer explainer;
    private final int timeoutSeconds;

    public Presenter(Explainer explainer, int timeoutSeconds) {
        this.explainer = explainer;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void onRequest(PermissionRequest request) {
        Reader.safe(() -> {
            Explanation ex = explainer.explain(request);
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
