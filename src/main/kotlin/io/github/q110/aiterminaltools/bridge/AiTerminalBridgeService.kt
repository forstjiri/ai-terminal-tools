// AI Terminal 桥接核心服务 — 直接向当前激活的终端输入区写入内容
package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import io.github.q110.aiterminaltools.filter.displayPath
import io.github.q110.aiterminaltools.monitor.AiTerminalTabContext
import io.github.q110.aiterminaltools.monitor.AiTool
import io.github.q110.aiterminaltools.monitor.AiTurnEventServer
import io.github.q110.aiterminaltools.monitor.AiTurnHookInstaller
import io.github.q110.aiterminaltools.monitor.AiTurnOpenCodeInstaller
import io.github.q110.aiterminaltools.monitor.AiTurnMonitorService
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.IOException
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

@Service(Service.Level.PROJECT)
class AiTerminalBridgeService(
    private val project: Project
) {
    /** 新版终端辅助类，仅在 2025.3+ IDE 中可加载，低版本为 null */
    private val frontendHelper: FrontendTerminalHelper? = try {
        FrontendTerminalHelper(project)
    } catch (_: Throwable) {
        null
    }

    private val legacyReworkedTerminalHelper = LegacyReworkedTerminalHelper(project)
    private val log = Logger.getInstance(AiTerminalBridgeService::class.java)
    private val openCodeTerminalStartInProgress = AtomicBoolean(false)
    private val claudeCodeTerminalStartInProgress = AtomicBoolean(false)
    private val aiFrontendTerminals = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val aiLegacyReworkedTerminals = Collections.newSetFromMap(IdentityHashMap<TerminalWidget, Boolean>())
    private val aiClassicTerminals = Collections.newSetFromMap(IdentityHashMap<TerminalWidget, Boolean>())

    /** 直接写入当前激活的 AI 终端输入区 */
    fun sendDirectInput(payload: String, dataContext: DataContext, settleAtLineEnd: Boolean = false): BridgeResult {
        val terminal = resolveTargetTerminal(dataContext)
            ?: return BridgeResult.Error(NO_ACTIVE_TERMINAL_MESSAGE)

        return injectDirectInput(terminal, payload, settleAtLineEnd)
    }

    /** 使用 bracketed paste 直接写入多行内容，避免换行被终端当作提交处理 */
    fun sendDirectPaste(payload: String, dataContext: DataContext): BridgeResult {
        val terminal = resolveTargetTerminal(dataContext)
            ?: return BridgeResult.Error(NO_ACTIVE_TERMINAL_MESSAGE)

        return injectDirectInput(terminal, bracketedPaste(payload), settleAtLineEnd = false)
    }

    /** 拖拽路径合并为一次输入，避免多次触发造成卡顿 */
    fun sendDroppedPaths(files: List<VirtualFile>): BridgeResult {
        val payload = files.filter { it.isValid }
            .joinToString(separator = " ") { pathPayload(it) }
        if (payload.isBlank()) {
            return BridgeResult.Error("Could not find a file or folder to send.")
        }

        val terminal = selectedTerminal()
            ?: return BridgeResult.Error(NO_ACTIVE_TERMINAL_MESSAGE)
        return injectDirectInput(terminal, payload, settleAtLineEnd = true)
    }

    fun isSelectedTerminalRecordedAiTerminal(): Boolean {
        val terminal = selectedTerminal()?.takeIf { isUsable(it) } ?: return false
        return isRecordedAiTerminal(terminal)
    }

    fun isRecordedAiTerminalContent(content: Content): Boolean {
        pruneInvalidAiTerminalRecords()

        val frontendHelper = frontendHelper
        if (frontendHelper != null && aiFrontendTerminals.any { isFrontendContentOf(frontendHelper, it, content) }) {
            return true
        }

        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        return widget != null && (widget in aiLegacyReworkedTerminals || widget in aiClassicTerminals)
    }

    internal fun unregisterAiTerminalContent(content: Content) {
        frontendHelper?.let { helper ->
            aiFrontendTerminals.removeAll { isFrontendContentOf(helper, it, content) }
        }

        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        if (widget != null) {
            aiLegacyReworkedTerminals.remove(widget)
            aiClassicTerminals.remove(widget)
        }
    }

    /** 创建新的 OpenCode terminal，并启动 opencode */
    fun startOpenCodeTerminal(): BridgeResult {
        if (!openCodeTerminalStartInProgress.compareAndSet(false, true)) {
            return BridgeResult.Scheduled
        }
        scheduleOpenCodeTerminalStart()
        return BridgeResult.Scheduled
    }

    /** 创建新的 Claude Code terminal，并启动 claude */
    fun startClaudeCodeTerminal(): BridgeResult {
        if (!claudeCodeTerminalStartInProgress.compareAndSet(false, true)) {
            return BridgeResult.Scheduled
        }
        scheduleClaudeCodeTerminalStart()
        return BridgeResult.Scheduled
    }

    private fun scheduleOpenCodeTerminalStart() {
        // OpenCode：注入监控上下文，使用 launcher 脚本启动
        val tabId = UUID.randomUUID().toString()
        val token = generateSecureToken()

        val port = try {
            project.service<AiTurnEventServer>().ensureStarted()
        } catch (exception: Throwable) {
            log.error("Failed to start AiTurnEventServer", exception)
            openCodeTerminalStartInProgress.set(false)
            notify(project, "Failed to start AI Turn Event Server: ${exception.message}", NotificationType.WARNING)
            return
        }

        val launcherCommand = try {
            val installer = AiTurnOpenCodeInstaller(project)
            val launcherPaths = installer.installOpenCodePlugin(tabId, token, port)
            if (isWindows()) {
                launcherPaths.cmdPath.toString()
            } else {
                launcherPaths.shPath.toString()
            }
        } catch (exception: Throwable) {
            log.error("Failed to install OpenCode plugin", exception)
            openCodeTerminalStartInProgress.set(false)
            notify(project, "Failed to install the OpenCode plugin: ${exception.message}", NotificationType.WARNING)
            return
        }

        val workingDirectory = terminalWorkingDirectory()
        val tabContext = AiTerminalTabContext(
            tabId = tabId,
            token = token,
            tool = AiTool.OPENCODE,
            workingDirectory = Path.of(workingDirectory),
            createdAtMillis = System.currentTimeMillis()
        )
        project.service<AiTurnMonitorService>().registerTab(tabContext)

        scheduleTerminalStart(
            tabName = nextTerminalTabName(OPEN_CODE_TAB_NAME),
            command = launcherCommand,
            toolName = OPEN_CODE_TAB_NAME,
            inProgress = openCodeTerminalStartInProgress
        )
    }

    private fun scheduleClaudeCodeTerminalStart() {
        // Claude Code：注入监控上下文，使用 launcher 脚本启动
        val tabId = UUID.randomUUID().toString()
        val token = generateSecureToken()

        val port = try {
            project.service<AiTurnEventServer>().ensureStarted()
        } catch (exception: Throwable) {
            log.error("Failed to start AiTurnEventServer", exception)
            claudeCodeTerminalStartInProgress.set(false)
            notify(project, "Failed to start AI Turn Event Server: ${exception.message}", NotificationType.WARNING)
            return
        }

        val launcherCommand = try {
            val installer = AiTurnHookInstaller(project)
            val launcherPaths = installer.installClaudeHooks(tabId, token, port)
            if (isWindows()) {
                launcherPaths.cmdPath.toString()
            } else {
                launcherPaths.shPath.toString()
            }
        } catch (exception: Throwable) {
            log.error("Failed to install Claude hooks", exception)
            claudeCodeTerminalStartInProgress.set(false)
            notify(project, "Failed to install Claude Code hooks: ${exception.message}", NotificationType.WARNING)
            return
        }

        val workingDirectory = terminalWorkingDirectory()
        val tabContext = AiTerminalTabContext(
            tabId = tabId,
            token = token,
            tool = AiTool.CLAUDE_CODE,
            workingDirectory = Path.of(workingDirectory),
            createdAtMillis = System.currentTimeMillis()
        )
        project.service<AiTurnMonitorService>().registerTab(tabContext)

        scheduleTerminalStart(
            tabName = nextTerminalTabName(CLAUDE_CODE_TAB_NAME),
            command = launcherCommand,
            toolName = CLAUDE_CODE_TAB_NAME,
            inProgress = claudeCodeTerminalStartInProgress
        )
    }

    private fun scheduleTerminalStart(tabName: String, command: String, toolName: String, inProgress: AtomicBoolean) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                inProgress.set(false)
                return@invokeLater
            }

            val terminalToolWindowManager = TerminalToolWindowManager.getInstance(project)
            val toolWindow = terminalToolWindow(terminalToolWindowManager)
            if (toolWindow == null) {
                inProgress.set(false)
                notify(project, "Terminal tool window was not found.", NotificationType.WARNING)
                return@invokeLater
            }

            toolWindow.activate(Runnable {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val workingDirectory = terminalWorkingDirectory()
                        val result = startFrontendTerminal(tabName, workingDirectory, command, toolName)
                            ?: if (shouldSkipLegacyReworkedTerminal(toolName)) {
                                startClassicTerminal(tabName, workingDirectory, command, toolName)
                            } else {
                                startLegacyReworkedTerminal(tabName, workingDirectory, command, toolName)
                                    ?: run {
                                        notifyLegacyReworkedFallbackIfNeeded(toolName)
                                        startClassicTerminal(tabName, workingDirectory, command, toolName)
                                    }
                            }
                        if (result is BridgeResult.Error) {
                            notify(project, result.message, NotificationType.WARNING)
                        }
                    } finally {
                        inProgress.set(false)
                    }
                }
            }, true, true)
        }
    }

    private fun shouldSkipLegacyReworkedTerminal(toolName: String): Boolean {
        return toolName == OPEN_CODE_TAB_NAME && ideBaselineVersion() in 251..252
    }

    private fun startFrontendTerminal(tabName: String, workingDirectory: String, command: String, toolName: String): BridgeResult? {
        val helper = frontendHelper ?: return null
        return try {
            val tab = helper.createAiTerminal(tabName, workingDirectory)
            helper.runCommand(
                tab,
                command,
                "Started $toolName terminal",
                "Failed to start $toolName"
            ) {
                registerAiTerminal(TargetTerminal.Frontend(tab))
            }
        } catch (exception: Throwable) {
            notify(project, "The new terminal is unavailable; falling back to Classic Terminal: ${exception.message}", NotificationType.WARNING)
            null
        }
    }

    private fun startLegacyReworkedTerminal(tabName: String, workingDirectory: String, command: String, toolName: String): BridgeResult? {
        return try {
            val widget = legacyReworkedTerminalHelper.createAiTerminal(tabName, workingDirectory)
                ?: return null
            legacyReworkedTerminalHelper.runCommand(
                widget = widget,
                command = command,
                successMessage = "Started $toolName terminal",
                failurePrefix = "Failed to run $command",
                onCommandSent = {
                    registerAiTerminal(TargetTerminal.LegacyReworked(widget))
                },
                onCommandFailed = {
                    val result = startClassicTerminal(tabName, workingDirectory, command, toolName)
                    if (result is BridgeResult.Error) {
                        notify(project, result.message, NotificationType.WARNING)
                    }
                }
            )
        } catch (exception: Throwable) {
            notify(project, "Reworked Terminal is unavailable; falling back to Classic Terminal: ${exception.message}", NotificationType.WARNING)
            null
        }
    }

    private fun startClassicTerminal(tabName: String, workingDirectory: String, command: String, toolName: String): BridgeResult {
        val terminalToolWindowManager = TerminalToolWindowManager.getInstance(project)
        val toolWindow = terminalToolWindow(terminalToolWindowManager)
            ?: return BridgeResult.Error("Terminal tool window was not found.")
        val startupOptions = ShellStartupOptions.Builder()
            .workingDirectory(workingDirectory)
            .build()
        val startupDisposable: Disposable = Disposer.newDisposable("$toolName Terminal startup")
        val widget = try {
            terminalToolWindowManager.terminalRunner.startShellTerminalWidget(startupDisposable, startupOptions, true)
        } catch (exception: Throwable) {
            Disposer.dispose(startupDisposable)
            return BridgeResult.Error("Failed to create $toolName Terminal: ${exception.message}")
        }

        val content = terminalToolWindowManager.newTab(toolWindow, widget)
        content.displayName = tabName
        toolWindow.activate(Runnable {
            try {
                ShellTerminalWidget.toShellJediTermWidgetOrThrow(widget).executeCommand(command)
                registerAiTerminal(TargetTerminal.Classic(widget))
                notify(project, "Started $toolName terminal", NotificationType.INFORMATION)
            } catch (exception: Throwable) {
                notify(project, "Failed to run $command: ${exception.message}", NotificationType.WARNING)
            }
        }, true, true)
        return BridgeResult.Scheduled
    }

    private fun pathPayload(file: VirtualFile): String {
        return "@${displayPath(project, file)}"
    }

    private fun bracketedPaste(payload: String): String {
        return BRACKETED_PASTE_START + payload + BRACKETED_PASTE_END
    }

    private fun terminalWorkingDirectory(): String {
        return project.basePath ?: System.getProperty("user.home")
    }

    private fun nextTerminalTabName(baseName: String): String {
        val existingNames = terminalNames()
        val pattern = Regex("""^${Regex.escape(baseName)} \((\d+)\)$""")
        val maxIndex = existingNames.fold(0) { max, name ->
            when {
                name == baseName -> maxOf(max, 1)
                else -> maxOf(max, pattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
            }
        }
        return if (maxIndex == 0) baseName else "$baseName (${maxIndex + 1})"
    }

    private fun terminalNames(): List<String> {
        val frontendNames = frontendHelper?.allTerminalNames().orEmpty()
        val classicNames = TerminalToolWindowManager.getInstance(project)
            .toolWindow
            ?.contentManager
            ?.contents
            ?.mapNotNull { it.displayName }
            .orEmpty()
        return frontendNames + classicNames
    }

    private fun terminalToolWindow(manager: TerminalToolWindowManager): ToolWindow? {
        manager.toolWindow?.let { return it }
        return try {
            val method = manager.javaClass.getDeclaredMethod("getOrInitToolWindow")
            method.isAccessible = true
            method.invoke(manager) as? ToolWindow
        } catch (_: Throwable) {
            null
        }
    }

    private fun notifyLegacyReworkedFallbackIfNeeded(toolName: String) {
        when (ideBaselineVersion()) {
            251 -> notify(
                project,
                "Start $toolName using Classic Terminal.",
                NotificationType.WARNING
            )
            252 -> notify(
                project,
                "Start $toolName using Classic Terminal.",
                NotificationType.WARNING
            )
        }
    }

    private fun ideBaselineVersion(): Int {
        return ApplicationInfo.getInstance().build.baselineVersion
    }

    private fun injectDirectInput(terminal: TargetTerminal, payload: String, settleAtLineEnd: Boolean): BridgeResult {
        return when (terminal) {
            is TargetTerminal.Classic -> injectClassicDirectInput(terminal.widget, payload, settleAtLineEnd)
            is TargetTerminal.LegacyReworked -> legacyReworkedTerminalHelper.injectDirectInput(
                terminal.widget,
                payload,
                settleAtLineEnd
            )
            is TargetTerminal.Frontend -> {
                val helper = frontendHelper
                    ?: return BridgeResult.Error("The new terminal API is unavailable in the current IDE.")
                helper.injectDirectInput(terminal.tab, payload, settleAtLineEnd)
            }
        }
    }

    private fun registerAiTerminal(terminal: TargetTerminal) {
        when (terminal) {
            is TargetTerminal.Classic -> aiClassicTerminals += terminal.widget
            is TargetTerminal.LegacyReworked -> aiLegacyReworkedTerminals += terminal.widget
            is TargetTerminal.Frontend -> aiFrontendTerminals += terminal.tab
        }
        project.service<AiTerminalDropService>().refreshDropTarget()
    }

    private fun isRecordedAiTerminal(terminal: TargetTerminal): Boolean {
        pruneInvalidAiTerminalRecords()

        return when (terminal) {
            is TargetTerminal.Classic -> terminal.widget in aiClassicTerminals
            is TargetTerminal.LegacyReworked -> terminal.widget in aiLegacyReworkedTerminals
            is TargetTerminal.Frontend -> terminal.tab in aiFrontendTerminals
        }
    }

    private fun pruneInvalidAiTerminalRecords() {
        val helper = frontendHelper
        if (helper == null) {
            aiFrontendTerminals.clear()
        } else {
            aiFrontendTerminals.removeAll { !helper.isTabExists(it) }
        }

        aiLegacyReworkedTerminals.removeAll { widget ->
            !legacyReworkedTerminalHelper.isWidgetContentExists(widget)
        }

        aiClassicTerminals.removeAll { widget ->
            try {
                widget.ttyConnector?.isConnected != true
            } catch (_: Throwable) {
                true
            }
        }
    }

    private fun isFrontendContentOf(helper: FrontendTerminalHelper, tab: Any, content: Content): Boolean {
        return try {
            helper.isContentOf(tab, content)
        } catch (_: Throwable) {
            false
        }
    }

    /** 通过 TTY Connector 直接向经典终端写入文本 */
    private fun injectClassicDirectInput(terminal: TerminalWidget, payload: String, settleAtLineEnd: Boolean): BridgeResult {
        val connector = try {
            terminal.ttyConnector
        } catch (_: Throwable) {
            return BridgeResult.Error("The current Terminal does not expose a writable TTY connector.")
        } ?: return BridgeResult.Error("The current Terminal does not expose a writable TTY connector.")

        if (!connector.isConnected) {
            return BridgeResult.Error("The currently active Terminal is disconnected.")
        }

        return try {
            terminal.requestFocus()
            connector.write(payload)
            if (settleAtLineEnd) {
                scheduleClassicLineEndSpace { connector.write(LINE_END_SPACE) }
            }
            BridgeResult.Success
        } catch (exception: IOException) {
            BridgeResult.Error("Failed to send AI Terminal input: ${exception.message}")
        }
    }

    /** 经典终端上行尾空格延时 300ms（等待终端处理完输入） */
    private fun scheduleClassicLineEndSpace(writeLineEndSpace: () -> Unit) {
        Timer(SETTLE_INPUT_DELAY_MS) {
            try {
                writeLineEndSpace()
            } catch (exception: Throwable) {
                notify(project, "Failed to send AI Terminal line-end spacing: ${exception.message}", NotificationType.WARNING)
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    /** 终端发现优先级：DataContext → 当前选中终端 */
    private fun resolveTargetTerminal(dataContext: DataContext): TargetTerminal? {
        classicTerminalFromDataContext(dataContext)?.let {
            val target = TargetTerminal.Classic(it)
            if (isUsable(target)) return target
        }
        return selectedTerminal()?.takeIf { isUsable(it) }
    }

    private fun classicTerminalFromDataContext(dataContext: DataContext): TerminalWidget? {
        return JBTerminalWidget.TERMINAL_DATA_KEY.getData(dataContext)?.asNewWidget()
    }

    /** 优先前端终端 → 经典终端 */
    private fun selectedTerminal(): TargetTerminal? {
        return frontendHelper?.selectedTerminal()?.let { TargetTerminal.Frontend(it) }
            ?: selectedClassicOrLegacyTerminal()
    }

    private fun selectedClassicOrLegacyTerminal(): TargetTerminal? {
        val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow ?: return null
        val selectedContent = toolWindow.contentManager.selectedContent ?: return null
        val widget = TerminalToolWindowManager.findWidgetByContent(selectedContent) ?: return null
        return if (legacyReworkedTerminalHelper.isReworkedWidget(widget)) {
            TargetTerminal.LegacyReworked(widget)
        } else {
            TargetTerminal.Classic(widget)
        }
    }

    /** 检查终端是否可用：经典终端检查 TTY 连接，前端终端检查 tab 仍存在 */
    private fun isUsable(terminal: TargetTerminal): Boolean {
        return when (terminal) {
            is TargetTerminal.Classic -> {
                try {
                    terminal.widget.ttyConnector?.isConnected == true
                } catch (_: Throwable) {
                    false
                }
            }
            is TargetTerminal.LegacyReworked -> legacyReworkedTerminalHelper.isWidgetContentExists(terminal.widget)
            is TargetTerminal.Frontend -> frontendHelper?.isTabExists(terminal.tab) == true
        }
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name", "").lowercase().contains("win")
    }

    companion object {
        private const val NOTIFICATION_GROUP_ID = "AI Terminal Tools"
        private const val OPEN_CODE_TAB_NAME = "OpenCode"
        private const val CLAUDE_CODE_TAB_NAME = "Claude Code"
        private const val NO_ACTIVE_TERMINAL_MESSAGE = "Please start and activate an OpenCode or Claude Code terminal first."
        private const val LINE_END_SPACE = "\u0005 "
        private const val BRACKETED_PASTE_START = "\u001B[200~"
        private const val BRACKETED_PASTE_END = "\u001B[201~"
        private const val SETTLE_INPUT_DELAY_MS = 300

        fun getInstance(project: Project): AiTerminalBridgeService {
            return project.service()
        }

        fun notify(project: Project, message: String, type: NotificationType) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, type)
                .notify(project)
        }
    }

    /** 发送结果类型 */
    sealed class BridgeResult {
        data object Success : BridgeResult()
        data object Scheduled : BridgeResult()
        data class Error(val message: String) : BridgeResult()
    }

    /** 终端类型抽象：经典 / 前端（tab 在运行时为 TerminalToolWindowTab 类型） */
    private sealed class TargetTerminal {
        data class Classic(val widget: TerminalWidget) : TargetTerminal()
        data class LegacyReworked(val widget: TerminalWidget) : TargetTerminal()
        data class Frontend(val tab: Any) : TargetTerminal()
    }
}
