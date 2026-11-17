# blastradius

Deterministic, 100% offline, read-only security explainer for AI coding agent permission requests.

When an AI agent requests permission to execute a shell command or mutate project files, blastradius captures the prompt, parses the command, and displays a non-intrusive floating popup color-coded **RED (CRITICAL)**, **YELLOW (WARNING)**, or **GREEN (SAFE)**, detailing what the command does, why it is dangerous or safe, and what system resources it touches.

Verified live on **Google Antigravity**. Support for Cursor, OpenAI Codex, Windsurf and Claude Code is pattern-based and experimental (see [Supported AI Agents](#supported-ai-agents)).

**Read-Only Non-Interference Guarantee:** blastradius never blocks, intercepts, auto-approves, or auto-denies commands. It never simulates clicks or keyboard events. You retain 100% control inside your AI agent's native interface.

---

## Operational Modes: Live Screen vs. Silent Background Daemon

blastradius provides two complementary operational modes: an interactive live screen watcher with continuous terminal telemetry, and a silent background daemon with zero console footprint.

```
+-----------------------------------------------------------------------------------------+
|                                  OPERATIONAL MODES                                      |
+-----------------------------------------------------------------------------------------+
|  1. LIVE INTERACTIVE SCREEN MODE               |  2. SILENT BACKGROUND DAEMON MODE       |
|     $ blastradius screen                       |     $ blastradius start | status | stop |
|                                                |                                         |
|  * Foreground terminal loop                    |  * Decoupled background javaw.exe       |
|  * Real-time scan & heartbeat telemetry        |  * Zero terminal window footprint       |
|  * Sub-second dirty-box OCR latency logs       |  * Controlled via daemon PID & locks    |
|  * Ideal for auditing, debugging & testing     |  * Ideal for daily silent development   |
+-----------------------------------------------------------------------------------------+
```

### 1. Interactive Live Mode (`blastradius screen`)

Runs in the active foreground terminal, providing continuous visual heartbeat confirmation and real-time detection telemetry:

```
blastradius screen
```

Terminal output telemetry:

- **Continuous heartbeat:** periodically reports watcher liveness, confirming local offline isolation (`Capturing screen... blastradius runs locally (100% offline, privacy preserved)`).
- **Sampled frame delta detection:** analyzes screen changes at 2 fps using pixel deltas. If no UI change occurs, CPU utilization is virtually zero.
- **Dirty-rectangle OCR profiling:** detects the exact bounding box of changed screen regions and runs local OCR exclusively on that card (~150 ms latency vs ~7500 ms for the full screen).
- **Prompt structural extraction:** prints the matched agent profile, the extracted target command, and the evaluated blast-radius security rule.

### 2. Silent Background Daemon Mode (`blastradius start | status | stop`)

Runs detached in the background without keeping an open terminal window:

```
# Start the background watcher daemon
blastradius start

# Inspect daemon status, uptime, PID, and memory footprint
blastradius status

# Gracefully terminate the background watcher daemon
blastradius stop
```

Daemon architecture and lifecycle:

- **Detached execution:** on Windows, spawns headless via `javaw.exe` (`-Dblastradius.daemon=true`), detaching from standard input/output with zero taskbar or terminal footprint.
- **Single-instance synchronization:** manages concurrency using an exclusive OS file lock at `~/.blastradius/daemon.lock` and tracks process identity in `~/.blastradius/daemon.pid`.
- **Health and status reporting:** `blastradius status` queries OS process tables via Java's `ProcessHandle` to verify process liveness, memory usage, and operational uptime.
- **Graceful termination:** `blastradius stop` signals the daemon via OS process channels, flushes pending locks, and cleanly releases all system resources.

---

## Installation Options

You can install blastradius using either of the two options below.

### Option 1: Direct Script Execution (if you downloaded `install.ps1` or cloned the repo)

For users who already have `install.ps1` in their current folder (for example after downloading the ZIP from GitHub or cloning the repository).

```powershell
# Step 1: Run the local installer script (adds blastradius to your global PATH)
.\install.ps1

# Step 2: Start the background watcher (or run 'blastradius screen' for live mode)
blastradius start
```

> **PowerShell path note:** running `.\install.ps1` adds blastradius permanently to your Windows PATH so you can run `blastradius screen` from any terminal. If you want to run it directly inside this folder before running `.\install.ps1`, PowerShell requires the `.\` prefix:
> `.\blastradius.ps1 screen` (or `.\blastradius.cmd screen`)

What these commands do:

- `.\install.ps1`:
  - Checks your system for Java 17+ and Tesseract OCR. If either is missing, it silently installs them in the background via Windows `winget`.
  - Deploys the standalone blastradius binary package into `~/.blastradius/bin`.
  - Registers blastradius in your Windows PATH and immediately updates your current terminal session (no restart needed).
- `blastradius start`: launches the detached background watcher daemon (`javaw.exe`) so AI agent permission prompts are monitored with zero console window footprint.

### Option 2: One-Liner Install (no downloading or cloning required)

For users who want a fully automated setup from any folder without opening a web browser or manually downloading files.

```powershell
# Step 1: Download and execute the installer in a single command
irm https://raw.githubusercontent.com/svaibhav15656-create/blastradius/main/install.ps1 | iex

# Step 2: Start the background watcher
blastradius start
```

What these commands do:

- `irm https://... | iex`:
  - `irm` (`Invoke-RestMethod`) downloads the latest `install.ps1` directly from GitHub into memory.
  - `| iex` (`Invoke-Expression`) executes the script in one step without opening a web browser.
  - Provisions Java 17 and Tesseract OCR in the background, configures your PATH, and prepares blastradius for use.
- `blastradius start`: spawns the background watcher daemon immediately so you can start coding with your AI agent right away.

---

## Automated Provisioning Architecture

```
[install.ps1]
     |
     |--> [1/4] Query JVM Version (>= 17)
     |           '-- [Missing] --> winget install EclipseAdoptium.Temurin.17.JRE --silent
     |
     |--> [2/4] Query Tesseract OCR Binary & Registry
     |           '-- [Missing] --> winget install UB-Mannheim.TesseractOCR --silent
     |
     |--> [3/4] Deploy Standalone Shaded Fat JAR to ~/.blastradius/bin
     |
     '--> [4/4] Configure System PATH (HKCU\Environment\PATH) + Hot-Patch Active Session
```

**JVM runtime resolution (OpenJDK 17 JRE):**

- Inspects `java -version` and `JAVA_HOME` for an active Java Virtual Machine >= 17.
- If missing or outdated, silently provisions Eclipse Adoptium Temurin OpenJDK 17 JRE via the Windows Package Manager (winget):

```
winget install EclipseAdoptium.Temurin.17.JRE --silent --accept-package-agreements --accept-source-agreements
```

**OCR engine resolution (Tesseract OCR):**

- Probes default installation paths (`C:\Program Files\Tesseract-OCR`) and the system PATH for the `tesseract` binary.
- If unresolved, automatically installs UB-Mannheim Tesseract OCR in unattended mode:

```
winget install UB-Mannheim.TesseractOCR --silent --accept-package-agreements --accept-source-agreements
```

**Artifact deployment and launchers:**

- Copies the pre-built shaded fat JAR (`blastradius-0.1.0-SNAPSHOT.jar`) into `%USERPROFILE%\.blastradius\bin`.
- Deploys launcher wrappers:
  - `blastradius.ps1`: native PowerShell wrapper.
  - `blastradius.cmd`: Windows batch launcher with `-Dapple.awt.UIElement=true` to suppress OS taskbar stealing.

**Immediate PATH propagation:**

- Registers `%USERPROFILE%\.blastradius\bin` permanently in the Windows User Registry (`HKCU\Environment\PATH`).
- Hot-patches `$env:Path` in the current terminal session in memory, so the `blastradius` command works immediately without restarting the shell or logging out.

**Universal project compatibility:**

Because blastradius monitors approval cards at the OS display / window level, it works across any repository or project technology stack (Python, JavaScript/TypeScript, Go, Rust, C#, PHP, Docker) without project-level plugins or wrappers.

---

## 100% Offline & Privacy-By-Design

- **Zero network sockets at runtime:** does not open outbound network connections, bind HTTP servers, or invoke cloud AI APIs. (The installer uses `winget` once to download Java and Tesseract; the running tool makes no network calls.)
- **Transient in-memory analysis:** screen frame buffers and extracted OCR text strings exist strictly in volatile RAM and are garbage-collected immediately. No images or logs are written to disk.
- **Non-stealing focus:** the popup UI is built with Swing `JWindow` and `setFocusableWindowState(false)`, so typing focus in your IDE or terminal is never interrupted.

---

## Supported AI Agents

| Agent | Target Interface | Detection Method | Status | Notes |
|---|---|---|---|---|
| Google Antigravity | IDE cards & CLI | Screen & PTY | **Verified live** | Matches `Allow run <cmd>?`, sandbox bypass warnings, and multi-choice options |
| OpenAI Codex | Terminal UI | Screen & PTY | Experimental | Pattern for `Would you like to run the following command?` and `$ <cmd>` targets; not yet tested on a live session |
| Cursor / Windsurf | IDE modals | Screen | Experimental | Targets approval modal cards and command terminal prompts; not yet tested on a live session |
| Claude Code | Terminal UI | Screen | Experimental | Targets tool confirmation prompts and execution permissions; not yet tested on a live session |
| Generic shells | PTY / Screen | Pattern heuristic | Experimental | Matches `[y/n]` prompts paired with shell invocation patterns |

Detection depends on each agent's prompt format and may break when the agent updates. OCR can misread text; if a prompt is hidden behind another window or on a screen that is off, it cannot be seen. When unsure, blastradius stays silent rather than raising a false alarm.

---

## CLI Command Reference

| Command | Description |
|---|---|
| `blastradius screen` | Starts the foreground watcher with continuous terminal heartbeat and detection telemetry |
| `blastradius start` | Spawns the headless detached background watcher daemon |
| `blastradius status` | Displays daemon PID, running status, uptime, and JVM memory stats |
| `blastradius stop` | Gracefully stops the background watcher daemon |
| `blastradius explain "<cmd>"` | Offline CLI explanation of a command without triggering the screen watcher |
| `blastradius diagnose` | Validates the local environment (Java version, Tesseract path, display permissions) |

---

## Configuration (`.blastradius.yaml`)

Place `.blastradius.yaml` in your user home directory (`~/.blastradius.yaml`) or workspace root:

```yaml
timeout_seconds: 0        # 0 = persist until prompt leaves screen; >0 = auto-dismiss seconds
show_safe: true           # true = display green popups for safe commands; false = critical/warning only
popup_position: top-right # Placement on primary display: top-right (default), top-left, bottom-right
disable: [sudo]           # Rule IDs to suppress
severity:
  read-env-file: critical
rules:                    # Define custom local offline security rules
  - id: terraform-destroy
    applies_to: [shell]
    pattern: 'terraform destroy'
    severity: critical
    what: Tears down managed cloud infrastructure.
    why: Irreversibly terminates cloud instances and deletes managed resources.
    affects: [cloud, system]
```

---

## Roadmap

Planned, not yet implemented: continuous background process monitoring and deep static/dynamic runtime analysis, to track every child process and socket an AI agent attempts to spawn.
