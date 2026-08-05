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
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.vcs.commit.CommitMessageUi
import com.intellij.vcs.commit.CommitWorkflowUi
import io.github.q110.aiterminaltools.ProjectBasePath
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class GenerateCommitMessageAction : AnAction(AllIcons.Debugger.Console) {
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
        val aiTool = AiCliRunner.normalizedCommitMessageAiTool(settings.commitMessageAiTool)
        val toolName = AiCliRunner.toolDisplayName(aiTool)
        commitMessageUi.startLoading()
        GenerateCommitMessageTask(project, workflowUi, commitMessageUi, includedChanges, includedUnversionedFiles, aiTool, toolName).queue()
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

    private class GenerateCommitMessageTask(
        project: Project,
        private val workflowUi: CommitWorkflowUi,
        private val commitMessageUi: CommitMessageUi,
        private val includedChanges: List<Change>,
        private val includedUnversionedFiles: List<FilePath>,
        private val aiTool: String,
        private val toolName: String
    ) : Task.Backgroundable(project, "Generating $toolName Commit Message", false) {
        override fun run(indicator: ProgressIndicator) {
            try {
                indicator.text = "Collecting selected changes"
                val changeSummary = CommitChangeSummary(project, includedChanges, includedUnversionedFiles).build()
                indicator.checkCanceled()

                val prompt = buildPrompt(changeSummary.prompt)
                indicator.text = "Running $toolName"
                val rawOutput = AiCliRunner.runQuery(aiTool, prompt, changeSummary.gitRoot, project, indicator, showTerminal = true)
                indicator.checkCanceled()

                val message = cleanupOutput(rawOutput)
                if (message.isBlank()) {
                    throw CommitMessageGenerationException("$toolName returned no usable commit message")
                }

                invokeOnEdt {
                    commitMessageUi.setText(message)
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

    private data class CommitMessageSummary(val prompt: String, val gitRoot: Path)

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

    private data class IncludedFile(
        val gitRoot: Path,
        val absolutePath: Path,
        val relativePath: String,
        val status: String
    )

    private class CommitMessageGenerationException(message: String) : RuntimeException(message)

    companion object {
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

        private fun cleanupOutput(output: String): String {
            val lines = output
                .replace(AiCliRunner.ANSI_PATTERN, "")
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
    }
}
