package blastradius;

import blastradius.models.Explanation;
import blastradius.models.PermissionRequest;
import blastradius.models.RequestType;
import blastradius.models.Severity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Offline, rule-based explainer: {@link PermissionRequest} to {@link Explanation}.
 * Rules come from {@code rules/default.yaml} plus the user's {@code .blastradius.yaml}.
 * The most severe matching rule supplies the wording; ties go to the earliest rule.
 */
public final class Explainer {

    /** One explanation rule. A null pattern matches every request of the applicable types. */
    public record Rule(
            String id,
            Set<RequestType> appliesTo,
            Pattern pattern,
            boolean matchRaw,
            Severity severity,
            String what,
            String why,
            List<String> affects,
            Map<String, Object> examples) {

        boolean matches(PermissionRequest r) {
            if (!appliesTo.contains(r.type())) {
                return false;
            }
            if (pattern == null) {
                return true;
            }
            if (!r.target().isBlank() && pattern.matcher(r.target()).find()) {
                return true;
            }
            return matchRaw && pattern.matcher(r.rawText()).find();
        }
    }

    private static final String DEFAULT_RULES = "/blastradius/rules/default.yaml";

    private final List<Rule> rules;
    private final Set<String> disabled;
    private final List<String> loadErrors;

    private Explainer(List<Rule> rules, Set<String> disabled, List<String> loadErrors) {
        this.rules = List.copyOf(rules);
        this.disabled = Set.copyOf(disabled);
        this.loadErrors = List.copyOf(loadErrors);
    }

    /** Loads built-in rules and applies the user's overrides. */
    public static Explainer load(Config config) throws IOException {
        List<String> errors = new ArrayList<>();
        List<Rule> all = new ArrayList<>();
        for (Map<String, Object> m : config.extraRules()) {
            addRule(all, m, config, errors);
        }
        for (Map<String, Object> m : Yamls.mapList(Yamls.readResource(DEFAULT_RULES).get("rules"))) {
            addRule(all, m, config, errors);
        }
        return new Explainer(all, config.disabledRules(), errors);
    }

    public List<Rule> rules() {
        return rules;
    }

    /** Problems found while loading rules (bad regex, unknown type...). Empty when all is well. */
    public List<String> loadErrors() {
        return loadErrors;
    }

    public Explanation explain(PermissionRequest request) {
        Rule top = null;
        List<Rule> hits = new ArrayList<>();
        for (Rule r : rules) {
            if (!disabled.contains(r.id()) && r.matches(request)) {
                hits.add(r);
                if (top == null || r.severity().ordinal() < top.severity().ordinal()) {
                    top = r;
                }
            }
        }
        if (top == null) {
            return request.target().isBlank() && request.type() != RequestType.UNKNOWN
                    ? new Explanation("none", Severity.SAFE, "No known risks found.",
                            "blastradius could not read the details of this request, so check what the agent is asking.",
                            List.of())
                    : Explanation.noKnownRisks();
        }
        Set<String> affects = new LinkedHashSet<>();
        for (Rule r : hits) {
            if (r.severity() == top.severity()) {
                affects.addAll(r.affects());
            }
        }
        return new Explanation(top.id(), top.severity(), top.what(), top.why(), new ArrayList<>(affects));
    }

    // ---- loading ---------------------------------------------------------------------------

    private static void addRule(List<Rule> out, Map<String, Object> m, Config cfg, List<String> errors) {
        String id = Yamls.str(m.get("id"), "?");
        try {
            Severity sev = cfg.severityOverrides().getOrDefault(id,
                    Severity.valueOf(Yamls.str(m.get("severity"), "warning").toUpperCase(Locale.ROOT)));
            String p = m.get("pattern") == null ? null : Yamls.str(m.get("pattern"), null);
            Pattern pattern = p == null ? null : Pattern.compile(p, Pattern.CASE_INSENSITIVE);
            Set<RequestType> types = parseTypes(Yamls.strList(m.get("applies_to")));
            if (types.isEmpty()) {
                throw new IllegalArgumentException("applies_to is empty");
            }
            out.add(new Rule(id, types, pattern, "any".equals(Yamls.str(m.get("scope"), "")), sev,
                    Yamls.str(m.get("what"), ""), Yamls.str(m.get("why"), ""),
                    Yamls.strList(m.get("affects")), Yamls.map(m.get("examples"))));
        } catch (RuntimeException e) {
            errors.add("rule " + id + ": " + e.getMessage());
        }
    }

    static Set<RequestType> parseTypes(List<String> names) {
        Set<RequestType> out = EnumSet.noneOf(RequestType.class);
        for (String n : names) {
            switch (n.toLowerCase(Locale.ROOT)) {
                case "any" -> out.addAll(EnumSet.allOf(RequestType.class));
                case "shell" -> out.addAll(List.of(RequestType.SHELL_COMMAND, RequestType.PRIVILEGE_ESCALATION));
                case "file" -> out.addAll(List.of(RequestType.FILE_EDIT, RequestType.FILE_CREATE,
                        RequestType.FILE_DELETE, RequestType.OUTSIDE_PROJECT_ACCESS));
                default -> out.add(RequestType.valueOf(n.toUpperCase(Locale.ROOT)));
            }
        }
        return out;
    }
}
