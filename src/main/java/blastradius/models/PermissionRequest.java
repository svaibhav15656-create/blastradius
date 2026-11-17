package blastradius.models;

import java.time.Instant;
import java.util.Objects;

/**
 * A single permission request detected from an agent.
 *
 * @param agent   agent profile name, e.g. "codex"
 * @param type    classified request type
 * @param target  command string, file path, URL or tool name ("" if unknown)
 * @param rawText the text the request was detected from
 * @param seenAt  when it was detected
 */
public record PermissionRequest(
        String agent,
        RequestType type,
        String target,
        String rawText,
        Instant seenAt) {

    public PermissionRequest {
        agent = Objects.requireNonNullElse(agent, "unknown");
        type = Objects.requireNonNullElse(type, RequestType.UNKNOWN);
        target = Objects.requireNonNullElse(target, "");
        rawText = Objects.requireNonNullElse(rawText, "");
        seenAt = Objects.requireNonNullElseGet(seenAt, Instant::now);
    }

    /** Convenience factory using the current time. */
    public static PermissionRequest of(String agent, RequestType type, String target, String rawText) {
        return new PermissionRequest(agent, type, target, rawText, Instant.now());
    }
}
