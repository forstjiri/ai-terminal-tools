# Opencode / Claude TUI integration — Architecture

This document summarizes the source structure, terminal compatibility layers, and the OpenCode / Claude Code integration used by AI Turn Diff.

Forked from [Q-110/ai-terminal-tools](https://github.com/Q-110/ai-terminal-tools).

## Project Structure

```text
src/main/kotlin/io/github/q110/aiterminaltools/
|
├── bridge/                                # Terminal interaction, context menus, drag-and-drop
│   ├── AiTerminalBridgeService.kt         # Core bridge service for terminal input injection
│   ├── FrontendTerminalHelper.kt          # New frontend terminal helper
│   ├── LegacyReworkedTerminalHelper.kt    # Legacy Reworked terminal helper
│   ├── SendSelectionToAiTerminalAction.kt # Action: send selected code to AI terminal
│   ├── SendPathToAiTerminalAction.kt      # Action: send file/folder paths to AI terminal
│   ├── StartOpenCodeAction.kt             # Action: start an OpenCode terminal session
│   ├── StartClaudeCodeAction.kt           # Action: start a Claude Code terminal session
│   ├── GenerateCommitMessageAction.kt     # Action: generate Git commit messages through AI
│   ├── AiTerminalDropService.kt           # Drag-and-drop service for terminal path sending
│   └── AiTerminalToolsMenuRegistrar.kt    # Startup activity that registers context menu actions
|
├── filter/                                # Output filtering for jump/copy links
│   ├── AiTerminalToolsFilter.kt           # Core filter for jump and copy links
│   ├── AiTerminalToolsFilterProvider.kt   # Registers the core filter for consoles/terminals
│   ├── FilterPatterns.kt                  # Regex constants for file refs, @paths, and copy patterns
│   └── PathUtils.kt                       # Path utilities
|
├── jump/                                  # File and folder hyperlink handlers
│   ├── FileReferenceHyperlinkInfo.kt      # Jump to the best matching file and line
│   ├── FolderReferenceHyperlinkInfo.kt    # Select and expand a folder in Project View
│   └── FileChoiceDialog.kt                # Manual chooser for ambiguous file matches
|
├── console/                               # Console error handling
│   ├── ConsoleErrorBlockParser.kt         # Extracts error blocks from Run/Debug output
│   └── AiConsoleErrorInlayService.kt      # Adds a send icon next to console error blocks
|
├── monitor/                               # AI Turn Diff: events, snapshots, and diff presentation
│   ├── AiTurnEventServer.kt               # Local HTTP event server for hooks and plugins
│   ├── AiTurnMonitorService.kt            # Turn state machine keyed by tabId/sessionID
│   ├── AiTurnOpenCodeInstaller.kt         # OpenCode plugin and launcher generator
│   ├── AiTurnHookInstaller.kt             # Claude Code hook and launcher generator
│   ├── AiTurnSnapshotService.kt           # Captures pre-change file snapshots
│   ├── AiTurnDiffPresenter.kt             # Builds and shows AI turn diffs
│   ├── AiTurnDiffDialog.kt                # Multi-file diff window
│   └── ShowLastAiTurnDiffAction.kt        # Reopen the latest AI Turn Diff
|
└── settings/                              # Plugin settings
    ├── AiTerminalToolsSettings.kt         # Persistent settings stored in ai-terminal-tools.xml
    └── AiTerminalToolsConfigurable.kt     # Settings UI
```

## Terminal Compatibility

- Frontend: IDE 2025.3+ uses `TerminalToolWindowTabsManager`.
- Legacy Reworked: IDE 2025.1 to 2025.2 uses reflection against the older Reworked Terminal API.
- OpenCode: IDE 2025.1 to 2025.2 falls back to Classic Terminal to avoid Reworked rendering issues.
- Classic: fallback path using `ShellTerminalWidget` and TTY Connector.

## AI Turn Diff Integration

AI Turn Diff is built from a local event server, terminal-specific launchers, an OpenCode plugin, and Claude Code hooks.

OpenCode:

- Generates a project-level `.opencode/plugins/ai-terminal-tools.js` and a per-terminal launcher.
- The plugin reads `AITT_PORT`, `AITT_TOKEN`, and `AITT_TAB_ID` from the current process environment.
- `session.status busy` is used as the turn start signal.
- `session.idle` is used as the turn end signal.

Claude Code:

- Generates `.claude/settings.local.json` hooks and a per-terminal launcher.
- Uses `UserPromptSubmit`, `PreToolUse`, `PostToolUse`, `Stop`, and `StopFailure` to maintain turn state.

Isolation and display:

- Diff content is isolated by `tabId` and upstream `sessionID`.
- Multiple OpenCode / Claude Code terminals can run at the same time without mixing states.
- When a turn completes, `AiTurnDiffPresenter` builds the diff and displays it through `AiTurnDiffDialog`.
