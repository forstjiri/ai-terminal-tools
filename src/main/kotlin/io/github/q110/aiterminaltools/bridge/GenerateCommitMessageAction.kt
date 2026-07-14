// Commit message generation action — uses selected Commit panel files to generate a message with OpenCode / Claude Code.
package io.github.q110.aiterminaltools.bridge

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.content.Content
import com.intellij.vcs.commit.CommitMessageUi
import com.intellij.vcs.commit.CommitWorkflowUi
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import io.github.q110.aiterminaltools.ProjectBasePath
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

class GenerateCommitMessageAction : AnAction(AllIcons.Debugger.Console) {
    /** The Commit panel toolbar action is enabled or disabled on the EDT based on selected files. */
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override fun update(event: AnActionEvent) {
        val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val hasIncludedFiles = workflowUi != null &&
            (workflowUi.getIncludedChanges().isNotEmpty() || workflowUi.getIncludedUnversionedFiles().isNotEmpty())
        event.presentation.isEnabled = event.project != null && hasIncludedFiles
        event.presentation.isVisible = event.project != null && workflowUi != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        if (workflowUi == null) {
            AiTerminalBridgeService.notify(project, "The Commit panel context was not found.", NotificationType.WARNING)
            return
        }

        val includedChanges = workflowUi.getIncludedChanges()
        val includedUnversionedFiles = workflowUi.getIncludedUnversionedFiles()
        if (includedChanges.isEmpty() && includedUnversionedFiles.isEmpty()) {
            AiTerminalBridgeService.notify(project, "Select files to commit first.", NotificationType.WARNING)
            return
        }

        val commitMessageUi = workflowUi.commitMessageUi
        val currentMessage = commitMessageUi.text.trim()
        if (currentMessage.isNotEmpty() && !confirmReplaceCommitMessage(project)) {
            return
        }

        val settings = AiTerminalToolsSettings.getInstance().getState()
        val commitMessageAiTool = normalizedCommitMessageAiTool(settings.commitMessageAiTool)
        commitMessageUi.startLoading()
        GenerateCommitMessageTask(
            project,
            workflowUi,
            commitMessageUi,
            includedChanges,
            includedUnversionedFiles,
            commitMessageAiTool
        ).queue()
    }

    private fun confirmReplaceCommitMessage(project: Project): Boolean {
        return Messages.showYesNoDialog(
            project,
            "The current area already contains text. Replace it?",
            "Generate Commit Message",
            "Replace",
            "Cancel",
            Messages.getQuestionIcon()
        ) == Messages.YES
    }

    /** Collects changes, calls the AI CLI, and writes the result to the Commit Message field on the EDT. */
    private class GenerateCommitMessageTask(
        project: Project,
        private val workflowUi: CommitWorkflowUi,
        private val commitMessageUi: CommitMessageUi,
        private val includedChanges: List<Change>,
        private val includedUnversionedFiles: List<FilePath>,
        private val commitMessageAiTool: String
    ) : Task.Backgroundable(project, commitMessageTaskTitle(commitMessageAiTool), false) {
        override fun run(indicator: ProgressIndicator) {
            try {
                indicator.text = "Collecting selected changes"
                val changeSummary = CommitChangeSummary(project, includedChanges, includedUnversionedFiles).build()
                indicator.checkCanceled()

                indicator.text = "Running ${commitMessageCommandName(commitMessageAiTool)}"
                val generatedMessage = commitMessageGenerator(project, changeSummary, commitMessageAiTool).generate(indicator)
                indicator.checkCanceled()

                invokeOnEdt {
                    commitMessageUi.setText(generatedMessage)
                    commitMessageUi.focus()
                    AiTerminalBridgeService.notify(project, "Commit message generated", NotificationType.INFORMATION)
                }
            } catch (exception: ProcessCanceledException) {
                throw exception
            } catch (exception: CommitMessageGenerationException) {
                invokeOnEdt {
                    AiTerminalBridgeService.notify(project, exception.message ?: "Failed to generate commit message", NotificationType.WARNING)
                }
            } catch (exception: Throwable) {
                invokeOnEdt {
                    AiTerminalBridgeService.notify(project, "Failed to generate commit message: ${exception.message}", NotificationType.WARNING)
                }
            } finally {
                invokeOnEdt {
                    commitMessageUi.stopLoading()
                }
            }
        }

        private fun invokeOnEdt(action: () -> Unit) {
            ApplicationManager.getApplication().invokeLater(
                {
                    if (!project.isDisposed && !Disposer.isDisposed(workflowUi)) {
                        action()
                    }
                },
                ModalityState.any()
            )
        }
    }

