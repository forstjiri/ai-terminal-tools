// Frontend Terminal compatibility layer - adapts the IDE 2025.3+ terminal API via reflection
package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import io.github.q110.aiterminaltools.bridge.AiTerminalBridgeService.BridgeResult
import javax.swing.JComponent
import javax.swing.Timer

class FrontendTerminalHelper(
    private val project: Project
) {
    private val tabsManagerClass = Class.forName(FRONTEND_TABS_MANAGER_CLASS)
    private val tabBuilderClass = Class.forName(FRONTEND_TAB_BUILDER_CLASS)
    private val tabClass = Class.forName(FRONTEND_TAB_CLASS)
    private val tabsManager = Class.forName(FRONTEND_TABS_MANAGER_CLASS)
        .getMethod("getInstance", Project::class.java)
        .invoke(null, project)

    /** The selected terminal Content must be mapped back to the frontend tab object before we can write to the input area. */
    fun selectedTerminal(): Any? {
        val selectedContent = ToolWindowManager.getInstance(project)
            .getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?.contentManager?.selectedContent ?: return null
        return tabs().firstOrNull { contentOf(it) == selectedContent }
    }

    fun allTerminals(): List<Any> = tabs()

    fun allTerminalNames(): List<String> {
        return tabs().mapNotNull { contentOf(it)?.displayName }
    }

    fun createAiTerminal(tabName: String, workingDirectory: String): Any {
        var builder = tabsManagerMethod("createTabBuilder").invoke(tabsManager)
        builder = tabBuilderMethod("workingDirectory", String::class.java).invoke(builder, workingDirectory)
        builder = tabBuilderMethod("tabName", String::class.java).invoke(builder, tabName)
        builder = tabBuilderMethod("requestFocus", Boolean::class.javaPrimitiveType).invoke(builder, true)
        // Wait until the UI is fully shown before starting the session to avoid first-render width glitches in 2025.3+.
        builder = tabBuilderMethod("deferSessionStartUntilUiShown", Boolean::class.javaPrimitiveType).invoke(builder, true)
        return tabBuilderMethod("createTab").invoke(builder)
    }

    /** Run the command after the frontend terminal receives focus to avoid writing into an old focused component. */
    fun runCommand(
        tab: Any,
        command: String,
        successMessage: String,
        failurePrefix: String,
        onCommandSent: () -> Unit = {}
    ): BridgeResult {
        val content = contentOf(tab)
            ?: return BridgeResult.Error("Invalid frontend terminal tab.")
        val view = viewOf(tab)
            ?: return BridgeResult.Error("Invalid frontend terminal view.")
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: return BridgeResult.Error("Terminal tool window was not found.")

        toolWindow.activate(Runnable {
            toolWindow.contentManager.setSelectedContentCB(content, true, true)
                .doWhenProcessed(Runnable {
                    val focusComponent = preferredFocusableComponent(view)
                    if (focusComponent == null) {
                        AiTerminalBridgeService.notify(project, "Cannot focus the AI terminal input component.", NotificationType.WARNING)
                        return@Runnable
                    }
                    IdeFocusManager.getInstance(project)
                        .requestFocusInProject(focusComponent, project)
                        .doWhenDone(Runnable {
                            try {
                                sendCommandToExecute(view, command)
                                onCommandSent()
                                AiTerminalBridgeService.notify(project, successMessage, NotificationType.INFORMATION)
                            } catch (exception: Throwable) {
                                AiTerminalBridgeService.notify(project, "$failurePrefix: ${exception.message}", NotificationType.WARNING)
                            }
                        })
                        .doWhenRejected(Runnable {
                            AiTerminalBridgeService.notify(project, "Cannot focus the AI terminal input component.", NotificationType.WARNING)
                        })
                })
        }, true, true)

        return BridgeResult.Scheduled
    }

    fun isTabExists(tab: Any): Boolean {
        return tabs().any { it == tab }
    }

    fun isContentOf(tab: Any, content: Content): Boolean {
        return contentOf(tab) == content
    }

    /** Write directly to the input area; settleAtLineEnd is used to end `@path` completion state. */
    fun injectDirectInput(tab: Any, payload: String, settleAtLineEnd: Boolean): BridgeResult {
        val content = contentOf(tab)
            ?: return BridgeResult.Error("Invalid frontend terminal tab.")
        val view = viewOf(tab)
            ?: return BridgeResult.Error("Invalid frontend terminal view.")
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: return BridgeResult.Error("Terminal tool window was not found.")

        toolWindow.activate(Runnable {
            toolWindow.contentManager.setSelectedContentCB(content, true, true)
                .doWhenProcessed(Runnable {
                    val focusComponent = preferredFocusableComponent(view)
                    if (focusComponent == null) {
                        AiTerminalBridgeService.notify(project, "Cannot focus the AI terminal input component.", NotificationType.WARNING)
                        return@Runnable
                    }
                    val focusCallback = IdeFocusManager.getInstance(project)
                        .requestFocusInProject(focusComponent, project)
                    focusCallback.doWhenDone(Runnable {
                        try {
                            sendDirectInput(view, focusComponent, payload, settleAtLineEnd)
                        } catch (exception: Throwable) {
                            AiTerminalBridgeService.notify(project, "Failed to send AI Terminal input: ${exception.message}", NotificationType.WARNING)
                        }
                    })
                    focusCallback.doWhenRejected(Runnable {
                        AiTerminalBridgeService.notify(project, "Cannot focus the AI terminal input component.", NotificationType.WARNING)
                    })
                })
        }, true, true)

        return BridgeResult.Scheduled
    }

    private fun tabs(): List<Any> {
        val tabs = tabsManagerMethod("getTabs").invoke(tabsManager) as? List<*>
        return tabs?.filterNotNull().orEmpty()
    }

    private fun sendDirectInput(view: Any, focusComponent: JComponent, payload: String, settleAtLineEnd: Boolean) {
        try {
            sendRawString(view, payload)
        } catch (_: Throwable) {
            sendText(view, payload)
        }
        if (settleAtLineEnd) {
            scheduleLineEndSpace(view, focusComponent, true)
        } else {
            AiTerminalBridgeService.notify(project, "Sent to AI Terminal", NotificationType.INFORMATION)
        }
    }

    private fun scheduleLineEndSpace(view: Any, focusComponent: JComponent, notifyAfter: Boolean) {
        val timer = Timer(300) {
            try {
                IdeFocusManager.getInstance(project)
                    .requestFocusInProject(focusComponent, project)
                    .doWhenDone(Runnable {
                        sendLineEndSpace(view)
                        if (notifyAfter) {
                            AiTerminalBridgeService.notify(project, "Sent to AI Terminal", NotificationType.INFORMATION)
                        }
                    })
                    .doWhenRejected(Runnable {
                        AiTerminalBridgeService.notify(project, "Cannot focus the AI terminal input component.", NotificationType.WARNING)
                    })
            } catch (exception: Throwable) {
                AiTerminalBridgeService.notify(project, "Failed to send AI Terminal line-end spacing: ${exception.message}", NotificationType.WARNING)
            }
        }
        timer.isRepeats = false
        timer.start()
    }

    private fun sendRawString(view: Any, text: String) {
        val input = terminalInput(view)
        input.javaClass.getMethod("sendString", String::class.java).invoke(input, text)
    }

    private fun sendLineEndSpace(view: Any) {
        try {
            sendRawString(view, LINE_END_SPACE)
        } catch (_: Throwable) {
            sendText(view, LINE_END_SPACE)
        }
    }

    private fun sendText(view: Any, text: String) {
        view.javaClass.getMethod("sendText", String::class.java).invoke(view, text)
    }

    private fun sendCommandToExecute(view: Any, command: String) {
        val builder = view.javaClass.getMethod("createSendTextBuilder").invoke(view)
        val executableBuilder = builder.javaClass.getMethod("shouldExecute").invoke(builder)
        executableBuilder.javaClass.getMethod("send", String::class.java).invoke(executableBuilder, command)
    }

    private fun terminalInput(view: Any): Any {
        return findField(view.javaClass, "terminalInput")
            ?.let { field -> field.also { it.isAccessible = true }.get(view) }
            ?: throw IllegalStateException("Cannot access the frontend terminal input channel.")
    }

    private fun contentOf(tab: Any): Content? {
        return tabMethod("getContent").invoke(tab) as? Content
    }

    private fun viewOf(tab: Any): Any? {
        return tabMethod("getView").invoke(tab)
    }

    private fun preferredFocusableComponent(view: Any): JComponent? {
        return view.javaClass.getMethod("getPreferredFocusableComponent").invoke(view) as? JComponent
    }

    /** The Frontend Terminal API is still evolving, so all call sites go through reflection fallback here. */
    private fun tabsManagerMethod(name: String, vararg parameterTypes: Class<*>?): java.lang.reflect.Method {
        return tabsManagerClass.getMethod(name, *parameterTypes)
    }

    private fun tabBuilderMethod(name: String, vararg parameterTypes: Class<*>?): java.lang.reflect.Method {
        return tabBuilderClass.getMethod(name, *parameterTypes)
    }

    private fun tabMethod(name: String, vararg parameterTypes: Class<*>?): java.lang.reflect.Method {
        return tabClass.getMethod(name, *parameterTypes)
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    companion object {
        private const val FRONTEND_TABS_MANAGER_CLASS =
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"
        private const val FRONTEND_TAB_BUILDER_CLASS =
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder"
        private const val FRONTEND_TAB_CLASS =
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab"
        private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
        private const val LINE_END_SPACE = "\u0005 "
    }
}
