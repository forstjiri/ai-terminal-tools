// Legacy Reworked Terminal compatibility layer - adapts the 2025.1/2025.2 terminal API via reflection
package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Timer

class LegacyReworkedTerminalHelper(
    private val project: Project
) {
    /** Prefer the Reworked engine to avoid accidentally getting the Classic terminal implementation. */
    fun createAiTerminal(tabName: String, workingDirectory: String): TerminalWidget? {
        val manager = TerminalToolWindowManager.getInstance(project)
        val toolWindow = manager.toolWindow ?: return null
        val contentManager = toolWindow.contentManager

        for (engineName in listOf("REWORKED", "NEW_TERMINAL")) {
            val widget = tryCreateTab(manager, contentManager, engineName, tabName, workingDirectory)
            if (widget != null) {
                if (isClassicWidget(widget)) {
                    continue
                }
                contentManager.contents
                    .firstOrNull { TerminalToolWindowManager.findWidgetByContent(it) == widget }
                    ?.let { content ->
                        content.displayName = tabName
                        contentManager.setSelectedContent(content)
                    }
                return widget
            }
        }

        return tryCreate20251Session(manager, tabName, workingDirectory)
    }

    fun runCommand(
        widget: TerminalWidget,
        command: String,
        successMessage: String,
        failurePrefix: String,
        onCommandSent: () -> Unit = {},
        onCommandFailed: () -> Unit = {}
    ): AiTerminalBridgeService.BridgeResult {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: return AiTerminalBridgeService.BridgeResult.Error("Terminal tool window was not found.")

        toolWindow.activate(Runnable {
            val future = terminalSizeInitializedFuture(widget)

            if (future == null) {
                sendCommand(widget, command, successMessage, failurePrefix, onCommandSent, onCommandFailed)
            } else {
                future.whenComplete { _: Any?, exception: Throwable? ->
                    ApplicationManager.getApplication().invokeLater {
                        if (exception != null) {
                            AiTerminalBridgeService.notify(project, "$failurePrefix: ${exception.message}", NotificationType.WARNING)
                            onCommandFailed()
                        } else {
                            sendCommand(widget, command, successMessage, failurePrefix, onCommandSent, onCommandFailed)
                        }
                    }
                }
            }
        }, true, true)

        return AiTerminalBridgeService.BridgeResult.Scheduled
    }

    /** New and old Reworked APIs expose initialization size differently; if reflection fails, send the command directly. */
    private fun terminalSizeInitializedFuture(widget: TerminalWidget): CompletableFuture<*>? {
        return try {
            widget.javaClass.getMethod("getTerminalSizeInitializedFuture").invoke(widget) as? CompletableFuture<*>
        } catch (_: Throwable) {
            null
        }
    }

    fun injectDirectInput(widget: TerminalWidget, payload: String, settleAtLineEnd: Boolean): AiTerminalBridgeService.BridgeResult {
        return try {
            widget.requestFocus()
            sendDirectInput(widget, payload)
            if (settleAtLineEnd) {
                scheduleLineEndSpace { sendDirectInput(widget, LINE_END_SPACE) }
            }
            AiTerminalBridgeService.BridgeResult.Success
        } catch (exception: Throwable) {
            AiTerminalBridgeService.BridgeResult.Error("Failed to send AI Terminal input: ${exception.message}")
        }
    }

    fun isReworkedWidget(widget: TerminalWidget): Boolean {
        return widget.javaClass.name == REWORKED_WIDGET_CLASS
    }

    fun isWidgetContentExists(widget: TerminalWidget): Boolean {
        val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow ?: return false
        return toolWindow.contentManager.contents.any { content ->
            TerminalToolWindowManager.findWidgetByContent(content) == widget
        }
    }

    private fun tryCreateTab(
        manager: TerminalToolWindowManager,
        contentManager: Any,
        engineName: String,
        tabName: String,
        workingDirectory: String
    ): TerminalWidget? {
        val engine = enumValue("org.jetbrains.plugins.terminal.TerminalEngine", engineName) ?: return null
        val state = terminalTabState(tabName, workingDirectory) ?: return null

        return tryCreate2025xTab(manager, engine, state, contentManager)
            ?: tryCreate20252Tab(manager, engine, state)
    }

    private fun tryCreate20251Session(
        manager: TerminalToolWindowManager,
        tabName: String,
        workingDirectory: String
    ): TerminalWidget? {
        val state = terminalTabState(tabName, workingDirectory) ?: return null
        val createdWidget = AtomicReference<TerminalWidget?>()
        val disposable: Disposable = Disposer.newDisposable("AI Terminal Reworked setup")
        return try {
            manager.addNewTerminalSetupHandler({ widget ->
                if (!isClassicWidget(widget)) {
                    createdWidget.set(widget)
                }
            }, disposable)
            val method = manager.javaClass.methods.firstOrNull { method ->
                method.name == "createNewSession" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].name == "org.jetbrains.plugins.terminal.TerminalTabState"
            } ?: return null
            method.invoke(manager, state)
            val widget = createdWidget.get() ?: return null
            val toolWindow = manager.toolWindow ?: return null
            toolWindow.contentManager.contents
                .firstOrNull { TerminalToolWindowManager.findWidgetByContent(it) == widget }
                ?.let { content ->
                    content.displayName = tabName
                    toolWindow.contentManager.setSelectedContent(content)
                }
            widget
        } catch (_: Throwable) {
            null
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun tryCreate2025xTab(
        manager: TerminalToolWindowManager,
        engine: Any,
        state: Any,
        contentManager: Any
    ): TerminalWidget? {
        return try {
            val method = manager.javaClass.methods.firstOrNull { method ->
                method.name == "createNewTab" &&
                    method.parameterTypes.size == 3 &&
                    method.parameterTypes[0].name == "org.jetbrains.plugins.terminal.TerminalEngine" &&
                    method.parameterTypes[1].name == "org.jetbrains.plugins.terminal.TerminalTabState" &&
                    method.parameterTypes[2].name == "com.intellij.ui.content.ContentManager"
            } ?: return null
            method.invoke(manager, engine, state, contentManager) as? TerminalWidget
        } catch (_: Throwable) {
            null
        }
    }

    private fun isClassicWidget(widget: TerminalWidget): Boolean {
        val className = widget.javaClass.name
        return className.contains("ShellTerminalWidget") || className.contains("JediTerm")
    }

    private fun tryCreate20252Tab(
        manager: TerminalToolWindowManager,
        engine: Any,
        state: Any
    ): TerminalWidget? {
        return try {
            val fusInfo = terminalStartupFusInfo() ?: return null
            val method = manager.javaClass.methods.firstOrNull { method ->
                method.name == "createNewTab" &&
                    method.parameterTypes.size == 3 &&
                    method.parameterTypes[0].name == "org.jetbrains.plugins.terminal.TerminalEngine" &&
                    method.parameterTypes[1].name == "org.jetbrains.plugins.terminal.fus.TerminalStartupFusInfo" &&
                    method.parameterTypes[2].name == "org.jetbrains.plugins.terminal.TerminalTabState"
            } ?: return null
            method.invoke(manager, engine, fusInfo, state) as? TerminalWidget
        } catch (_: Throwable) {
            null
        }
    }

    private fun terminalTabState(tabName: String, workingDirectory: String): Any? {
        return try {
            val stateClass = Class.forName("org.jetbrains.plugins.terminal.TerminalTabState")
            val state = stateClass.getConstructor().newInstance()
            stateClass.getField("myTabName").set(state, tabName)
            stateClass.getField("myIsUserDefinedTabTitle").setBoolean(state, true)
            stateClass.getField("myWorkingDirectory").set(state, workingDirectory)
            state
        } catch (_: Throwable) {
            null
        }
    }

    private fun terminalStartupFusInfo(): Any? {
        return try {
            val openingWay = enumValue("org.jetbrains.plugins.terminal.fus.TerminalOpeningWay", "OPEN_NEW_TAB")
                ?: return null
            val fusInfoClass = Class.forName("org.jetbrains.plugins.terminal.fus.TerminalStartupFusInfo")
            fusInfoClass.constructors
                .firstOrNull { constructor -> constructor.parameterTypes.size == 1 }
                ?.newInstance(openingWay)
        } catch (_: Throwable) {
            null
        }
    }

    private fun sendCommand(
        widget: TerminalWidget,
        command: String,
        successMessage: String,
        failurePrefix: String,
        onCommandSent: () -> Unit,
        onCommandFailed: () -> Unit
    ) {
        try {
            widget.requestFocus()
            widget.sendCommandToExecute(command)
            onCommandSent()
            AiTerminalBridgeService.notify(project, successMessage, NotificationType.INFORMATION)
        } catch (_: Throwable) {
            try {
                sendDirectInput(widget, "$command\r")
                onCommandSent()
                AiTerminalBridgeService.notify(project, successMessage, NotificationType.INFORMATION)
            } catch (writeException: Throwable) {
                AiTerminalBridgeService.notify(project, "$failurePrefix: ${writeException.message}", NotificationType.WARNING)
                onCommandFailed()
            }
        }
    }

    private fun sendDirectInput(widget: TerminalWidget, text: String) {
        try {
            val input = terminalInput(widget)
            input.javaClass.getMethod("sendString", String::class.java).invoke(input, text)
            return
        } catch (_: Throwable) {
        }

        val accessor = widget.javaClass.getMethod("getTtyConnectorAccessor").invoke(widget)
        accessor.javaClass
            .getMethod("executeWithTtyConnector", java.util.function.Consumer::class.java)
            .invoke(accessor, java.util.function.Consumer<Any> { connector ->
                connector.javaClass.getMethod("write", String::class.java).invoke(connector, text)
            })
    }

    private fun terminalInput(widget: TerminalWidget): Any {
        val view = findField(widget.javaClass, "view")
            ?.let { field -> field.also { it.isAccessible = true }.get(widget) }
            ?: throw IllegalStateException("Cannot access the reworked terminal view.")
        return findField(view.javaClass, "terminalInput")
            ?.let { field -> field.also { it.isAccessible = true }.get(view) }
            ?: throw IllegalStateException("Cannot access the reworked terminal input channel.")
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

    private fun scheduleLineEndSpace(writeLineEndSpace: () -> Unit) {
        Timer(SETTLE_INPUT_DELAY_MS) {
            try {
                writeLineEndSpace()
            } catch (exception: Throwable) {
                AiTerminalBridgeService.notify(project, "Failed to send AI Terminal line-end spacing: ${exception.message}", NotificationType.WARNING)
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumValue(className: String, valueName: String): Any? {
        return try {
            val enumClass = Class.forName(className) as Class<out Enum<*>>
            java.lang.Enum.valueOf(enumClass, valueName)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
        private const val REWORKED_WIDGET_CLASS = "com.intellij.terminal.frontend.ReworkedTerminalWidget"
        private const val LINE_END_SPACE = "\u0005 "
        private const val SETTLE_INPUT_DELAY_MS = 300
    }
}
