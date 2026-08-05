package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.codeInsight.hint.HintManager

class ExplainSelectionWithAiAction : AbstractSelectionAiAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel.selectedText
        if (selection.isNullOrEmpty()) return

        val tool = aiTool()
        val toolName = AiCliRunner.toolDisplayName(tool)
        val prompt = "Shortly explain:\n${buildCodeContext(project, editor, selection)}"
        queueSelectionTask(project, editor, tool, prompt, "Explaining selection with $toolName") { cleaned ->
            if (editor.component.isShowing) {
                HintManager.getInstance().showInformationHint(editor, cleaned)
            } else {
                AiTerminalBridgeService.notify(project, cleaned, NotificationType.INFORMATION)
            }
        }
    }
}
