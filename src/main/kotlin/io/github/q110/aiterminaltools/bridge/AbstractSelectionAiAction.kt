package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.q110.aiterminaltools.filter.displayPath
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import java.nio.file.Path

abstract class AbstractSelectionAiAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        event.presentation.isVisible = event.project != null && hasSelection
        event.presentation.isEnabled = event.project != null && hasSelection
    }

    protected fun aiTool(): String {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        return AiCliRunner.normalizedCommitMessageAiTool(settings.commitMessageAiTool)
    }

    protected fun buildCodeContext(project: Project, editor: Editor, selection: String): String {
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)
        return if (virtualFile != null) {
            val selectionModel = editor.selectionModel
            val startLine = editor.document.getLineNumber(selectionModel.selectionStart) + 1
            val endOffsetForLine = (selectionModel.selectionEnd - 1).coerceAtLeast(selectionModel.selectionStart)
            val endLine = editor.document.getLineNumber(endOffsetForLine) + 1
            val filePath = displayPath(project, virtualFile)
            val lineRange = if (startLine == endLine) "$filePath:$startLine" else "$filePath:$startLine-$endLine"
            "File: $lineRange\n\nCode:\n$selection"
        } else {
            "Code:\n$selection"
        }
    }

    protected fun queueSelectionTask(
        project: Project,
        editor: Editor,
        aiTool: String,
        prompt: String,
        taskTitle: String,
        onResult: (String) -> Unit
    ) {
        val basePath = project.basePath?.let { Path.of(it) }
            ?: throw AiCliRunner.AiCliException("Project base path was not found")
        SelectionAiTask(project, editor, aiTool, prompt, basePath, taskTitle, onResult).queue()
    }

    private class SelectionAiTask(
        project: Project,
        private val editor: Editor,
        private val aiTool: String,
        private val prompt: String,
        private val basePath: Path,
        taskTitle: String,
        private val onResult: (String) -> Unit
    ) : Task.Backgroundable(project, taskTitle, true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val result = AiCliRunner.runQuery(aiTool, prompt, basePath, project, indicator, showTerminal = true)
                indicator.checkCanceled()
                val cleaned = result.replace(AiCliRunner.ANSI_PATTERN, "").trim()
                ApplicationManager.getApplication().invokeLater({
                    if (!editor.isDisposed) {
                        onResult(cleaned)
                    }
                }, ModalityState.any())
            } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            } catch (exception: Throwable) {
                ApplicationManager.getApplication().invokeLater({
                    AiTerminalBridgeService.notify(project, "Failed: ${exception.message}", NotificationType.WARNING)
                }, ModalityState.any())
            }
        }
    }
}
