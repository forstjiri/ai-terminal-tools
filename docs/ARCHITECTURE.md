# OpenCode / Claude Code / Pi TUI integration — Architecture

This document summarizes the source structure, terminal compatibility layers, and AI CLI integration used by the plugin.

Forked from [Q-110/ai-terminal-tools](https://github.com/Q-110/ai-terminal-tools).

## Project Structure

```text
src/main/kotlin/io/github/q110/aiterminaltools/
|
├── bridge/                                # Terminal interaction and IDE actions
│   ├── AiTerminalBridgeService.kt         # Core bridge, terminal lifecycle and input injection
│   ├── AiTerminalFileLinkService.kt       # Orange overlay-diamond file links
│   ├── AiTerminalDropService.kt           # Drag-and-drop path sending
│   ├── AiTerminalToolsMenuRegistrar.kt    # Registers actions in IDE menus and toolbars
│   ├── FrontendTerminalHelper.kt          # New frontend terminal API adapter
│   ├── LegacyReworkedTerminalHelper.kt    # Older/reworked terminal API adapter
│   ├── AiCliRunner.kt                     # OpenCode, Claude Code and Pi CLI execution
│   ├── GenerateCommitMessageAction.kt     # AI commit message generation
│   ├── SendSelectionToAiTerminalAction.kt # Send selected code to an AI terminal
│   ├── SendPathToAiTerminalAction.kt      # Send file/folder paths to an AI terminal
│   ├── SendDiagnosticToAiTerminalAction.kt# Send an IDE diagnostic to an AI terminal
│   ├── ExplainSelectionWithAiAction.kt    # Explain selected code
│   ├── ModifySelectionWithAiAction.kt     # Modify selected code
│   └── Start*Action.kt                    # Start OpenCode, Claude Code, or Pi
│
├── filter/                                # File-reference matching and path utilities
│   ├── FilterPatterns.kt                  # Regexes for file references and @paths
│   └── PathUtils.kt                       # Path normalization and resolution
│
├── jump/                                  # File and folder hyperlink handlers
│   ├── FileReferenceHyperlinkInfo.kt      # Jump to a file and optional line range
│   ├── FolderReferenceHyperlinkInfo.kt    # Select and expand a project folder
│   └── FileChoiceDialog.kt                # Resolve ambiguous file matches
│
├── console/                               # Console diagnostic handling
│   ├── ConsoleErrorBlockParser.kt         # Extract error blocks from Run/Debug output
│   └── AiConsoleErrorInlayService.kt      # Add a send icon next to error blocks
│
├── monitor/                               # AI Turn Diff and external event integration
│   ├── AiTurnModels.kt                    # Shared turn, event and snapshot models
│   ├── AiTurnEventServer.kt               # Local HTTP server for CLI callbacks
│   ├── AiTurnMonitorService.kt            # Turn state machine keyed by tab/session
│   ├── AiTurnSnapshotService.kt           # Capture pre-change file snapshots
│   ├── AiTurnDiffPresenter.kt             # Build and show native IntelliJ diffs
│   ├── AiTurnDiffDialog.kt                # Multi-file diff window
│   ├── AiTerminalLauncher.kt              # Generate per-terminal launcher scripts
│   ├── AiTurnOpenCodeInstaller.kt         # OpenCode plugin and launcher generator
│   ├── AiTurnHookInstaller.kt             # Claude Code hooks and launcher generator
│   ├── AiTurnPiInstaller.kt               # Pi extension and launcher generator
│   └── ShowLastAiTurnDiffAction.kt        # Reopen the latest AI Turn Diff
│
├── settings/                              # Persistent settings and settings UI
└── ProjectBasePath.kt                     # Project path validation and resolution
```

## Terminal Compatibility

- Frontend: IDE 2025.3+ uses the newer terminal tabs API through reflection.
- Legacy Reworked: IDE 2025.1–2025.2 uses the older Reworked Terminal API through reflection.
- Classic: fallback path using `ShellTerminalWidget` and the TTY connector.

`AiTerminalBridgeService` selects the best available implementation and keeps the rest of the plugin independent of terminal API changes.

## Terminal File Links

`AiTerminalFileLinkService` scans reworked/frontend terminal editor documents, matches file and `@path` references with `FilterPatterns`, resolves them through `PathUtils`, and adds orange overlay-diamond markers. Clicking a marker uses the jump hyperlink handlers.

## AI Turn Diff Integration

AI Turn Diff is built from a local event server, terminal-specific launchers, and one integration adapter for each supported CLI.

OpenCode:

- Generates `.opencode/plugins/ai-terminal-tools.js` and a per-terminal launcher.
- Uses `session.status` events: `busy` starts a turn and `idle` ends it.

Claude Code:

- Generates `.claude/settings.local.json` hooks and a per-terminal launcher.
- Uses `UserPromptSubmit`, `PreToolUse`, `PostToolUse`, `Stop`, and `StopFailure` hooks.

Pi:

- Generates `.pi/extensions/ai-terminal-tools.ts` and a per-terminal launcher.
- The extension reports turn and file events to the local event server.

All launchers inject `AITT_PORT`, `AITT_TOKEN`, and `AITT_TAB_ID`. Diff state is isolated by tab ID and upstream session ID, so multiple AI terminals can run concurrently without mixing state. When a turn completes, the presenter compares snapshots with the current files and opens the native diff UI only when content changed.
