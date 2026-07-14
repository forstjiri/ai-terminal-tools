// "Send File/Folder Path to AI Terminal" action — sends a path from the project tree or editor tab context menu.
package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import io.github.q110.aiterminaltools.filter.displayPath

class SendPathToAiTerminalAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    /** Updates the menu text according to whether the selection is a file or folder. */
    override fun update(event: AnActionEvent) {
        val project = event.project
        val selectedFiles = selectedVirtualFiles(event)
        val virtualFile = selectedFiles.firstOrNull()
        val hasFile = selectedFiles.isNotEmpty()
        event.presentation.text = if (virtualFile?.isDirectory == true) {
            "Send Folder Path to AI Terminal"
        } else {
            "Send File Path to AI Terminal"
        }
        event.presentation.isEnabledAndVisible = project != null && hasFile
    }

    /** Sends the path as @path, with settleAtLineEnd=true to finish @path completion. */
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val virtualFile = selectedVirtualFiles(event).firstOrNull()
        if (virtualFile == null) {
            AiTerminalBridgeService.notify(project, "No file or folder to send was found.", NotificationType.WARNING)
            return
        }

        val payload = "@${displayPath(project, virtualFile)}"
        when (
            val result = AiTerminalBridgeService.getInstance(project)
                .sendDirectInput(payload, event.dataContext, settleAtLineEnd = true)
        ) {
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

    /** Gets selected files, preferring VIRTUAL_FILE_ARRAY over VIRTUAL_FILE. */
    private fun selectedVirtualFiles(event: AnActionEvent): List<VirtualFile> {
        val selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!selectedFiles.isNullOrEmpty()) {
            return selectedFiles.toList()
        }

        val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
        return if (virtualFile != null) listOf(virtualFile) else emptyList()
    }
}
