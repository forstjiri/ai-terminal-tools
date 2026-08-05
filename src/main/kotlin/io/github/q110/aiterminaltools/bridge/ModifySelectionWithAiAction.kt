package io.github.q110.aiterminaltools.bridge

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages

class ModifySelectionWithAiAction : AbstractSelectionAiAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel.selectedText
        if (selection.isNullOrEmpty()) return

        val userRequest = Messages.showInputDialog(
            project,
            "Describe how to modify the selected code:",
            "Modify Selection with AI",
            Messages.getQuestionIcon()
        )?.trim() ?: return

        val tool = aiTool()
        val toolName = AiCliRunner.toolDisplayName(tool)
        val codeContext = buildCodeContext(project, editor, selection)
        val prompt = "Modify the given code selection by the user's request. " +
            "Output only the modified code. Do not add explanations, Markdown code fences, or any surrounding text.\n\n" +
            "User request:\n$userRequest\n\n$codeContext"
        queueSelectionTask(project, editor, tool, prompt, "Modifying selection with $toolName") { cleaned ->
            val document = editor.document
            val start = editor.selectionModel.selectionStart
            val end = editor.selectionModel.selectionEnd
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(start, end, cleaned)
            }
        }
    }
}
