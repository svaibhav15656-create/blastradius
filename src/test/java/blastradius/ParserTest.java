package blastradius;

import static org.junit.jupiter.api.Assertions.assertEquals;

import blastradius.models.PermissionRequest;
import blastradius.models.RequestType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ParserTest {

    private final Parser parser = new Parser(Path.of("/work/project"));

    private RequestType type(RequestType hint, String target) {
        return parser.fromPrompt("test", hint, target, "").type();
    }

    @Test
    void plainShellCommandStaysShell() {
        assertEquals(RequestType.SHELL_COMMAND, type(RequestType.SHELL_COMMAND, "npm test"));
    }

    @Test
    void sudoBecomesPrivilegeEscalation() {
        assertEquals(RequestType.PRIVILEGE_ESCALATION, type(RequestType.SHELL_COMMAND, "sudo apt install jq"));
        assertEquals(RequestType.PRIVILEGE_ESCALATION, type(RequestType.SHELL_COMMAND, "FOO=1 sudo ls"));
    }

    @Test
    void wordContainingSudoIsNotEscalation() {
        assertEquals(RequestType.SHELL_COMMAND, type(RequestType.SHELL_COMMAND, "pseudo-tool --run"));
    }

    @Test
    void bareUrlWithUnknownHintBecomesNetworkAccess() {
        assertEquals(RequestType.NETWORK_ACCESS, type(RequestType.UNKNOWN, "https://example.com/x"));
        assertEquals(RequestType.UNKNOWN, type(RequestType.UNKNOWN, "something else"));
    }

    @Test
    void filePathsInsideProjectStayFileOps() {
        assertEquals(RequestType.FILE_EDIT, type(RequestType.FILE_EDIT, "src/a.py"));
        assertEquals(RequestType.FILE_CREATE, type(RequestType.FILE_CREATE, "/work/project/src/new.py"));
    }

    @Test
    void filePathsOutsideProjectBecomeOutsideAccess() {
        assertEquals(RequestType.OUTSIDE_PROJECT_ACCESS, type(RequestType.FILE_EDIT, "/etc/hosts"));
        assertEquals(RequestType.OUTSIDE_PROJECT_ACCESS, type(RequestType.FILE_DELETE, "../other/a.txt"));
        assertEquals(RequestType.OUTSIDE_PROJECT_ACCESS, type(RequestType.FILE_EDIT, "~/.zshrc"));
    }

    @Test
    void emptyTargetIsNeverOutside() {
        assertEquals(RequestType.FILE_EDIT, type(RequestType.FILE_EDIT, ""));
    }

    @Test
    void unknownProjectDirMeansNoOutsideDetection() {
        Parser p = new Parser(null);
        assertEquals(RequestType.FILE_EDIT, p.fromPrompt("t", RequestType.FILE_EDIT, "/etc/hosts", "").type());
    }

    @Test
    void borderCharactersAreStripped() {
        PermissionRequest r = parser.fromPrompt("test", RequestType.SHELL_COMMAND, "\u2502 ls -la \u2502", "raw");
        assertEquals("ls -la", r.target());
        assertEquals("raw", r.rawText());
    }

    @Test
    void freeTextDefaultsToShellAndUrlToNetwork() {
        assertEquals(RequestType.SHELL_COMMAND, parser.parseFreeText("x", "ls -la").type());
        assertEquals(RequestType.NETWORK_ACCESS, parser.parseFreeText("x", "https://example.com").type());
    }

    @Test
    void freeTextPrefixesPickTheType() {
        assertEquals(RequestType.FILE_EDIT, parser.parseFreeText("x", "edit:src/a.py").type());
        assertEquals(RequestType.FILE_CREATE, parser.parseFreeText("x", "create:src/b.py").type());
        assertEquals(RequestType.FILE_DELETE, parser.parseFreeText("x", "delete:src/c.py").type());
        assertEquals(RequestType.TOOL_CALL, parser.parseFreeText("x", "tool:github.create_issue").type());
        assertEquals(RequestType.NETWORK_ACCESS, parser.parseFreeText("x", "net:example.com").type());
        assertEquals("src/a.py", parser.parseFreeText("x", "edit:src/a.py").target());
    }

    @Test
    void freeTextWithOutsidePathIsOutsideAccess() {
        assertEquals(RequestType.OUTSIDE_PROJECT_ACCESS, parser.parseFreeText("x", "edit:/etc/hosts").type());
    }
}
