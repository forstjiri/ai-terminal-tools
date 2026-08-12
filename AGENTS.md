# AGENTS.md

## Plugin Overview

This plugin provides five main capabilities:

1. Opens and manages AI terminal tabs for OpenCode, Claude Code, and Pi.
2. Sends selected text, diagnostics, console errors, and `@path` references from the IDE into an active AI terminal.
3. Adds orange overlay-diamond file links to frontend/reworked terminal editors.
4. Monitors AI turns, captures file snapshots, and shows diffs after a turn finishes.
5. Generates commit messages from selected changes in the Commit panel.

## Package Overview

### `settings/`

- `AiTerminalToolsSettings.kt` stores persistent plugin settings, including file links, console icons, drag-and-drop, commit-message tool/model/prompt, startup commands, and post-turn commands.
- `AiTerminalToolsConfigurable.kt` renders and applies the Settings UI.

### `bridge/`

- `AiTerminalBridgeService.kt` is the central project service. It creates OpenCode, Claude Code, and Pi terminal tabs, detects active terminals, injects input, and coordinates monitoring.
- `AbstractStartAiTerminalAction.kt` and the `Start*Action.kt` classes implement the three terminal start actions.
- `AiCliRunner.kt` runs OpenCode, Claude Code, and Pi for query and commit-message workflows.
- `AbstractSelectionAiAction.kt` is the base for AI selection actions.
- `ExplainSelectionWithAiAction.kt` explains selected code.
- `ModifySelectionWithAiAction.kt` replaces selected code with an AI-generated modification.
- `SendSelectionToAiTerminalAction.kt` sends selected editor text with file and line context.
- `SendPathToAiTerminalAction.kt` sends selected files/folders as `@path` references.
- `SendDiagnosticToAiTerminalAction.kt` sends the diagnostic under the caret.
- `DiagnosticPayload.kt` formats diagnostic payloads.
- `GenerateCommitMessageAction.kt` builds a selected-change summary and writes the generated message into the Commit panel.
- `AiTerminalFileLinkService.kt` scans terminal documents and adds clickable file-reference overlays using `FilterPatterns` and `PathUtils`.
- `AiTerminalDropService.kt` sends dragged files/folders as `@path` references.
- `FrontendTerminalHelper.kt` adapts newer terminal APIs through reflection.
- `LegacyReworkedTerminalHelper.kt` adapts older/reworked terminal APIs through reflection.
- `AiTerminalToolsMenuRegistrar.kt` registers plugin actions in IDE menus and toolbars.

### `filter/` and `jump/`

- `FilterPatterns.kt` defines file-reference and `@path` regular expressions.
- `PathUtils.kt` normalizes paths and resolves project files.
- `FileReferenceHyperlinkInfo.kt` opens files at matched lines or line ranges.
- `FolderReferenceHyperlinkInfo.kt` selects and expands folders in Project View.
- `FileChoiceDialog.kt` resolves ambiguous file matches.

### `console/`

- `ConsoleErrorBlockParser.kt` recognizes error and stack-trace blocks in Run/Debug output.
- `AiConsoleErrorInlayService.kt` adds an inline send action beside detected console errors.

### `monitor/`

- `AiTurnModels.kt` contains shared models for tools, events, turns, snapshots, and terminal contexts.
- `AiTurnEventServer.kt` runs a local HTTP server on `127.0.0.1` for Claude hooks, the OpenCode plugin, and the Pi extension.
- `AiTurnMonitorService.kt` registers AI tabs, processes events, captures snapshots, and decides when to show diffs.
- `AiTurnSnapshotService.kt` captures text/binary file snapshots before AI edits, with size limits.
- `AiTurnDiffPresenter.kt` builds IntelliJ diff requests, remembers the latest completed turn, and supports reverting changes.
- `AiTurnDiffDialog.kt` displays changed files in a standalone multi-file dialog.
- `AiTerminalLauncher.kt` generates per-terminal `.cmd` and `.sh` launchers with `AITT_*` variables.
- `AiTurnHookInstaller.kt` generates Claude Code hooks and launchers.
- `AiTurnOpenCodeInstaller.kt` generates the project-level OpenCode JavaScript plugin and launchers.
- `AiTurnPiInstaller.kt` generates the project-level Pi TypeScript extension and launchers.
- `ShowLastAiTurnDiffAction.kt` reopens the most recent completed AI turn diff.

## End-to-End Flow

1. The user starts OpenCode, Claude Code, or Pi through the plugin.
2. The plugin creates a launcher and installs the tool-specific integration when needed:
   - OpenCode: `.opencode/plugins/ai-terminal-tools.js`
   - Claude Code: `.claude/settings.local.json` hooks
   - Pi: `.pi/extensions/ai-terminal-tools.ts`
3. The launcher injects `AITT_*` environment variables so callbacks are associated with the correct project and terminal tab.
4. When an AI turn begins, the monitor captures before-snapshots for files reported by the tool.
5. When the turn ends, current files are compared with snapshots and a diff is shown if content changed.
6. Independently, terminal and console output can provide clickable file links, and IDE selections, diagnostics, errors, and dropped paths can be sent to the active AI terminal.

## Maintenance Notes

- Keep README, `docs/ARCHITECTURE.md`, plugin metadata, and this file synchronized when adding a supported AI CLI.
- Generated launcher files and tool integrations are created under the project `.idea`, `.opencode`, `.claude`, or `.pi` directories at runtime; they should not be committed.
