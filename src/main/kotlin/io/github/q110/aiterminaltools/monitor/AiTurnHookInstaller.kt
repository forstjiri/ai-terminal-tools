// Hook installer - generates Claude hook configuration, hook scripts, and launcher scripts
package io.github.q110.aiterminaltools.monitor

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Generates the following for Claude Code:
 * 1. `.claude/settings.local.json` - hook configuration
 * 2. `.idea/ai-terminal-tools/claude-hook.cmd` - Windows cmd wrapper
 * 3. `.idea/ai-terminal-tools/claude-hook.ps1` - PowerShell hook implementation
 * 4. `.idea/ai-terminal-tools/claude-hook.sh` - macOS/Linux hook implementation
 * 5. `.idea/ai-terminal-tools/run-claude-<tabId>.cmd` - Windows launcher
 * 6. `.idea/ai-terminal-tools/run-claude-<tabId>.sh` - macOS/Linux launcher
 */
class AiTurnHookInstaller(
    private val project: Project
) {
    private val log = Logger.getInstance(AiTurnHookInstaller::class.java)

    /**
     * 安装 Claude Code hooks 并生成 launcher 脚本。
     * 返回 launcher 脚本的文件名（不含路径，相对于 .idea/ai-terminal-tools/）。
     */
    fun installClaudeHooks(tabId: String, token: String, port: Int): LauncherPaths {
        val projectBasePath = project.basePath
            ?: throw IllegalStateException("Project base path is null")
        val basePath = Path.of(projectBasePath)

        // 1. Ensure the `.idea/ai-terminal-tools/` directory exists.
        val toolsDir = basePath.resolve(".idea").resolve("ai-terminal-tools")
        Files.createDirectories(toolsDir)

        // 2. Generate the hook scripts.
        writeHookScripts(toolsDir)

        // 3. Generate or merge `.claude/settings.local.json`.
        writeClaudeSettings(basePath, toolsDir)

        // 4. Generate the launcher scripts.
        return writeLauncherScripts(toolsDir, tabId, token, port)
    }

    private fun writeHookScripts(toolsDir: Path) {
        // `claude-hook.cmd` - Windows cmd wrapper that delegates to PowerShell.
        val hookCmd = toolsDir.resolve("claude-hook.cmd")
        Files.writeString(hookCmd, buildString {
            appendLine("@echo off")
            appendLine("set EVENT_TYPE=%1")
            appendLine("powershell -NoProfile -ExecutionPolicy Bypass -File \"%~dp0claude-hook.ps1\" \"%EVENT_TYPE%\"")
        })

        // `claude-hook.ps1` - PowerShell implementation.
        val hookPs1 = toolsDir.resolve("claude-hook.ps1")
        Files.writeString(hookPs1, buildString {
            appendLine("param(")
            appendLine("  [string]\$EventType")
            appendLine(")")
            appendLine()
            appendLine("\$raw = [Console]::In.ReadToEnd()")
            appendLine()
            appendLine("# Extract file paths from the raw JSON")
            appendLine("\$paths = @()")
            appendLine("if (\$raw -match '\"file_path\"\\s*:\\s*\"([^\"]+)\"') {")
            appendLine("  \$paths += \$Matches[1]")
            appendLine("}")
            appendLine("if (\$raw -match '\"path\"\\s*:\\s*\"([^\"]+)\"') {")
            appendLine("  \$paths += \$Matches[1]")
            appendLine("}")
            appendLine("if (\$raw -match '\"file\"\\s*:\\s*\"([^\"]+)\"') {")
            appendLine("  \$paths += \$Matches[1]")
            appendLine("}")
            appendLine()
            appendLine("\$pathsJson = '[]'")
            appendLine("if (\$paths.Count -gt 0) {")
            appendLine("  \$escapedPaths = \$paths | ForEach-Object { '\"' + \$_.Replace('\\', '\\\\').Replace('\"', '\\\"') + '\"' }")
            appendLine("  \$pathsJson = '[' + (\$escapedPaths -join ',') + ']'")
            appendLine("}")
            appendLine()
            appendLine("\$body = '{' +")
            appendLine("  '\"source\":\"claude\",' +")
            appendLine("  '\"type\":\"' + \$EventType + '\",' +")
            appendLine("  '\"tabId\":\"' + \$env:AITT_TAB_ID + '\",' +")
            appendLine("  '\"paths\":' + \$pathsJson +")
            appendLine("  '}'")
            appendLine()
            appendLine("try {")
            appendLine("  Invoke-RestMethod ``")
            appendLine("    -Uri \"http://127.0.0.1:\$env:AITT_PORT/event\" ``")
            appendLine("    -Method POST ``")
            appendLine("    -Headers @{ 'X-AITT-Token' = \$env:AITT_TOKEN } ``")
            appendLine("    -Body \$body ``")
            appendLine("    -ContentType 'application/json' | Out-Null")
            appendLine("} catch {")
            appendLine("  # Silently ignore errors to avoid interrupting Claude Code")
            appendLine("}")
        })

        // `claude-hook.sh` - macOS/Linux bash implementation.
        val hookSh = toolsDir.resolve("claude-hook.sh")
        Files.writeString(hookSh, buildString {
            appendLine("#!/usr/bin/env bash")
            appendLine("EVENT_TYPE=\"\$1\"")
            appendLine("RAW=\"\$(cat)\"")
            appendLine()
            appendLine("# Extract file paths from raw JSON (portable sed, works on macOS and Linux)")
            appendLine("PATHS=\"[]\"")
            appendLine("FILE_PATH=\$(echo \"\$RAW\" | sed -n 's/.*\"file_path\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' | head -1)")
            appendLine("if [ -z \"\$FILE_PATH\" ]; then")
            appendLine("  FILE_PATH=\$(echo \"\$RAW\" | sed -n 's/.*\"path\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' | head -1)")
            appendLine("fi")
            appendLine("if [ -z \"\$FILE_PATH\" ]; then")
            appendLine("  FILE_PATH=\$(echo \"\$RAW\" | sed -n 's/.*\"file\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' | head -1)")
            appendLine("fi")
            appendLine("if [ -n \"\$FILE_PATH\" ]; then")
            appendLine("  PATHS=\"[\\\"\$FILE_PATH\\\"]\"")
            appendLine("fi")
            appendLine()
            appendLine("curl -sS \\")
            appendLine("  -X POST \"http://127.0.0.1:\${AITT_PORT}/event\" \\")
            appendLine("  -H \"content-type: application/json\" \\")
            appendLine("  -H \"x-aitt-token: \${AITT_TOKEN}\" \\")
            appendLine("  --data \"{\\\"source\\\":\\\"claude\\\",\\\"type\\\":\\\"\${EVENT_TYPE}\\\",\\\"tabId\\\":\\\"\${AITT_TAB_ID}\\\",\\\"paths\\\":\${PATHS}}\" \\")
            appendLine("  2>/dev/null || true")
        })

        // Mark the shell script executable on non-Windows platforms.
        setExecutableIfPosix(hookSh)
    }

    private fun writeClaudeSettings(projectBasePath: Path, toolsDir: Path) {
        val claudeDir = projectBasePath.resolve(".claude")
        Files.createDirectories(claudeDir)

        val settingsFile = claudeDir.resolve("settings.local.json")

        // Use ${CLAUDE_PROJECT_DIR} + forward slashes to match the official docs.
        //    - ${CLAUDE_PROJECT_DIR} is expanded by Claude Code when the hook runs and always points to the project root
        //    - Forward slashes are portable across Git Bash / WSL / Linux / macOS
        //    - Always use the .sh script (Windows Claude Code uses Git Bash's /usr/bin/bash)
        val hookCommand = "\${CLAUDE_PROJECT_DIR}/.idea/ai-terminal-tools/claude-hook.sh"

        // Read any existing configuration and merge it.
        val existingContent = if (Files.exists(settingsFile)) {
            try {
                Files.readString(settingsFile)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        val newSettings = generateClaudeSettingsJson(hookCommand, existingContent)
        Files.writeString(settingsFile, newSettings)
        log.info("Written Claude settings to $settingsFile")
    }

    /**
     * 生成 Claude settings.local.json 内容。
     * 如果已有配置存在，保留非 hooks 字段。
     */
    private fun generateClaudeSettingsJson(hookCommand: String, existingContent: String?): String {
        // JSON-escape 反斜杠（Windows 路径需要 \\ → \\\\）
        val escapedCommand = hookCommand.replace("\\", "\\\\")

        // Build the new hooks JSON.
        val hooksJson = buildString {
            appendLine("{")

            // Preserve the existing non-hooks fields.
            if (existingContent != null) {
                val nonHooksFields = extractNonHooksFields(existingContent)
                if (nonHooksFields.isNotEmpty()) {
                    append("  $nonHooksFields,")
                    appendLine()
                }
            }

            appendLine("  \"hooks\": {")
            appendLine("    \"UserPromptSubmit\": [")
            appendLine("      {")
            appendLine("        \"hooks\": [")
            appendLine("          {")
            appendLine("            \"type\": \"command\",")
            appendLine("            \"command\": \"$escapedCommand turn_start\"")
            appendLine("          }")
            appendLine("        ]")
            appendLine("      }")
            appendLine("    ],")
            appendLine("    \"PreToolUse\": [")
            appendLine("      {")
            appendLine("        \"matcher\": \"Edit|Write|MultiEdit|NotebookEdit\",")
            appendLine("        \"hooks\": [")
            appendLine("          {")
            appendLine("            \"type\": \"command\",")
            appendLine("            \"command\": \"$escapedCommand before_write\"")
            appendLine("          }")
            appendLine("        ]")
            appendLine("      }")
            appendLine("    ],")
            appendLine("    \"PostToolUse\": [")
            appendLine("      {")
            appendLine("        \"matcher\": \"Edit|Write|MultiEdit|NotebookEdit\",")
            appendLine("        \"hooks\": [")
            appendLine("          {")
            appendLine("            \"type\": \"command\",")
            appendLine("            \"command\": \"$escapedCommand file_changed\"")
            appendLine("          }")
            appendLine("        ]")
            appendLine("      }")
            appendLine("    ],")
            appendLine("    \"Stop\": [")
            appendLine("      {")
            appendLine("        \"hooks\": [")
            appendLine("          {")
            appendLine("            \"type\": \"command\",")
            appendLine("            \"command\": \"$escapedCommand turn_end\"")
            appendLine("          }")
            appendLine("        ]")
            appendLine("      }")
            appendLine("    ],")
            appendLine("    \"StopFailure\": [")
            appendLine("      {")
            appendLine("        \"hooks\": [")
            appendLine("          {")
            appendLine("            \"type\": \"command\",")
            appendLine("            \"command\": \"$escapedCommand turn_end_failed\"")
            appendLine("          }")
            appendLine("        ]")
            appendLine("      }")
            appendLine("    ]")
            appendLine("  }")
            append("}")
        }

        return hooksJson
    }

    /**
     * 从已有 JSON 中提取非 hooks 的顶层字段。
     * Simple implementation: find all `"key": value` pairs and exclude `hooks`.
     */
    private fun extractNonHooksFields(json: String): String {
        // Simplified handling: if the original JSON contains top-level fields other than hooks, try to preserve them.
        // Without a JSON library, this only does lightweight string processing.
        val fields = mutableListOf<String>()

        // Match simple `"key": "value"` fields.
        val simpleFieldPattern = Regex("""^\s*"([^"]+)"\s*:\s*("[^"]*"|true|false|\d+)\s*,?\s*$""", RegexOption.MULTILINE)
        for (match in simpleFieldPattern.findAll(json)) {
            val key = match.groupValues[1]
            if (key != "hooks") {
                fields.add("\"$key\": ${match.groupValues[2]}")
            }
        }

        return fields.joinToString(", ")
    }

    private fun writeLauncherScripts(
        toolsDir: Path,
        tabId: String,
        token: String,
        port: Int
    ): LauncherPaths {
        val cmdFile = toolsDir.resolve("run-claude-$tabId.cmd")
        val shFile = toolsDir.resolve("run-claude-$tabId.sh")

        // Windows cmd launcher
        Files.writeString(cmdFile, buildString {
            appendLine("@echo off")
            appendLine("set AITT_PORT=$port")
            appendLine("set AITT_TOKEN=$token")
            appendLine("set AITT_TAB_ID=$tabId")
            appendLine("set AITT_TOOL=claude")
            appendLine("claude")
        })

        // macOS/Linux bash launcher
        Files.writeString(shFile, buildString {
            appendLine("#!/usr/bin/env bash")
            appendLine("export AITT_PORT=\"$port\"")
            appendLine("export AITT_TOKEN=\"$token\"")
            appendLine("export AITT_TAB_ID=\"$tabId\"")
            appendLine("export AITT_TOOL=\"claude\"")
            appendLine("exec claude")
        })

        setExecutableIfPosix(shFile)

        return LauncherPaths(
            cmdPath = cmdFile,
            shPath = shFile
        )
    }

    /** Clean up launcher scripts for the specified tabId. */
    fun cleanupLauncherScripts(tabId: String) {
        val projectBasePath = project.basePath ?: return
        val toolsDir = Path.of(projectBasePath).resolve(".idea").resolve("ai-terminal-tools")
        try {
            Files.deleteIfExists(toolsDir.resolve("run-claude-$tabId.cmd"))
            Files.deleteIfExists(toolsDir.resolve("run-claude-$tabId.sh"))
        } catch (exception: Throwable) {
            log.warn("Failed to cleanup launcher scripts for tab $tabId", exception)
        }
    }

    private fun setExecutableIfPosix(path: Path) {
        try {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms.add(PosixFilePermission.OWNER_EXECUTE)
            perms.add(PosixFilePermission.GROUP_EXECUTE)
            Files.setPosixFilePermissions(path, perms)
        } catch (_: UnsupportedOperationException) {
            // Windows does not support POSIX permissions.
        } catch (exception: Throwable) {
            log.warn("Failed to set executable permission on $path", exception)
        }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name", "").lowercase().contains("win")
    }

    data class LauncherPaths(
        val cmdPath: Path,
        val shPath: Path
    )
}
