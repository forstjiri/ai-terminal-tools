package io.github.q110.aiterminaltools.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class StartClaudeCodeAction : AbstractStartAiTerminalAction() {
    override val toolDisplayName: String = "Claude Code"

    override fun startTerminal(project: Project, virtualFile: VirtualFile?) =
        AiTerminalBridgeService.getInstance(project).startClaudeCodeTerminal(virtualFile)
}
