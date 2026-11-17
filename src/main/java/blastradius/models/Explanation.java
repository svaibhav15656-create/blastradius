package blastradius.models;

import java.util.List;
import java.util.Objects;

/**
 * Plain-English explanation of a request.
 *
 * @param ruleId   id of the rule that matched ("none" when nothing matched)
 * @param severity CRITICAL / WARNING / SAFE
 * @param what     1-3 sentences: what the request does
 * @param why      1-3 sentences: why it matters
 * @param affects  tags such as files, network, secrets, system, history
 */
public record Explanation(
        String ruleId,
        Severity severity,
        String what,
        String why,
        List<String> affects) {

    public Explanation {
        ruleId = Objects.requireNonNullElse(ruleId, "none");
        severity = Objects.requireNonNullElse(severity, Severity.SAFE);
        what = Objects.requireNonNullElse(what, "");
        why = Objects.requireNonNullElse(why, "");
        affects = affects == null ? List.of() : List.copyOf(affects);
    }

    /** Neutral green note for requests that match no rule. */
    public static Explanation noKnownRisks() {
        return new Explanation("none", Severity.SAFE,
                "No known risks found.",
                "This request did not match any known risky pattern.",
                List.of());
    }
}
