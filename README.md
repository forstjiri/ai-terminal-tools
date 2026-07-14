# AI Terminal Tools

AI Terminal Tools is a JetBrains IDE plugin for terminal, console, and Commit panel workflows. It adds file jump links, click-to-copy, AI terminal sending, OpenCode / Claude Code launch actions, console error sending, AI Turn Diff, and commit message generation.

## Quick Start

Requirements:

- JetBrains IDE 2025.1+
- OpenCode or Claude Code installed, with `opencode` or `claude` available on `PATH`
- JDK 17 and Gradle Wrapper for local development or plugin builds

Workflow:

1. Click "Start OpenCode" or "Start Claude Code" in the IDE toolbar.
2. The plugin reuses an existing terminal tab for the same tool if one is open, or creates a new tab. It injects the `AITT_*` environment and runs `opencode` or `claude`, then automatically activates the terminal.
3. Send selections from editors, consoles, diffs, or read-only viewers, or send file paths from the project view, editor tabs, or the Commit panel.
4. AI Turn Diff automatically tracks real file content changes in plugin-started OpenCode / Claude Code terminals and opens a diff window when changes are detected.

> AI Turn Diff only auto-attaches to OpenCode / Claude Code terminals started from the plugin buttons. Manually started terminals can still receive selection and path sends, but they will not get the turn-tracking hooks/plugin.

## Features

### File Jump Links

Terminal and console output are parsed into clickable file links that jump to the matching file and line range in the IDE.

Supported formats:

```text
ExampleController.java
ExampleController.java:22
ExampleController.java:22-30
src/main/java/com/example/ExampleController.java:22
./src/main/java/com/example/ExampleController.java:22-30
../module/src/main/java/com/example/ExampleController.java:22
C:\Projects\demo\src\main\java\com\example\ExampleController.java:22
/projects/demo/src/main/java/com/example/ExampleController.java:22
@src/main/java/com/example/ExampleController.java:10
```

Resolution rules:

- Prefer suffix matching against project files.
- Recognizes any file extension of 2 to 12 characters.
- `@path` references have higher priority for AI terminal path matching.
- When multiple files match, IntelliJ project index scoring is used; if there are still multiple candidates, a chooser dialog is shown.

### Click to Copy

Structured output fragments can be turned into clickable copy targets that copy text to the system clipboard and show a small "Copied" hint.

Supported patterns include `{{...}}`, `[[...]]`, function calls, URLs, dotted chains, quoted strings, identifiers, and numbers.

### AI Terminal Sending

Send editor selections, file paths, or console errors into the active terminal input area. The target terminal can be OpenCode or Claude Code.

- Shortcut: `Ctrl+Alt+,`
- Context menus:
  - Editor context menu -> Send Selection to AI Terminal
  - Console / Run output -> Send Selection to AI Terminal
  - Diff view -> Send Selection to AI Terminal
  - Read-only text viewer -> Send Selection to AI Terminal
- Send format:

```text
@src/main/java/A.java:10-20
-------
<selected code>
-------
```

When there is no linked file, only plain text is sent.

File and folder paths are sent as `@displayPath`. Dragging multiple files or folders merges them into a single line and keeps a trailing space to end completion.

### Console Error Sending

Run/Debug Console error and exception headers can show a send icon that sends the visible error block to the active terminal.

Supported sources include JVM, Python, JavaScript/Node.js, TypeScript, Go, Rust, Ruby, and GCC/Clang C/C++ diagnostics.

### AI Turn Diff

When OpenCode or Claude Code is started through the plugin, the plugin tracks each AI turn's file modifications and opens a diff when the content actually changes.

- The before-side of the diff is read-only; the after-side shows the current file state.
- OpenCode: generates a project-level `.opencode/plugins/ai-terminal-tools.js` and a per-terminal launcher.
- Claude Code: generates `.claude/settings.local.json` hooks and a per-terminal launcher.
- Diff state is isolated by `tabId` and upstream `sessionID`.
- You can reopen the last diff from Tools -> AI Terminal Tools -> Show Last AI Turn Diff.

### Terminal Tab Reuse

Clicking "Start OpenCode" or "Start Claude Code" when a terminal for that tool already exists activates and focuses the existing tab instead of creating a new one.

## Settings

Available settings:

- Enable console error send icon
- Enable drag-and-drop files/folders to AI Terminal
- Commit message AI tool (OpenCode or Claude Code)
- Commit message model (full model name with provider prefix)
- Commit message additional prompt
- On turn end command (shell command run after each AI turn completes)
- Append diff changes to next agent message

## Build and Run

Requirements:

- JetBrains IDE 2025.1+ or a custom `-P` target platform
- JDK 17
- Gradle Wrapper

Common commands:

```powershell
./gradlew.bat runIde
./gradlew.bat buildPlugin "-PplatformVersion=2025.1" "-PplatformType=IU"
./gradlew.bat runIde "-PplatformVersion=2025.3" "-PplatformType=IU"
./gradlew.bat buildPlugin
```

## Developer Docs

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for project structure and the OpenCode / Claude Code integration details.

## Plugin Info

| Item | Value |
|------|-------|
| Plugin ID | `io.github.q110.aiterminaltools` |
| Version | `0.3.0` |
| Group | `io.github.q110` |
| Vendor | `zibo` |
| License | MIT |
