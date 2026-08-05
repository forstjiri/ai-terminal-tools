package io.github.q110.aiterminaltools.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

internal object AiCliRunner {
    const val OPENCODE_TIMEOUT_SECONDS = 120L
    const val CLAUDE_TIMEOUT_SECONDS = 120L
    const val PI_TIMEOUT_SECONDS = 120L
    private const val PROCESS_POLL_INTERVAL_MS = 200L
    val ANSI_PATTERN = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")

    fun normalizedCommitMessageAiTool(aiTool: String): String {
        return when (aiTool) {
            "claude" -> "claude"
            "pi" -> "pi"
            else -> "opencode"
        }
    }

    fun toolDisplayName(aiTool: String): String {
        return when (aiTool) {
            "claude" -> "Claude Code"
            "pi" -> "Pi"
            else -> "OpenCode"
        }
    }

    data class ProcessResult(val exitCode: Int, val output: String)
    data class CommitTerminal(val disposable: Disposable, val content: com.intellij.ui.content.Content)

    fun runProcess(
        command: List<String>,
        workingDirectory: Path,
        timeoutSeconds: Long,
        environment: Map<String, String> = emptyMap()
    ): ProcessResult {
        val process = try {
            ProcessBuilder(command).apply {
                environment().putAll(environment)
            }
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (exception: Throwable) {
            throw AiCliException("Could not start command ${command.firstOrNull().orEmpty()}: ${exception.message}")
        }
        try {
            process.outputStream.close()
        } catch (_: Throwable) {
        }

        val output = StringBuilder()
        val readerThread = Thread {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    output.appendLine(line)
                }
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            if (process.waitFor(PROCESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) break
            if (System.nanoTime() >= deadline) {
                destroyProcessTree(process)
                throw AiCliException("Command timed out: ${command.firstOrNull().orEmpty()}")
            }
        }
        readerThread.join(TimeUnit.SECONDS.toMillis(2))
        return ProcessResult(process.exitValue(), output.toString())
    }

    fun runProcessWithStdin(
        command: List<String>,
        workingDirectory: Path,
        stdinContent: String,
        timeoutSeconds: Long
    ): ProcessResult {
        val process = try {
            ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (exception: Throwable) {
            throw AiCliException("Could not start command ${command.firstOrNull().orEmpty()}: ${exception.message}")
        }
        try {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(stdinContent)
                writer.flush()
            }
        } catch (_: Throwable) {
        }

        val output = StringBuilder()
        val readerThread = Thread {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    output.appendLine(line)
                }
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            if (process.waitFor(PROCESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) break
            if (System.nanoTime() >= deadline) {
                destroyProcessTree(process)
                throw AiCliException("Command timed out: ${command.firstOrNull().orEmpty()}")
            }
        }
        readerThread.join(TimeUnit.SECONDS.toMillis(2))
        return ProcessResult(process.exitValue(), output.toString())
    }

    fun destroyProcessTree(process: Process) {
        process.descendants().forEach { descendant ->
            try {
                descendant.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        process.destroyForcibly()
    }

    fun opencodeCommand(): String {
        return if (SystemInfo.isWindows) "opencode.cmd" else "opencode"
    }

    fun piCommand(): String {
        return if (SystemInfo.isWindows) "pi.cmd" else "pi"
    }

    fun claudeCommand(): String {
        if (!SystemInfo.isWindows) return "claude"
        val pathValue = System.getenv("PATH").orEmpty()
        val commandNames = listOf("claude.cmd", "claude.exe", "claude.bat", "claude")
        pathValue.split(File.pathSeparatorChar)
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .forEach { pathEntry ->
                commandNames.forEach { commandName ->
                    val commandPath = Path.of(pathEntry, commandName)
                    if (Files.isRegularFile(commandPath)) {
                        return commandPath.toAbsolutePath().normalize().toString()
                    }
                }
            }
        return "claude"
    }

    fun shellQuote(value: String): String {
        return if (SystemInfo.isWindows) {
            "'${value.replace("'", "''")}'"
        } else {
            "'${value.replace("'", "'\\''")}'"
        }
    }

    fun createCommitTerminal(
        project: Project,
        workingDirectory: Path,
        command: String,
        tabName: String = "AI CLI"
    ): CommitTerminal {
        var result: CommitTerminal? = null
        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait({
            try {
                val manager = TerminalToolWindowManager.getInstance(project)
                val toolWindow = manager.toolWindow
                    ?: throw AiCliException("Terminal tool window was not found.")
                val startupDisposable = Disposer.newDisposable("AI CLI Terminal")
                val options = ShellStartupOptions.Builder()
                    .workingDirectory(workingDirectory.toString())
                    .build()
                val widget = try {
                    manager.terminalRunner.startShellTerminalWidget(startupDisposable, options, true)
                } catch (exception: Throwable) {
                    Disposer.dispose(startupDisposable)
                    throw exception
                }
                val content = manager.newTab(toolWindow, widget)
                content.displayName = tabName
                toolWindow.activate(Runnable {
                    ShellTerminalWidget.toShellJediTermWidgetOrThrow(widget).executeCommand(command)
                }, true, true)
                result = CommitTerminal(startupDisposable, content)
            } catch (exception: Throwable) {
                failure = exception
            }
        }, ModalityState.defaultModalityState())
        failure?.let { throw AiCliException("Could not open terminal: ${it.message}") }
        return result ?: throw AiCliException("Could not open terminal")
    }

    fun closeCommitTerminal(terminal: CommitTerminal) {
        val close = Runnable {
            try {
                terminal.content.manager?.removeContent(terminal.content, true)
            } catch (_: Throwable) {
            }
            try {
                Disposer.dispose(terminal.disposable)
            } catch (_: Throwable) {
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            close.run()
        } else {
            ApplicationManager.getApplication().invokeLater(close)
        }
    }

    fun runClaudeQuery(prompt: String, basePath: Path): String {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        val command = mutableListOf(
            claudeCommand(), "-p", prompt,
            "--output-format", "text", "--no-session-persistence"
        )
        val model = settings.claudeCommitMessageModel.trim()
        if (model.isNotEmpty()) command += listOf("--model", model)
        val result = runProcessWithStdin(command, basePath, prompt, CLAUDE_TIMEOUT_SECONDS)
        if (result.exitCode != 0) {
            throw AiCliException(extractErrorMessage("Claude Code", result.output))
        }
        return result.output
    }

    fun runPiQuery(prompt: String, basePath: Path): String {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        val command = mutableListOf(
            piCommand(), "-p", prompt, "--no-session"
        )
        val model = settings.piCommitMessageModel.trim()
        if (model.isNotEmpty()) command += listOf("--model", model)
        val result = runProcess(command, basePath, PI_TIMEOUT_SECONDS)
        if (result.exitCode != 0) {
            throw AiCliException(extractErrorMessage("Pi", result.output))
        }
        return result.output
    }

    fun runOpencodeQuery(
        prompt: String,
        basePath: Path,
        project: Project,
        indicator: ProgressIndicator,
        showTerminal: Boolean = true
    ): String {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        val model = settings.commitMessageModel.trim()
        val command = mutableListOf(opencodeCommand(), "run", "--pure")
        if (model.isNotEmpty()) command += listOf("-m", model)
        command += listOf("--agent", "build", prompt)

        if (!showTerminal) {
            val result = runProcess(command, basePath, OPENCODE_TIMEOUT_SECONDS)
            if (result.exitCode != 0) {
                throw AiCliException(extractErrorMessage("OpenCode", result.output))
            }
            return result.output
        }

        val outputFile = Files.createTempFile("ai-cli-out-", ".txt")
        val shutdownHook = Thread { try { Files.deleteIfExists(outputFile) } catch (_: Throwable) {} }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        var process: Process? = null
        var readerThread: Thread? = null
        try {
            val displayCommand = command.map { shellQuote(it) }.joinToString(" ")
            Files.writeString(outputFile, "$displayCommand\n\n", StandardCharsets.UTF_8)
            val q = shellQuote(outputFile.toString())
            createCommitTerminal(project, basePath, "tail -n +1 -f $q")

            process = try {
                ProcessBuilder(command).directory(basePath.toFile()).redirectErrorStream(true).start()
            } catch (exception: Throwable) {
                throw AiCliException("Could not start opencode: ${exception.message}")
            }
            try { process!!.outputStream.close() } catch (_: Throwable) {}

            val output = StringBuilder()
            readerThread = Thread {
                process!!.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                        try {
                            Files.writeString(outputFile, "$line\n", StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                        } catch (_: Throwable) {}
                    }
                }
            }
            readerThread!!.isDaemon = true
            readerThread!!.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(OPENCODE_TIMEOUT_SECONDS)
            while (true) {
                indicator.checkCanceled()
                if (System.nanoTime() >= deadline) {
                    destroyProcessTree(process!!)
                    throw AiCliException("Timed out")
                }
                if (process!!.waitFor(PROCESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) break
            }
            readerThread!!.join()
            if (process!!.exitValue() != 0) {
                throw AiCliException(extractErrorMessage("OpenCode", output.toString()))
            }
            return output.toString()
        } finally {
            readerThread?.join(TimeUnit.SECONDS.toMillis(5))
            process?.let { destroyProcessTree(it) }
            try { Files.deleteIfExists(outputFile) } catch (_: Throwable) {}
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: IllegalStateException) {}
        }
    }

    fun runQuery(
        aiTool: String,
        prompt: String,
        basePath: Path,
        project: Project,
        indicator: ProgressIndicator,
        showTerminal: Boolean = true
    ): String {
        return when (aiTool) {
            "claude" -> runClaudeQuery(prompt, basePath)
            "pi" -> runPiQuery(prompt, basePath)
            else -> runOpencodeQuery(prompt, basePath, project, indicator, showTerminal)
        }
    }

    fun extractErrorMessage(tool: String, rawOutput: String): String {
        val stripped = ANSI_PATTERN.replace(rawOutput.take(ERROR_OUTPUT_LIMIT), "")
        val prioritizedLine = stripped.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("error.error=") || it.contains("message=\"stream error\"") }
        if (prioritizedLine != null) {
            val errorText = Regex("""error\.error\s*=\s*"((?:\\.|[^"\\])*)"""")
                .find(prioritizedLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { unescapeJsonString(it) }
                ?: Regex("""(?:error|message)\s*=\s*"((?:\\.|[^"\\])*)"""")
                    .findAll(prioritizedLine)
                    .map { it.groupValues[1] }
                    .firstOrNull { it != "stream error" }
                    ?.let { unescapeJsonString(it) }
            if (!errorText.isNullOrBlank()) {
                return "$tool failed: $errorText"
            }
        }
        val singleLine = stripped.replace(Regex("\\s+"), " ")
        val jsonMatch = Regex("""\{\s*"name"\s*:\s*"(\w+)"\s*,\s*"data"\s*:\s*\{[^}]*"message"\s*:\s*"([^"]*)"[^}]*"ref"\s*:\s*"([^"]*)"[^}]*\}""")
            .find(singleLine)
        if (jsonMatch != null) {
            val errorName = jsonMatch.groupValues[1]
            val message = jsonMatch.groupValues[2]
            val ref = jsonMatch.groupValues[3]
            return "$tool server error: $message [$errorName] ($ref)"
        }
        val usefulLines = stripped.lineSequence()
            .map { it.trim() }
            .filterNot { it.isBlank() }
            .joinToString(" | ")
            .take(500)
        return "$tool failed: $usefulLines"
    }

    fun unescapeJsonString(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index + 1 >= value.length) {
                result.append(char)
                index++
                continue
            }
            when (val escaped = value[index + 1]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val hexStart = index + 2
                    val hexEnd = hexStart + 4
                    if (hexEnd <= value.length) {
                        val codePoint = value.substring(hexStart, hexEnd).toIntOrNull(16)
                        if (codePoint != null) {
                            result.append(codePoint.toChar())
                            index += 6
                            continue
                        }
                    }
                    result.append("\\u")
                }
                else -> result.append(escaped)
            }
            index += 2
        }
        return result.toString()
    }

    private const val ERROR_OUTPUT_LIMIT = 600

    class AiCliException(message: String) : RuntimeException(message)
}
