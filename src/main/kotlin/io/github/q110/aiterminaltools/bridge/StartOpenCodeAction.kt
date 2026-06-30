// "Start OpenCode" action - creates an OpenCode terminal tab monitored by the plugin
package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class StartOpenCodeAction : AnAction() {
    /** This toolbar action only depends on project context, so the enabled state can be updated on the EDT. */
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        // Terminal creation, environment injection, and event listener setup are handled by the bridge service.
        when (val result = AiTerminalBridgeService.getInstance(project).startOpenCodeTerminal()) {
            is AiTerminalBridgeService.BridgeResult.Success -> {
                AiTerminalBridgeService.notify(project, "Started OpenCode", NotificationType.INFORMATION)
            }
            is AiTerminalBridgeService.BridgeResult.Scheduled -> {
            }
            is AiTerminalBridgeService.BridgeResult.Error -> {
                AiTerminalBridgeService.notify(project, result.message, NotificationType.WARNING)
            }
        }
    }
}
