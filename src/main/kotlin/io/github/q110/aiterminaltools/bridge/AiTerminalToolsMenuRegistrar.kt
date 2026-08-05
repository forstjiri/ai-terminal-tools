// Dynamically registers context-menu items after startup so they stay first regardless of load order.
package io.github.q110.aiterminaltools.bridge

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.components.service
import io.github.q110.aiterminaltools.console.AiConsoleErrorInlayService

class AiTerminalToolsMenuRegistrar : StartupActivity, DumbAware {
    /** Initializes runtime services at startup and inserts dynamic actions into IDE menus/toolbars. */
    override fun runActivity(project: com.intellij.openapi.project.Project) {
        project.service<AiConsoleErrorInlayService>().initialize()
        project.service<AiTerminalDropService>().initialize()

        val actionManager = ActionManager.getInstance()

        registerMenuFirst(actionManager, "EditorPopupMenu", "AiTerminalTools.SendSelectionToAiTerminal")
        registerMenuFirst(actionManager, "ConsoleEditorPopupMenu", "AiTerminalTools.SendSelectionToAiTerminal")
        registerMenuFirst(actionManager, "Diff.EditorPopupMenu", "AiTerminalTools.SendSelectionToAiTerminal")
        registerMenuFirst(actionManager, "TextViewerEditorPopupMenu", "AiTerminalTools.SendSelectionToAiTerminal")
        registerMenuFirst(actionManager, "EditorPopupMenu", "AiTerminalTools.QuerySelectionViaAi")
        registerMenuFirst(actionManager, "ConsoleEditorPopupMenu", "AiTerminalTools.QuerySelectionViaAi")
        registerMenuFirst(actionManager, "Diff.EditorPopupMenu", "AiTerminalTools.QuerySelectionViaAi")
        registerMenuFirst(actionManager, "TextViewerEditorPopupMenu", "AiTerminalTools.QuerySelectionViaAi")
        registerMenuFirst(actionManager, "ProjectViewPopupMenu", "AiTerminalTools.SendPathToAiTerminal")
        registerMenuFirst(actionManager, "EditorTabPopupMenu", "AiTerminalTools.SendPathToAiTerminal")
        registerMenuFirst(actionManager, "ChangesViewPopupMenu", "AiTerminalTools.SendPathToAiTerminal")
        registerToolbarAction(actionManager, "AiTerminalTools.StartOpenCode")
        registerToolbarAction(actionManager, "AiTerminalTools.StartClaudeCode")
        registerToolbarAction(actionManager, "AiTerminalTools.StartPi")
    }

    /** Inserts the action at the beginning of the menu group using Constraints.FIRST. */
    private fun registerMenuFirst(actionManager: ActionManager, menuId: String, actionId: String) {
        val group = actionManager.getAction(menuId) as? DefaultActionGroup ?: return
        val action = actionManager.getAction(actionId) ?: return

        if (group.getChildActionsOrStubs().any { it == action }) return
        group.addAction(action, Constraints.FIRST)
    }

    private fun registerToolbarAction(actionManager: ActionManager, actionId: String) {
        val action = actionManager.getAction(actionId) ?: return
        val group = toolbarGroup(actionManager) ?: return

        if (group.getChildActionsOrStubs().any { it == action }) return
        group.addAction(action, Constraints.LAST)
    }

    /** Supports both the newer MainToolbarRight and older MainToolBar. */
    private fun toolbarGroup(actionManager: ActionManager): DefaultActionGroup? {
        return actionManager.getAction("MainToolbarRight") as? DefaultActionGroup
            ?: actionManager.getAction("MainToolBar") as? DefaultActionGroup
    }
}
