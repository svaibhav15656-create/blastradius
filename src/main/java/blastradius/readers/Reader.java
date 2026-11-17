package blastradius.readers;

import blastradius.models.PermissionRequest;
import java.util.Optional;

/**
 * Source of {@link PermissionRequest} events (PTY, log file, screen...).
 *
 * <p>Hard rule: implementations are strictly passive. They must never block, approve,
 * deny or alter anything the agent does, and must swallow their own internal errors.
 */
public interface Reader extends AutoCloseable {

    /** Receives events. Calls are wrapped so a listener bug can never reach the agent. */
    interface Listener {
        /** A permission prompt appeared. */
        void onRequest(PermissionRequest request);

        /** The prompt that was last reported is gone. */
        default void onPromptGone() {}
    }

    /** Turns recent text into events; implemented by agent profiles later. */
    interface Detector {
        /** Return a request if {@code recentText} contains an approval prompt. */
        Optional<PermissionRequest> detect(String recentText);

        /** True if {@code recentText} shows the prompt has been answered or dismissed. */
        boolean promptGone(String recentText);
    }

    /** Short reader name, e.g. "pty". */
    String name();

    /** Begin reading. Returns quickly; work happens on background threads. */
    void start(Listener listener) throws Exception;

    /** Stop reading and release resources. Idempotent. */
    void stop();

    /** True for readers that may be unreliable (screen reader). */
    default boolean isExperimental() {
        return false;
    }

    @Override
    default void close() {
        stop();
    }

    /** Like Runnable, but allowed to throw. */
    @FunctionalInterface
    interface Task {
        void run() throws Exception;
    }

    /** Run {@code r}, ignoring any error: blastradius must fail silently. */
    static void safe(Task r) {
        try {
            r.run();
        } catch (Throwable ignored) {
            // intentional: never disturb the agent
        }
    }
}
