// Sends the diagnostic message under the caret to the selected AI terminal.
package io.github.q110.aiterminaltools.bridge

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Iconable
import javax.swing.Icon
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.q110.aiterminaltools.filter.displayPath

class SendDiagnosticToAiTerminalAction : IntentionAction, DumbAware, Iconable {
    override fun getText(): String = "Send Diagnostic to AI Terminal"

    override fun getFamilyName(): String = "AI Terminal Tools"

    override fun getIcon(flags: Int): Icon = AllIcons.Debugger.Console

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        return findDiagnosticMessage(project, editor) != null
    }

    private fun findDiagnosticMessage(project: Project, editor: Editor): String? {
        val offset = editor.caretModel.offset
        var bestSeverityVal = -1
        var bestMessage: String? = null

        DaemonCodeAnalyzerEx.processHighlights(
            editor.document,
            project,
            HighlightSeverity.WEAK_WARNING,
            offset,
            offset,
        ) { info ->
            val severity = info.severity
            if (severity != HighlightSeverity.ERROR && severity != HighlightSeverity.WARNING && severity != HighlightSeverity.WEAK_WARNING) {
                return@processHighlights true
            }
            val message = DiagnosticPayload.message(info.description, info.toolTip)
            if (message != null && severity.myVal > bestSeverityVal) {
                bestSeverityVal = severity.myVal
                bestMessage = message
            }
            true
        }

        return bestMessage
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val message = findDiagnosticMessage(project, editor) ?: return

        val virtualFile = file.virtualFile
        val line = editor.document.getLineNumber(editor.caretModel.offset) + 1
        val filePath = virtualFile?.let { displayPath(project, it) }
        val payload = DiagnosticPayload.format(filePath, line, message)

        when (val result = AiTerminalBridgeService.getInstance(project).sendDirectPasteToSelectedAiTerminal(payload)) {
            is AiTerminalBridgeService.BridgeResult.Success -> {
                AiTerminalBridgeService.notify(project, "Sent to AI Terminal", NotificationType.INFORMATION)
            }
            is AiTerminalBridgeService.BridgeResult.Scheduled -> {
            }
            is AiTerminalBridgeService.BridgeResult.Error -> {
                AiTerminalBridgeService.notify(project, result.message, NotificationType.WARNING)
            }
        }
    }
}