    private interface CommitMessageGenerator {
        fun generate(indicator: ProgressIndicator): String
    }

    private data class CommitMessageSummary(val prompt: String, val gitRoot: Path)

    /** Builds prompt context from the files actually selected in the Commit panel. */
    private class CommitChangeSummary(
        private val project: Project,
        private val includedChanges: List<Change>,
        private val includedUnversionedFiles: List<FilePath>
    ) {
        fun build(): CommitMessageSummary {
            val changedFiles = includedChanges.map { change ->
                change.toIncludedFile(project)
                    ?: throw CommitMessageGenerationException("Unable to determine the selected change's file path.")
            }
            val unversionedFiles = includedUnversionedFiles.map { filePath -> filePath.toIncludedFile(project, "New file") }
            val allFiles = changedFiles + unversionedFiles
            if (allFiles.isEmpty()) {
                throw CommitMessageGenerationException("No files are available for commit message generation.")
            }

            val roots = allFiles.map { it.gitRoot }.distinctBy { root ->
                if (SystemInfo.isWindows) root.toString().lowercase() else root.toString()
            }
            if (roots.size != 1) {
                throw CommitMessageGenerationException(
                    "Selected files must belong to the same Git root; found: ${roots.joinToString()}"
                )
            }
            val commonGitRoot = roots.single()
            val diffs = computeDiffs(allFiles)
            val sb = StringBuilder()
            sb.appendLine("Selected files:")
            for (file in allFiles) {
                sb.appendLine("- ${file.status}: ${file.relativePath}")
            }
            sb.appendLine()
            sb.appendLine("Changes in each file (analyze only these changes to generate the commit message):")
            for (file in allFiles) {
                val diff = diffs[file.relativePath] ?: ""
                if (diff.isNotEmpty()) {
                    sb.appendLine("=== ${file.relativePath} ===")
                    sb.appendLine(diff)
                    sb.appendLine()
                }
            }
            return CommitMessageSummary(sb.toString(), commonGitRoot)
        }

        private fun computeDiffs(files: List<IncludedFile>): Map<String, String> {
            val diffs = linkedMapOf<String, String>()
            for (file in files) {
                val content = when (file.status) {
                    "Added", "New file" -> readFileContent(file.absolutePath)
                    "Deleted" -> gitDiff(file.gitRoot, file.relativePath)
                    else -> gitDiff(file.gitRoot, file.relativePath)
                }
                if (!content.isNullOrBlank()) {
                    diffs[file.relativePath] = content
                }
            }
            return diffs
        }

        private fun gitDiff(gitRoot: Path, relativePath: String): String? {
            return try {
                val process = ProcessBuilder(
                    listOf("git", "diff", "--no-color", "--no-ext-diff", "--", relativePath)
                )
                    .directory(gitRoot.toFile())
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                process.waitFor(30, TimeUnit.SECONDS)
                output.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }
        }

        private fun readFileContent(path: Path): String? {
            return try {
                Files.readString(path, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private class OpenCodeCommitMessageGenerator(
        private val project: Project,
        private val changeSummary: CommitMessageSummary
    ) : CommitMessageGenerator {
        override fun generate(indicator: ProgressIndicator): String {
            val basePathPath = changeSummary.gitRoot
            val settings = AiTerminalToolsSettings.getInstance().getState()
            val fullPrompt = buildPrompt(changeSummary.prompt)
            val model = settings.commitMessageModel.trim()
            val baseCommand = opencodeCommand()

            indicator.text = "Generating commit message with OpenCode build agent"
            val outputFile = Files.createTempFile("ai-commit-out-", ".txt")
            val shutdownHook = Thread { deleteTempFile(outputFile) }
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            var process: Process? = null
            var readerThread: Thread? = null
            var terminal: CommitTerminal? = null
            try {
                val command = mutableListOf(baseCommand, "run", "--pure")
                if (model.isNotEmpty()) command += listOf("-m", model)
                command += listOf("--agent", "build", fullPrompt)
                val displayCommand = command.map { shellQuote(it) }.joinToString(" ")

                Files.writeString(outputFile, "$displayCommand\n\n", StandardCharsets.UTF_8)
                val q = shellQuote(outputFile.toString())
                terminal = createCommitTerminal(project, basePathPath, "tail -n +1 -f $q")

                process = try {
                    ProcessBuilder(command)
                        .directory(basePathPath.toFile())
                        .redirectErrorStream(true)
                        .start()
                } catch (exception: Throwable) {
                    throw CommitMessageGenerationException("Could not start opencode: ${exception.message}")
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
                        throw CommitMessageGenerationException("Timed out while generating the commit message with opencode")
                    }
                    if (process!!.waitFor(PROCESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) break
                }
                readerThread!!.join()

                try {
                    Files.writeString(outputFile, "\n--- Done (exit ${process!!.exitValue()}) ---\n",
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND)
                } catch (_: Throwable) {}
                if (process!!.exitValue() != 0) {
                    throw CommitMessageGenerationException(extractErrorMessage("OpenCode", output.toString()))
                }
                val message = cleanupOutput(output.toString())
                if (message.isBlank()) {
                    throw CommitMessageGenerationException("opencode returned no usable commit message")
                }
                return message
            } catch (canceled: ProcessCanceledException) {
                process?.let { destroyProcessTree(it) }
                throw canceled
            } finally {
                readerThread?.join(TimeUnit.SECONDS.toMillis(5))
                process?.let { destroyProcessTree(it) }
                deleteTempFile(outputFile)
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook)
                } catch (_: IllegalStateException) {}
            }
        }
    }

    private class ClaudeCodeCommitMessageGenerator(
        private val project: Project,
        private val changeSummary: CommitMessageSummary
    ) : CommitMessageGenerator {
        override fun generate(indicator: ProgressIndicator): String {
            val basePathPath = changeSummary.gitRoot
            val settings = AiTerminalToolsSettings.getInstance().getState()
            val prompt = buildPrompt(changeSummary.prompt)
            val command = mutableListOf(
                claudeCommand(),
                "-p",
                "Generate an English commit message from the changes above.",
                "--output-format",
                "text",
                "--no-session-persistence"
            )
            val model = settings.claudeCommitMessageModel.trim()
            if (model.isNotEmpty()) {
                command += listOf("--model", model)
            }

            val result = runProcessWithStdin(
                command,
                basePathPath,
                prompt,
                CLAUDE_TIMEOUT_SECONDS,
                indicator,
                "Timed out while generating the commit message with claude"
            )
            if (result.exitCode != 0) {
                throw CommitMessageGenerationException(extractErrorMessage("Claude Code", result.output))
            }

            val message = cleanupOutput(result.output)
            if (message.isBlank()) {
                throw CommitMessageGenerationException("claude returned no usable commit message")
            }
            return message
        }
    }

    private data class IncludedFile(
        val gitRoot: Path,
        val absolutePath: Path,
        val relativePath: String,
        val status: String
    )

    private data class ProcessResult(val exitCode: Int, val output: String)

    private data class CommitTerminal(val disposable: Disposable, val content: Content)

    private class CommitMessageGenerationException(message: String) : RuntimeException(message)

    companion object {
        private const val ERROR_OUTPUT_LIMIT = 600
        private const val OPENCODE_TIMEOUT_SECONDS = 120L
        private const val CLAUDE_TIMEOUT_SECONDS = 120L
        private const val PROCESS_POLL_INTERVAL_MS = 200L
        private const val COMMIT_MESSAGE_AI_TOOL_OPENCODE = "opencode"
        private const val COMMIT_MESSAGE_AI_TOOL_CLAUDE = "claude"
        private val ANSI_PATTERN = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")
        private val OSC_PATTERN = Regex("\\u001B\\][^\\u0007]*\\u0007|\\u001B\\\\\\][^\\u001B]*\\u001B\\\\")

        private fun normalizedCommitMessageAiTool(aiTool: String): String {
            return if (aiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
                COMMIT_MESSAGE_AI_TOOL_CLAUDE
            } else {
                COMMIT_MESSAGE_AI_TOOL_OPENCODE
            }
        }

        private fun commitMessageTaskTitle(aiTool: String): String {
            return "Generating ${commitMessageToolDisplayName(aiTool)} Commit Message"
        }

        private fun commitMessageCommandName(aiTool: String): String {
            return if (aiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) "claude" else "opencode"
        }

        private fun commitMessageToolDisplayName(aiTool: String): String {
            return if (aiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) "Claude Code" else "OpenCode"
        }

        private fun commitMessageGenerator(
            project: Project,
            changeSummary: CommitMessageSummary,
            aiTool: String
        ): CommitMessageGenerator {
            return if (aiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
ClaudeCodeCommitMessageGenerator(project, changeSummary)
            } else {
                OpenCodeCommitMessageGenerator(project, changeSummary)
            }
        }

        private fun buildPrompt(summary: String): String {
            val settings = AiTerminalToolsSettings.getInstance().getState()
            val basePrompt = AiTerminalToolsSettings.StateData.DEFAULT_COMMIT_MESSAGE_BASE_PROMPT
            val additionalPrompt = settings.resolvedCommitMessageAdditionalPrompt()
            return """
                Generate the final commit message in natural ASCII English from the following changes.
                The commit message itself must be in English, even if the source changes or other instructions use another language.

                Output only the commit message. Do not explain, show analysis, or use Markdown code fences.
                Use one bullet per line with "- ".
                Do not invent content absent from the changes.

                Base requirements:
                $basePrompt

                Additional requirements:
                $additionalPrompt

                Changes:
                $summary
            """.trimIndent()
        }

        private fun extractErrorMessage(tool: String, rawOutput: String): String {
            val stripped = ANSI_PATTERN.replace(rawOutput.take(ERROR_OUTPUT_LIMIT), "")
            val prioritizedLine = stripped.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.contains("error.error=") || it.contains("message=\"stream error\"") }
            if (prioritizedLine != null) {
                val errorText = Regex("""error\.error\s*=\s*"((?:\\.|[^"\\])*)"""")
                    .find(prioritizedLine)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::unescapeJsonString)
                    ?: Regex("""(?:error|message)\s*=\s*"((?:\\.|[^"\\])*)"""")
                        .findAll(prioritizedLine)
                        .map { it.groupValues[1] }
                        .firstOrNull { it != "stream error" }
                        ?.let(::unescapeJsonString)
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

        private fun cleanupOutput(output: String): String {
            val lines = output
                .replace(ANSI_PATTERN, "")
                .lineSequence()
                .map { it.trimEnd() }
                .filterNot { it.trim().equals("```", ignoreCase = true) }
                .filterNot { it.trimStart().startsWith("> ") }
                .toList()
            val trailingBulletLines = lines
                .asReversed()
                .dropWhile { it.isBlank() }
                .takeWhile { it.isBlank() || it.trimStart().startsWith("- ") }
                .asReversed()
                .filter { it.trimStart().startsWith("- ") }
            return (trailingBulletLines.ifEmpty { lines })
                .joinToString("\n")
                .trim()
                .lineSequence()
                .map { it.trimStart().removePrefix("- ") }
                .joinToString("\n")
                .trim()
        }

        private fun shellQuote(value: String): String {
            return if (SystemInfo.isWindows) {
                "'${value.replace("'", "''")}'"
            } else {
                "'${value.replace("'", "'\\''")}'"
            }
        }

        private fun createCommitTerminal(project: Project, workingDirectory: Path, command: String): CommitTerminal {
            var result: CommitTerminal? = null
            var failure: Throwable? = null
            ApplicationManager.getApplication().invokeAndWait({
                try {
                    val manager = TerminalToolWindowManager.getInstance(project)
                    val toolWindow = manager.toolWindow
                        ?: throw CommitMessageGenerationException("Terminal tool window was not found.")
                    val startupDisposable = Disposer.newDisposable("AI Commit Terminal")
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
                    content.displayName = "AI Commit"
                    toolWindow.activate(Runnable {
                        ShellTerminalWidget.toShellJediTermWidgetOrThrow(widget).executeCommand(command)
                    }, true, true)
                    result = CommitTerminal(startupDisposable, content)
                } catch (exception: Throwable) {
                    failure = exception
                }
            }, ModalityState.defaultModalityState())
            failure?.let { throw CommitMessageGenerationException("Could not open commit terminal: ${it.message}") }
            return result ?: throw CommitMessageGenerationException("Could not open commit terminal")
        }

        private fun closeCommitTerminal(terminal: CommitTerminal) {
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

        private fun readFileContent(path: Path): String? {
            return try {
                Files.readString(path, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
        }

        private fun deleteTempFile(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (_: Throwable) {
            }
        }

        private fun Change.toIncludedFile(project: Project): IncludedFile? {
            val filePath = afterRevision?.file ?: beforeRevision?.file ?: return null
            val status = when {
                beforeRevision == null -> "Added"
                afterRevision == null -> "Deleted"
                isMoved || isRenamed -> "Renamed/Moved"
                else -> "Modified"
            }
            return filePath.toIncludedFile(project, status)
        }

        private fun FilePath.toIncludedFile(project: Project, status: String): IncludedFile {
            val absolutePath = ioFile.toPath().toAbsolutePath().normalize()
            val gitRoot = findGitRoot(absolutePath)
                ?: throw CommitMessageGenerationException("Could not find the file's Git root: $absolutePath")
            val relativePath = try {
                gitRoot.relativize(absolutePath).toString().replace(File.separatorChar, '/')
            } catch (_: IllegalArgumentException) {
                absolutePath.toString().replace(File.separatorChar, '/')
            }
            return IncludedFile(gitRoot, absolutePath, relativePath, status)
        }

        private fun findGitRoot(path: Path): Path? {
            var current = if (Files.isDirectory(path)) path else path.parent
            while (current != null) {
                val gitMarker = current.resolve(".git")
                if (Files.isDirectory(gitMarker) || Files.isRegularFile(gitMarker)) {
                    return try {
                        ProjectBasePath.requireValid(current)
                    } catch (_: IllegalStateException) {
                        null
                    }
                }
                current = current.parent
            }
            return null
        }

        private fun runProcess(
            command: List<String>,
            workingDirectory: Path,
            timeoutSeconds: Long,
            indicator: ProgressIndicator? = null,
            timeoutMessage: String? = null,
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
                throw CommitMessageGenerationException("Could not start command ${command.firstOrNull().orEmpty()}: ${exception.message}")
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
                if (indicator?.isCanceled == true) {
                    destroyProcessTree(process)
                    throw CommitMessageGenerationException("Canceled")
                }
                if (process.waitFor(PROCESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    break
                }
                if (System.nanoTime() >= deadline) {
                    destroyProcessTree(process)
                    throw CommitMessageGenerationException(timeoutMessage ?: "Command timed out: ${command.firstOrNull().orEmpty()}")
                }
            }
            readerThread.join(TimeUnit.SECONDS.toMillis(2))
            return ProcessResult(process.exitValue(), output.toString())
        }

        private fun destroyProcessTree(process: Process) {
            process.descendants().forEach { descendant ->
                try {
                    descendant.destroyForcibly()
                } catch (_: Throwable) {
                }
            }
            process.destroyForcibly()
        }

        private fun runProcessWithStdin(
            command: List<String>,
            workingDirectory: Path,
            stdinContent: String,
            timeoutSeconds: Long,
            indicator: ProgressIndicator? = null,
            timeoutMessage: String? = null
        ): ProcessResult {
            val process = try {
                ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start()
            } catch (exception: Throwable) {
                throw CommitMessageGenerationException("Could not start command ${command.firstOrNull().orEmpty()}: ${exception.message}")
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
                if (indicator?.isCanceled == true) {
                    destroyProcessTree(process)
                    throw CommitMessageGenerationException("Canceled")
                }
                if (process.waitFor(PROCESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    break
                }
                if (System.nanoTime() >= deadline) {
                    destroyProcessTree(process)
                    throw CommitMessageGenerationException(timeoutMessage ?: "Command timed out: ${command.firstOrNull().orEmpty()}")
                }
            }
            readerThread.join(TimeUnit.SECONDS.toMillis(2))
            return ProcessResult(process.exitValue(), output.toString())
        }

        private fun unescapeJsonString(value: String): String {
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

        private fun opencodeCommand(): String {
            return if (SystemInfo.isWindows) "opencode.cmd" else "opencode"
        }

        private fun claudeCommand(): String {
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
    }
}
