package io.github.q110.aiterminaltools.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class StartOpenCode2Action : AbstractStartAiTerminalAction() {
    override val toolDisplayName: String = "OpenCode 2"

    override fun startTerminal(project: Project, virtualFile: VirtualFile?) =
        AiTerminalBridgeService.getInstance(project).startOpenCode2Terminal(virtualFile)
}
