// Commit message generation action - uses the checked files in the Commit panel to generate text via OpenCode / Claude Code
package io.github.q110.aiterminaltools.bridge

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.vcs.commit.CommitMessageUi
import com.intellij.vcs.commit.CommitWorkflowUi
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.UUID

class GenerateCommitMessageAction : AnAction(AllIcons.Debugger.Console) {
    /** The Commit panel toolbar action must enable/disable itself on the EDT based on the checked files. */
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val messageControl = event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        val workflowHandler = event.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
        val inCommitContext = workflowUi != null ||
            messageControl is CommitMessageI ||
            workflowHandler is CommitMessageI
        event.presentation.isVisible = project != null && inCommitContext

        val hasFiles = when {
            workflowUi != null ->
                workflowUi.getIncludedChanges().isNotEmpty() || workflowUi.getIncludedUnversionedFiles().isNotEmpty()
            messageControl is CheckinProjectPanel ->
                runCatching { messageControl.selectedChanges.isNotEmpty() }.getOrDefault(false)
            else -> false
        }
        event.presentation.isEnabled = project != null && inCommitContext && hasFiles
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val context = resolveCommitContext(event)
        val target = context.target
        if (target == null) {
            AiTerminalBridgeService.notify(project, "Could not find the Commit panel context.", NotificationType.WARNING)
            return
        }
        if (context.changes.isEmpty() && context.unversionedFiles.isEmpty()) {
            AiTerminalBridgeService.notify(project, "Please check the files you want to commit first.", NotificationType.WARNING)
            return
        }

        val currentMessage = target.read().trim()
        if (currentMessage.isNotEmpty() && !confirmReplaceCommitMessage(project)) {
            return
        }

