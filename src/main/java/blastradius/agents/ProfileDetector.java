package blastradius.agents;

import blastradius.Parser;
import blastradius.agents.AgentProfile.PromptSpec;
import blastradius.models.PermissionRequest;
import blastradius.readers.Reader;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects an {@link AgentProfile} to the readers: finds the most recent approval prompt in
 * a block of text and extracts its target. Pure text matching; no side effects.
 */
public final class ProfileDetector implements Reader.Detector {

    /** How far back to look for a target that is printed before the prompt title. */
    private static final int CONTEXT_CHARS = 400;

    private final AgentProfile profile;
    private final Parser parser;

    public ProfileDetector(AgentProfile profile, Parser parser) {
        this.profile = profile;
        this.parser = parser;
    }

    @Override
    public Optional<PermissionRequest> detect(String text) {
        PromptSpec best = null;
        int bestPos = -1;
        for (PromptSpec spec : profile.prompts()) {
            Matcher m = spec.detect().matcher(text);
            int pos = -1;
            while (m.find()) {
                pos = m.start(); // keep the LAST match: the freshest prompt on screen
            }
            if (pos > bestPos) {
                bestPos = pos;
                best = spec;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        String slice = text.substring(bestPos);
        String before = text.substring(Math.max(0, bestPos - CONTEXT_CHARS));
        String target = "";
        for (Pattern p : best.targets()) {
            Matcher m = p.matcher(slice);
            if (m.find()) {
                target = m.group("target").replaceAll("[\\r\\n]+", " ").replaceAll("[ \\t]+", " ").strip();
                break;
            }
            if (best.targetBefore()) {
                // the command was printed above the prompt line: take the closest one
                Matcher b = p.matcher(before);
                String last = null;
                while (b.find()) {
                    last = b.group("target");
                }
                if (last != null) {
                    target = last.replaceAll("[\\r\\n]+", " ").replaceAll("[ \\t]+", " ").strip();
                    break;
                }
            }
        }
        return Optional.of(parser.fromPrompt(profile.name(), best.type(), target, slice));
    }

    @Override
    public boolean promptGone(String text) {
        for (Pattern p : profile.promptGone()) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
