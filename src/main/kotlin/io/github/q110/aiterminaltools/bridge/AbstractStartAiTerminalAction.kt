package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile

abstract class AbstractStartAiTerminalAction : AnAction() {
    protected abstract val toolDisplayName: String

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    protected abstract fun startTerminal(
        project: com.intellij.openapi.project.Project,
        virtualFile: VirtualFile?
    ): AiTerminalBridgeService.BridgeResult

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
        when (val result = startTerminal(project, virtualFile)) {
            is AiTerminalBridgeService.BridgeResult.Success -> {
                AiTerminalBridgeService.notify(project, "$toolDisplayName started", NotificationType.INFORMATION)
            }
            is AiTerminalBridgeService.BridgeResult.Scheduled -> Unit
            is AiTerminalBridgeService.BridgeResult.Error -> {
                AiTerminalBridgeService.notify(project, result.message, NotificationType.WARNING)
            }
        }
    }
}
