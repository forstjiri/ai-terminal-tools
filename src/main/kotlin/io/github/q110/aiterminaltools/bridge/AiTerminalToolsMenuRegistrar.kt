// Dynamically registers context menu items after startup so they stay at the front and are not affected by load order
package io.github.q110.aiterminaltools.bridge

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.components.service
import io.github.q110.aiterminaltools.console.AiConsoleErrorInlayService

class AiTerminalToolsMenuRegistrar : StartupActivity, DumbAware {
    /** Initialize runtime services at startup and insert dynamic actions into the IDE menus/toolbars. */
    override fun runActivity(project: com.intellij.openapi.project.Project) {
        project.service<AiConsoleErrorInlayService>().initialize()
        project.service<AiTerminalDropService>().initialize()

        val actionManager = ActionManager.getInstance()

        registerMenuFirst(actionManager, "EditorPopupMenu", "AiTerminalTools.SendSelectionToAiTerminal")
        registerMenuFirst(actionManager, "ConsoleEditorPopupMenu", "AiTerminalTools.SendSelectionToAiTerminal")
        registerMenuFirst(actionManager, "Diff.EditorPopupMenu", "AiTerminalTools.SendSelectionToAiTerminal")
        registerMenuFirst(actionManager, "TextViewerEditorPopupMenu", "AiTerminalTools.SendSelectionToAiTerminal")
        registerMenuFirst(actionManager, "ProjectViewPopupMenu", "AiTerminalTools.SendPathToAiTerminal")
        registerMenuFirst(actionManager, "EditorTabPopupMenu", "AiTerminalTools.SendPathToAiTerminal")
        registerMenuFirst(actionManager, "ChangesViewPopupMenu", "AiTerminalTools.SendPathToAiTerminal")
        registerToolbarAction(actionManager, "AiTerminalTools.StartOpenCode")
        registerToolbarAction(actionManager, "AiTerminalTools.StartClaudeCode")
    }

    /** Insert the menu item at the front of the group using `Constraints.FIRST`. */
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

    /** Support both the new `MainToolbarRight` and the old `MainToolBar`. */
    private fun toolbarGroup(actionManager: ActionManager): DefaultActionGroup? {
        return actionManager.getAction("MainToolbarRight") as? DefaultActionGroup
            ?: actionManager.getAction("MainToolBar") as? DefaultActionGroup
    }
}