        val settings = AiTerminalToolsSettings.getInstance().getState()
        val commitMessageAiTool = normalizedCommitMessageAiTool(settings.commitMessageAiTool)
        target.startLoading()
        // AI CLI calls and git diff collection can be slow, so run them in a background task to avoid blocking the Commit UI.
        GenerateCommitMessageTask(
            project,
            target,
            context.changes,
            context.unversionedFiles,
            commitMessageAiTool
        ).queue()
    }

    private fun confirmReplaceCommitMessage(project: Project): Boolean {
        return Messages.showYesNoDialog(
            project,
            "The current area already has content. Replace it?",
            "Generate Commit Message",
            "Replace",
            "Cancel",
            Messages.getQuestionIcon()
        ) == Messages.YES
    }

    /** The background task collects changes, calls the AI CLI, and writes back to the Commit Message input on the EDT. */
    private class GenerateCommitMessageTask(
        project: Project,
        private val target: CommitMessageTarget,
        private val includedChanges: List<Change>,
        private val includedUnversionedFiles: List<FilePath>,
        private val commitMessageAiTool: String
    ) : Task.Backgroundable(project, commitMessageTaskTitle(commitMessageAiTool), true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                indicator.text = "Collecting selected changes"
                val changeSummary = CommitChangeSummary(project, includedChanges, includedUnversionedFiles).build()
                indicator.checkCanceled()

                indicator.text = "Running ${commitMessageCommandName(commitMessageAiTool)}"
                val generatedMessage = commitMessageGenerator(project, changeSummary, commitMessageAiTool).generate(indicator)
                indicator.checkCanceled()

                invokeOnEdt {
                    target.write(generatedMessage)
                    target.focus()
                    AiTerminalBridgeService.notify(project, "Commit message generated", NotificationType.INFORMATION)
                }
            } catch (exception: ProcessCanceledException) {
                throw exception
            } catch (exception: CommitMessageGenerationException) {
                invokeOnEdt {
                    AiTerminalBridgeService.notify(project, exception.message ?: "Failed to generate the commit message", NotificationType.WARNING)
                }
            } catch (exception: Throwable) {
                invokeOnEdt {
                    AiTerminalBridgeService.notify(project, "Failed to generate the commit message: ${exception.message}", NotificationType.WARNING)
                }
            } finally {
                invokeOnEdt {
                    target.stopLoading()
                }
            }
        }

        private fun invokeOnEdt(action: () -> Unit) {
            ApplicationManager.getApplication().invokeLater(
                {
                    if (!project.isDisposed && target.isActive()) {
                        action()
                    }
                },
                ModalityState.any()
            )
        }
    }

    /**
     * Resolves the active commit context across both the new Commit workflow and the legacy
     * commit dialog, returning a unified message target plus the selected changes.
     */
    private fun resolveCommitContext(event: AnActionEvent): ResolvedCommitContext {
        var target: CommitMessageTarget? = null
        var changes: List<Change> = emptyList()
        var unversionedFiles: List<FilePath> = emptyList()

        // New Commit workflow (typed API with loading state and unversioned file support).
        val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        if (workflowUi != null) {
            target = WorkflowCommitMessageTarget(workflowUi)
            changes = workflowUi.getIncludedChanges().toList()
            unversionedFiles = workflowUi.getIncludedUnversionedFiles().toList()
        }

        // Legacy commit dialog / modal commit handler.
        if (target == null) {
            val workflowHandler = event.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
            if (workflowHandler is CommitMessageI) {
                target = LegacyCommitMessageTarget(workflowHandler)
            }
        }
        val messageControl = event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        if (target == null && messageControl is CommitMessageI) {
            target = LegacyCommitMessageTarget(messageControl)
        }
        if (changes.isEmpty() && messageControl is CheckinProjectPanel) {
            changes = runCatching { messageControl.selectedChanges.toList() }.getOrDefault(emptyList())
        }

        // Fallbacks for the change selection used by older commit views.
        if (changes.isEmpty()) {
            val selectedChanges = event.getData(VcsDataKeys.SELECTED_CHANGES)
            if (!selectedChanges.isNullOrEmpty()) {
                changes = selectedChanges.toList()
            }
        }
        if (changes.isEmpty()) {
            val allChanges = event.getData(VcsDataKeys.CHANGES)
            if (!allChanges.isNullOrEmpty()) {
                changes = allChanges.toList()
            }
        }

        return ResolvedCommitContext(target, changes, unversionedFiles)
    }

    private data class ResolvedCommitContext(
        val target: CommitMessageTarget?,
        val changes: List<Change>,
        val unversionedFiles: List<FilePath>
    )

    /** Unifies writing the generated message into either the new workflow UI or a legacy commit panel. */
    private interface CommitMessageTarget {
        fun read(): String
        fun write(message: String)
        fun startLoading()
        fun stopLoading()
        fun focus()
        /** True while the target is still usable, so background callbacks can be skipped after the dialog closes. */
        fun isActive(): Boolean
    }

    /** New Commit workflow target backed by the typed CommitMessageUi (supports loading state). */
    private class WorkflowCommitMessageTarget(workflowUi: CommitWorkflowUi) : CommitMessageTarget {
        private val messageUi: CommitMessageUi = workflowUi.commitMessageUi
        private val disposable: CommitWorkflowUi = workflowUi

        override fun read(): String = messageUi.text
        override fun write(message: String) = messageUi.setText(message)
        override fun startLoading() = messageUi.startLoading()
        override fun stopLoading() = messageUi.stopLoading()
        override fun focus() = messageUi.focus()
        override fun isActive(): Boolean = !Disposer.isDisposed(disposable)
    }

    /** Legacy commit dialog target backed by CommitMessageI (setCommitMessage / getCommitMessage). */
    private class LegacyCommitMessageTarget(private val panel: CommitMessageI) : CommitMessageTarget {
        // The concrete panel classes (e.g. CheckinProjectPanel) expose commit-message getters/setters that are
        // not declared on the CommitMessageI interface, so resolve them reflectively for version robustness.
        override fun read(): String = invokeStringGetter(panel, "getCommitMessage", "getComment", "getText")
        override fun write(message: String) {
            invokeStringSetter(panel, "setCommitMessage", message)
        }

        // Legacy panels do not expose a loading indicator; these are no-ops.
        override fun startLoading() = Unit
        override fun stopLoading() = Unit
        override fun focus() = Unit
        override fun isActive(): Boolean = true
    }

    private interface CommitMessageGenerator {
        fun generate(indicator: ProgressIndicator): String
    }

    /** Assemble the actually checked files in the Commit panel into prompt context. */
    private class CommitChangeSummary(
        private val project: Project,
        private val includedChanges: List<Change>,
        private val includedUnversionedFiles: List<FilePath>
    ) {
        fun build(): String {
            val changedFiles = includedChanges.mapNotNull { change -> change.toIncludedFile(project) }
            val unversionedFiles = includedUnversionedFiles.map { filePath -> filePath.toIncludedFile(project, "New file") }
            val allFiles = changedFiles + unversionedFiles
            if (allFiles.isEmpty()) {
                throw CommitMessageGenerationException("No files are available for generating a commit message.")
            }

            val diffs = computeDiffs(allFiles)
            val sb = StringBuilder()
            sb.appendLine("Checked files:")
            for (file in allFiles) {
                sb.appendLine("- ${file.status}: ${file.relativePath}")
            }
            sb.appendLine()
            sb.appendLine("Per-file changes (only the changes below are used to generate the commit message):")
            for (file in allFiles) {
                val diff = diffs[file.relativePath] ?: ""
                if (diff.isNotEmpty()) {
                    sb.appendLine("=== ${file.relativePath} ===")
                    sb.appendLine(diff)
                    sb.appendLine()
                }
            }
            return sb.toString()
        }

        private fun computeDiffs(files: List<IncludedFile>): Map<String, String> {
            val diffs = linkedMapOf<String, String>()
            for (file in files) {
                // New files do not have a git diff; read the file contents directly. Other states prefer git diff.
                val content = when (file.status) {
                    "New" -> readFileContent(file.absolutePath)
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
        private val changeSummary: String
    ) : CommitMessageGenerator {
        override fun generate(indicator: ProgressIndicator): String {
            val basePath = project.basePath
                ?: throw CommitMessageGenerationException("The project has no available working directory.")
            val settings = AiTerminalToolsSettings.getInstance().getState()
            val fullPrompt = buildPrompt(changeSummary)
            // Write the OpenCode agent configuration into a temporary XDG_CONFIG_HOME to avoid polluting the user's global config.
            val configHome = createOpenCodeConfigHome(fullPrompt)
            val basePathPath = Path.of(basePath).toAbsolutePath().normalize()
            val sessionTitle = "Generate commit message ${UUID.randomUUID()}"
            try {
                val command = mutableListOf(
                    opencodeCommand(),
                    "run",
                    "--pure",
                    "--agent",
                    COMMIT_MESSAGE_AGENT,
                    "--dir",
                    basePath,
                    "--title",
                    sessionTitle
                )
                val model = settings.commitMessageModel.trim()
                if (model.isNotEmpty()) {
                    command += listOf("-m", model)
                }
                command += "Generate a commit message based on the system instructions."

                val result = runProcess(
                    command,
                    basePathPath,
                    OPENCODE_TIMEOUT_SECONDS,
                    indicator,
                    "opencode commit message generation timed out",
                    environment = mapOf("XDG_CONFIG_HOME" to configHome.toString())
                )
                if (result.exitCode != 0) {
                    throw CommitMessageGenerationException("opencode execution failed: ${result.output.take(ERROR_OUTPUT_LIMIT)}")
                }

                val message = cleanupOutput(result.output)
                if (message.isBlank()) {
                    throw CommitMessageGenerationException("opencode did not return a usable commit message")
                }
                return message
            } finally {
                // opencode run creates a temporary session; try to clean it up by matching the title and directory.
                deleteSessionQuietly(sessionTitle, basePathPath)
                configHome.toFile().deleteRecursively()
            }
        }

        private fun deleteSessionQuietly(sessionTitle: String, basePath: Path) {
            try {
                val listResult = runProcess(
                    listOf(opencodeCommand(), "session", "list", "--format", "json", "--max-count", "20"),
                    basePath,
                    OPENCODE_SESSION_CLEANUP_TIMEOUT_SECONDS
                )
                if (listResult.exitCode != 0) return

                val sessionId = parseOpenCodeSessions(listResult.output)
                    .firstOrNull { session ->
                        session.title == sessionTitle && pathsEqual(session.directory, basePath)
                    }
                    ?.id
                    ?: return

                runProcess(
                    listOf(opencodeCommand(), "session", "delete", sessionId),
                    basePath,
                    OPENCODE_SESSION_CLEANUP_TIMEOUT_SECONDS
                )
            } catch (_: Throwable) {
            }
        }
}

    private class ClaudeCodeCommitMessageGenerator(
        private val project: Project,
        private val changeSummary: String
    ) : CommitMessageGenerator {
        override fun generate(indicator: ProgressIndicator): String {
            val basePath = project.basePath
                ?: throw CommitMessageGenerationException("The project has no available working directory.")
            val settings = AiTerminalToolsSettings.getInstance().getState()
            val prompt = buildPrompt(changeSummary)
            val basePathPath = Path.of(basePath).toAbsolutePath().normalize()
            val command = mutableListOf(
                claudeCommand(),
                "-p",
                "Generate a commit message based on the changes provided via standard input.",
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
                "claude commit message generation timed out"
            )
            if (result.exitCode != 0) {
                throw CommitMessageGenerationException("claude execution failed: ${result.output.take(ERROR_OUTPUT_LIMIT)}")
            }

            val message = cleanupOutput(result.output)
            if (message.isBlank()) {
                throw CommitMessageGenerationException("claude did not return a usable commit message")
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

    private data class OpenCodeSession(val id: String, val title: String, val directory: String)

    private class CommitMessageGenerationException(message: String) : RuntimeException(message)

    companion object {
        private const val COMMIT_MESSAGE_AGENT = "commit-message"
        private const val ERROR_OUTPUT_LIMIT = 600
        private const val OPENCODE_TIMEOUT_SECONDS = 120L
        private const val CLAUDE_TIMEOUT_SECONDS = 120L
        private const val OPENCODE_SESSION_CLEANUP_TIMEOUT_SECONDS = 15L
        private const val PROCESS_POLL_INTERVAL_MS = 200L
        private const val COMMIT_MESSAGE_AI_TOOL_OPENCODE = "opencode"
        private const val COMMIT_MESSAGE_AI_TOOL_CLAUDE = "claude"
        private val ANSI_PATTERN = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")

        /** Reads the commit text from a legacy panel by trying the getter names exposed by different impls. */
        private fun invokeStringGetter(target: Any, vararg methodNames: String): String {
            for (name in methodNames) {
                val value = runCatching {
                    target.javaClass.getMethod(name).invoke(target) as? String
                }.getOrNull()
                if (value != null) return value
            }
            return ""
        }

        /** Writes the commit text into a legacy panel via its setCommitMessage method. */
        private fun invokeStringSetter(target: Any, methodName: String, value: String) {
            runCatching {
                target.javaClass.getMethod(methodName, String::class.java).invoke(target, value)
            }
        }

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
            changeSummary: String,
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
                Generate a commit message from the following changes.

                Output only the commit text. Do not explain, analyze, or use Markdown code blocks.
                Use "- " bullet points.
                Do not invent anything that is not present in the changes.

                Base requirements:
                $basePrompt

                Additional requirements:
                $additionalPrompt

                Changes:
                $summary
            """.trimIndent()
        }

        private fun cleanupOutput(output: String): String {
            // Compatible with ANSI control codes, Markdown fences, and Claude quote prefixes in CLI output.
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
        }

        private fun Change.toIncludedFile(project: Project): IncludedFile? {
            val filePath = afterRevision?.file ?: beforeRevision?.file ?: return null
            val status = when {
                beforeRevision == null -> "New"
                afterRevision == null -> "Deleted"
                isMoved || isRenamed -> "Renamed/Moved"
                else -> "Modified"
            }
            return filePath.toIncludedFile(project, status)
        }

        private fun FilePath.toIncludedFile(project: Project, status: String): IncludedFile {
            val absolutePath = ioFile.toPath().toAbsolutePath().normalize()
            val gitRoot = findGitRoot(absolutePath, project)
            val relativePath = try {
                gitRoot.relativize(absolutePath).toString().replace(File.separatorChar, '/')
            } catch (_: IllegalArgumentException) {
                absolutePath.toString().replace(File.separatorChar, '/')
            }
            return IncludedFile(gitRoot, absolutePath, relativePath, status)
        }

        private fun findGitRoot(path: Path, project: Project): Path {
            val start = if (Files.isDirectory(path)) path else path.parent
            var current = start
            while (current != null) {
                if (Files.exists(current.resolve(".git"))) {
                    return current
                }
                current = current.parent
            }
            return Path.of(project.basePath ?: ".").toAbsolutePath().normalize()
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
                throw CommitMessageGenerationException("Cannot start command ${command.firstOrNull().orEmpty()}: ${exception.message}")
            }
            try {
                // Commands without stdin should have their input stream closed to avoid the child process waiting for input.
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
                    throw CommitMessageGenerationException("Cancelled")
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
                throw CommitMessageGenerationException("Cannot start command ${command.firstOrNull().orEmpty()}: ${exception.message}")
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
                    throw CommitMessageGenerationException("Cancelled")
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

        private fun parseOpenCodeSessions(json: String): List<OpenCodeSession> {
            // Avoid adding a JSON library; lightly parse the flat OpenCode session list output.
            return Regex("""\{[^{}]*\}""")
                .findAll(json)
                .mapNotNull { match ->
                    val item = match.value
                    val id = extractJsonStringField(item, "id") ?: return@mapNotNull null
                    val title = extractJsonStringField(item, "title") ?: return@mapNotNull null
                    val directory = extractJsonStringField(item, "directory") ?: return@mapNotNull null
                    OpenCodeSession(id, title, directory)
                }
                .toList()
        }

        private fun extractJsonStringField(jsonObject: String, field: String): String? {
            val pattern = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            return pattern.find(jsonObject)?.groupValues?.getOrNull(1)?.let(::unescapeJsonString)
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

        private fun pathsEqual(directory: String, basePath: Path): Boolean {
            return try {
                val sessionPath = Path.of(directory).toAbsolutePath().normalize().toString()
                val expectedPath = basePath.toAbsolutePath().normalize().toString()
                sessionPath.equals(expectedPath, ignoreCase = SystemInfo.isWindows)
            } catch (_: Throwable) {
                directory.equals(basePath.toString(), ignoreCase = SystemInfo.isWindows)
            }
        }

        private fun createOpenCodeConfigHome(fullPrompt: String): Path {
            // Enable only the capabilities needed for model generation; disallow read/write/command tools for commit message generation.
            val configHome = Files.createTempDirectory("opencode-commit-message-config")
            val configDir = configHome.resolve("opencode")
            Files.createDirectories(configDir)
            Files.writeString(
                configDir.resolve("opencode.json"),
                commitMessageAgentConfig(fullPrompt),
                StandardCharsets.UTF_8
            )
            return configHome
        }

        private fun commitMessageAgentConfig(fullPrompt: String): String {
            return """
                {
                  "agent": {
                    "$COMMIT_MESSAGE_AGENT": {
                      "description": "Commit message generator",
                      "mode": "primary",
                      "prompt": ${jsonString(fullPrompt)},
                      "tools": {
                        "invalid": false,
                        "skill": false,
                        "question": false,
                        "bash": false,
                        "read": false,
                        "glob": false,
                        "grep": false,
                        "edit": false,
                        "write": false,
                        "task": false,
                        "webfetch": false,
                        "websearch": false,
                        "todowrite": false
                      }
                    }
                  }
                }
            """.trimIndent()
        }

        private fun jsonString(value: String): String {
            return buildString {
                append('"')
                value.forEach { char ->
                    when (char) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(char)
                    }
                }
                append('"')
            }
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
