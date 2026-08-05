package io.github.q110.aiterminaltools.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class StartPiAction : AbstractStartAiTerminalAction() {
    override val toolDisplayName: String = "Pi"

    override fun startTerminal(project: Project, virtualFile: VirtualFile?) =
        AiTerminalBridgeService.getInstance(project).startPiTerminal(virtualFile)
}
