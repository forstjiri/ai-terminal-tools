package io.github.q110.aiterminaltools.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class StartCodexAction : AbstractStartAiTerminalAction() {
    override val toolDisplayName = "Codex"

    override fun startTerminal(project: Project, virtualFile: VirtualFile?): AiTerminalBridgeService.BridgeResult {
        return AiTerminalBridgeService.getInstance(project).startCodexTerminal(virtualFile)
    }
}
