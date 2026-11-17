package blastradius;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blastradius.agents.AgentProfile;
import blastradius.agents.ProfileDetector;
import blastradius.models.PermissionRequest;
import blastradius.models.RequestType;
import blastradius.readers.LogReader;
import blastradius.readers.PtyReader;
import blastradius.readers.Reader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fixtures in src/test/resources/fixtures are hand-built from the prompt wording that could be
 * verified (see README). They are NOT captured from a live agent session.
 */
class ReadersTest {

    private static final Parser PARSER = new Parser(Path.of("/work/project"));

    private static String fixture(String name) throws IOException {
        try (InputStream in = ReadersTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IOException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ProfileDetector detector(String agent) throws IOException {
        return new ProfileDetector(AgentProfile.load(agent), PARSER);
    }

    // ---- profiles ----------------------------------------------------------------------------

    @Test
    void builtInProfilesLoadWithExpectedStatus() throws Exception {
        assertEquals(AgentProfile.Status.PARTIAL, AgentProfile.load("codex").status());
        assertEquals(AgentProfile.Status.PARTIAL, AgentProfile.load("antigravity").status());
        assertEquals(AgentProfile.Status.UNVERIFIED, AgentProfile.load("generic").status());
        assertTrue(AgentProfile.loadAll().size() >= 3);
    }

    @Test
    void unknownOrUnsafeProfileNamesAreRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> AgentProfile.load("nope"));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> AgentProfile.load("../etc/passwd"));
    }

    @Test
    void agentIsGuessedFromTheExecutable() {
        assertEquals("codex", AgentProfile.guessFor("/usr/local/bin/codex"));
        assertEquals("antigravity", AgentProfile.guessFor("antigravity-cli"));
        assertEquals("generic", AgentProfile.guessFor("my-agent"));
    }

    // ---- prompt detection per agent ----------------------------------------------------------

    @Test
    void codexCommandPrompt() throws Exception {
        PermissionRequest r = detector("codex").detect(fixture("codex_command.txt")).orElseThrow();
        assertEquals(RequestType.SHELL_COMMAND, r.type());
        assertEquals("curl -s https://httpbin.org/ip", r.target());
        assertEquals("codex", r.agent());
    }

    @Test
    void codexEditsPrompt() throws Exception {
        PermissionRequest r = detector("codex").detect(fixture("codex_edits.txt")).orElseThrow();
        assertEquals(RequestType.FILE_EDIT, r.type());
    }

    @Test
    void codexNetworkPrompt() throws Exception {
        PermissionRequest r = detector("codex").detect(fixture("codex_network.txt")).orElseThrow();
        assertEquals(RequestType.NETWORK_ACCESS, r.type());
        assertEquals("example.com", r.target());
    }

    @Test
    void codexPromptGoneText() throws Exception {
        ProfileDetector d = detector("codex");
        assertTrue(d.promptGone(fixture("codex_gone.txt")));
        assertFalse(d.promptGone(fixture("plain_output.txt")));
    }

    @Test
    void antigravityProceedPromptReadsTheCommandPrefix() throws Exception {
        PermissionRequest r = detector("antigravity").detect(fixture("antigravity_proceed.txt")).orElseThrow();
        assertEquals(RequestType.SHELL_COMMAND, r.type());
        assertEquals("npm test", r.target());
    }

    @Test
    void antigravitySandboxBypassIsPrivilegeEscalation() throws Exception {
        PermissionRequest r = detector("antigravity").detect(fixture("antigravity_sandbox_bypass.txt")).orElseThrow();
        assertEquals(RequestType.PRIVILEGE_ESCALATION, r.type());
    }

    @Test
    void genericYesNoPrompt() throws Exception {
        PermissionRequest r = detector("generic").detect(fixture("generic_yn.txt")).orElseThrow();
        assertEquals("ls -la", r.target());
    }

    @Test
    void ordinaryOutputIsNotAPrompt() throws Exception {
        for (String agent : List.of("codex", "antigravity", "generic")) {
            assertTrue(detector(agent).detect(fixture("plain_output.txt")).isEmpty(), agent);
        }
    }

    @Test
    void theMostRecentPromptWins() throws Exception {
        String twoPrompts = fixture("codex_command.txt") + "\nsome output\n"
                + "Would you like to run the following command?\n\n  $ rm -rf build\n";
        PermissionRequest r = detector("codex").detect(twoPrompts).orElseThrow();
        assertEquals("rm -rf build", r.target());
    }

    // ---- terminal text cleanup ---------------------------------------------------------------

    @Test
    void plainTextStripsColoursAndTurnsCursorJumpsIntoNewlines() {
        assertEquals("red", PtyReader.plain("\u001B[31mred\u001B[0m"));
        assertEquals("a\nb", PtyReader.plain("a\u001B[2;1Hb"));
        assertEquals("a b", PtyReader.plain("a\u001B[1Cb"));
    }

    // ---- log reader --------------------------------------------------------------------------

    @Test
    void logReaderIgnoresOldContentAndReportsNewPrompts(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("session.log");
        Files.writeString(log, "Old prompt? [y/N]\n", StandardCharsets.UTF_8);

        CountDownLatch seen = new CountDownLatch(1);
        AtomicReference<PermissionRequest> got = new AtomicReference<>();
        try (LogReader reader = new LogReader(List.of(log), detector("generic"), 50)) {
            reader.start(new Reader.Listener() {
                @Override
                public void onRequest(PermissionRequest request) {
                    got.set(request);
                    seen.countDown();
                }
            });
            Thread.sleep(500); // first poll records the end of file
            assertEquals(1, seen.getCount(), "old content must not be reported");

            Files.writeString(log, "Run: git status\nApply? [y/N]\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            assertTrue(seen.await(5, TimeUnit.SECONDS), "new prompt was not reported");
        }
        assertEquals("git status", got.get().target());
    }

    @Test
    void logReaderSurvivesMissingFile(@TempDir Path dir) throws Exception {
        try (LogReader reader = new LogReader(List.of(dir.resolve("not-there.log")), detector("generic"), 50)) {
            reader.start(new Reader.Listener() {
                @Override
                public void onRequest(PermissionRequest request) {}
            });
            Thread.sleep(200); // must not throw or crash
        }
    }

    // ---- PTY wrapper (Unix only) -------------------------------------------------------------

    private static final Reader.Detector NO_DETECTION = new Reader.Detector() {
        @Override
        public Optional<PermissionRequest> detect(String recentText) {
            return Optional.empty();
        }

        @Override
        public boolean promptGone(String recentText) {
            return false;
        }
    };

    private static final Reader.Listener IGNORE = new Reader.Listener() {
        @Override
        public void onRequest(PermissionRequest request) {}
    };

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void ptyWrapperPassesOutputThroughAndKeepsTheExitCode() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int code;
        try (PtyReader reader = new PtyReader(List.of("sh", "-c", "printf 'hello\\n'; exit 7"), NO_DETECTION)) {
            reader.start(IGNORE);
            code = reader.awaitExit();
        } finally {
            System.setOut(original);
        }
        assertEquals(7, code, "exit code must be the agent's own");
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("hello"), "output must pass through");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void ptyWrapperReportsAPromptWithoutChangingTheOutput() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        CountDownLatch seen = new CountDownLatch(1);
        AtomicReference<PermissionRequest> got = new AtomicReference<>();
        int code;
        try (PtyReader reader = new PtyReader(
                List.of("sh", "-c", "printf 'Run: ls\\nApply? [y/N] '; sleep 1; exit 0"), detector("generic"))) {
            reader.start(new Reader.Listener() {
                @Override
                public void onRequest(PermissionRequest request) {
                    got.set(request);
                    seen.countDown();
                }
            });
            assertTrue(seen.await(5, TimeUnit.SECONDS), "prompt was not reported");
            code = reader.awaitExit();
        } finally {
            System.setOut(original);
        }
        assertEquals(0, code);
        assertEquals("ls", got.get().target());
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("Apply? [y/N]"));
    }
}
