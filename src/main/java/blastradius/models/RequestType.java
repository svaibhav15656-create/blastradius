package blastradius.models;

/** What kind of action the agent is asking permission for. */
public enum RequestType {
    SHELL_COMMAND,
    FILE_EDIT,
    FILE_CREATE,
    FILE_DELETE,
    OUTSIDE_PROJECT_ACCESS,
    NETWORK_ACCESS,
    PRIVILEGE_ESCALATION,
    TOOL_CALL,
    UNKNOWN
}
