# AGENTS.md

## Plugin Overview

This plugin mainly does five things:

1. Opens and manages AI terminal tabs for `OpenCode` and `Claude Code`.
2. Sends selected text or `@path` references from the IDE into the active AI terminal.
3. Turns terminal/console output into clickable file links and click-to-copy links.
4. Monitors AI turns, captures file snapshots, and shows diffs after a turn finishes.
5. Generates commit messages from selected changes in the Commit panel.

## Package Overview

### `settings/`

- `AiTerminalToolsSettings.kt`
  Stores plugin settings in `ai-terminal-tools.xml`.
  Holds flags for file links, copy links, drag-to-terminal, commit message tool/model/prompt, extra file extensions, and custom terminal start commands.

- `AiTerminalToolsConfigurable.kt`
  Renders the Settings UI in the IDE.
  Reads and writes all plugin options through `AiTerminalToolsSettings`.

### `bridge/`

- `AiTerminalBridgeService.kt`
  Main bridge service.
  Creates AI terminal tabs, detects the active terminal, sends text into it, tracks AI terminals, and coordinates terminal integration.

- `FrontendTerminalHelper.kt`
  Adapter for newer IntelliJ terminal APIs.
  Opens tabs, runs commands, and injects input into frontend terminals.

- `LegacyReworkedTerminalHelper.kt`
  Adapter for older/reworked terminal APIs.
  Provides the same basic operations for older terminal implementations.

- `StartOpenCodeAction.kt`
  Action that opens an OpenCode terminal.

- `StartClaudeCodeAction.kt`
  Action that opens a Claude Code terminal.

- `SendSelectionToAiTerminalAction.kt`
  Sends the current editor selection to the active AI terminal, including file path and line info.

- `SendPathToAiTerminalAction.kt`
  Sends an `@path` reference for the selected file or folder to the active AI terminal.

- `GenerateCommitMessageAction.kt`
  Collects the selected commit changes, builds a diff summary, runs `opencode` or `claude`, and writes the generated commit message back into the Commit panel.

- `AiTerminalToolsMenuRegistrar.kt`
  Registers plugin actions into editor, project view, console, diff, and toolbar menus on startup.

- `AiTerminalDropService.kt`
  Lets users drag files/folders into terminal tabs and send them as `@path` references.
  Also handles some click-to-copy behavior for classic terminals.

### `filter/`

- `AiTerminalToolsFilterProvider.kt`
  Factory/provider for the main console filter.

- `AiTerminalToolsFilter.kt`
  Parses terminal and console output.
  Detects file references, `@path` references, and copyable tokens, then turns them into clickable links.

- `FilterPatterns.kt`
  Contains regex patterns used by the filter layer.

- `PathUtils.kt`
  Path normalization and resolution helpers used by the filter and jump logic.

### `jump/`

- `FileReferenceHyperlinkInfo.kt`
  Opens a file at the matched line or line range when a file reference is clicked.

- `FolderReferenceHyperlinkInfo.kt`
  Selects and expands a folder in Project View.

- `FileChoiceDialog.kt`
  Lets the user choose the correct file when one reference matches multiple candidates.

### `copy/`

- `CopyTextHyperlinkInfo.kt`
  Copies clicked text into the clipboard and shows a short confirmation balloon.

### `console/`

- `ConsoleErrorBlockParser.kt`
  Parses Run/Debug console output and detects error or stack-trace blocks.

- `AiConsoleErrorInlayService.kt`
  Adds an inline action icon near detected error blocks so they can be sent to the AI terminal.

### `monitor/`

- `AiTurnModels.kt`
  Shared data models for AI turn monitoring: tools, events, snapshots, terminal context, and turn state.

- `AiTurnMonitorService.kt`
  Central turn-monitoring service.
  Registers AI tabs, processes turn events, captures snapshots, and decides when to show diffs.

- `AiTurnSnapshotService.kt`
  Captures file snapshots before AI-driven edits.
  Handles text/binary detection and file size limits.

- `AiTurnEventServer.kt`
  Local HTTP server on `127.0.0.1` that receives events from Claude hooks and the OpenCode plugin.

- `AiTurnDiffPresenter.kt`
  Builds IntelliJ diff requests from before/after snapshots and opens the diff UI.
  Also remembers the last completed AI turn.

- `AiTurnDiffDialog.kt`
  Standalone diff window with file selection.

- `AiTurnHookInstaller.kt`
  Generates Claude hook scripts and Claude launcher scripts.
  Injects `AITT_*` environment variables and optionally uses a custom Claude start command.

- `AiTurnOpenCodeInstaller.kt`
  Generates the OpenCode JS plugin and OpenCode launcher scripts.
  The JS plugin sends session/file events back to the IDE; the launcher injects `AITT_*` environment variables and can use a custom OpenCode start command.

- `ShowLastAiTurnDiffAction.kt`
  Reopens the diff for the most recent completed AI turn.

## End-to-End Flow

1. The user opens an OpenCode or Claude Code terminal through the plugin.
2. The plugin creates launcher scripts and, when needed, hook/plugin integration files.
3. The AI terminal runs with `AITT_*` environment variables so it can report back to the correct IDE project/tab.
4. When the AI starts editing files, the plugin captures before-snapshots.
5. When the turn ends, the plugin compares before/after snapshots and shows a diff.
6. Separately, the plugin enhances terminal and console output with clickable links, copy actions, and send-to-AI helpers.
