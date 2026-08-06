// AI Terminal bridge service — writes directly to the active terminal input.
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
import io.github.q110.aiterminaltools.ProjectBasePath
import io.github.q110.aiterminaltools.monitor.AiTerminalTabContext
import io.github.q110.aiterminaltools.monitor.AiTool
import io.github.q110.aiterminaltools.monitor.AiTurnEventServer
import io.github.q110.aiterminaltools.monitor.AiTurnHookInstaller
import io.github.q110.aiterminaltools.monitor.AiTurnOpenCodeInstaller
import io.github.q110.aiterminaltools.monitor.AiTurnPiInstaller
import io.github.q110.aiterminaltools.monitor.AiTurnMonitorService
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
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
    /** New terminal helper, loadable only in IDE 2025.3+; null on older versions. */
    private val frontendHelper: FrontendTerminalHelper? = try {
        FrontendTerminalHelper(project)
    } catch (_: Throwable) {
        null
    }

    private val legacyReworkedTerminalHelper = LegacyReworkedTerminalHelper(project)
    private val log = Logger.getInstance(AiTerminalBridgeService::class.java)
    private val openCodeTerminalStartInProgress = AtomicBoolean(false)
    private val claudeCodeTerminalStartInProgress = AtomicBoolean(false)
    private val piTerminalStartInProgress = AtomicBoolean(false)
    private val aiFrontendTerminals: MutableMap<Any, AiTool> = Collections.synchronizedMap(IdentityHashMap())
    private val aiLegacyReworkedTerminals: MutableMap<TerminalWidget, AiTool> = Collections.synchronizedMap(IdentityHashMap())
    private val aiClassicTerminals: MutableMap<TerminalWidget, AiTool> = Collections.synchronizedMap(IdentityHashMap())

    /** Writes directly to the active AI terminal input. */
    fun sendDirectInput(payload: String, dataContext: DataContext, settleAtLineEnd: Boolean = false): BridgeResult {
        val terminal = resolveTargetTerminal(dataContext)
            ?: return BridgeResult.Error(NO_ACTIVE_TERMINAL_MESSAGE)

        return injectDirectInput(terminal, payload, settleAtLineEnd)
    }

    /** Uses bracketed paste to write multiline content without treating newlines as submissions. */
    fun sendDirectPaste(payload: String, dataContext: DataContext): BridgeResult {
        val terminal = resolveTargetTerminal(dataContext)
            ?: return BridgeResult.Error(NO_ACTIVE_TERMINAL_MESSAGE)

        return injectDirectInput(terminal, bracketedPaste(payload), settleAtLineEnd = false)
    }

    /** Uses bracketed paste to write multiline content to the selected terminal created by this plugin. */
    fun sendDirectPasteToSelectedAiTerminal(payload: String): BridgeResult {
        val terminal = selectedTerminal()?.takeIf { isUsable(it) && isRecordedAiTerminal(it) }
            ?: return BridgeResult.Error(NO_ACTIVE_TERMINAL_MESSAGE)

        return injectDirectInput(terminal, bracketedPaste(payload), settleAtLineEnd = false)
    }

    /** Combines dragged paths into one input to avoid lag from repeated events. */
    fun sendDroppedPaths(files: List<VirtualFile>): BridgeResult {
        val payload = files.filter { it.isValid }
            .joinToString(separator = " ") { pathPayload(it) }
        if (payload.isBlank()) {
            return BridgeResult.Error("No file or folder to send was found.")
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
        if (frontendHelper != null && aiFrontendTerminals.keys.any { isFrontendContentOf(frontendHelper, it, content) }) {
            return true
        }

        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        return widget != null && (widget in aiLegacyReworkedTerminals || widget in aiClassicTerminals)
    }

    internal fun unregisterAiTerminalContent(content: Content) {
        frontendHelper?.let { helper ->
            aiFrontendTerminals.keys.removeAll { isFrontendContentOf(helper, it, content) }
        }

        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        if (widget != null) {
            aiLegacyReworkedTerminals.remove(widget)
            aiClassicTerminals.remove(widget)
        }
    }

    /** Creates a new OpenCode terminal and starts opencode. */
    fun startOpenCodeTerminal(virtualFileHint: VirtualFile? = null): BridgeResult {
        if (!openCodeTerminalStartInProgress.compareAndSet(false, true)) {
            return BridgeResult.Scheduled
        }
        val existing = findExistingTerminal(AiTool.OPENCODE)
        if (existing != null) {
            activateTerminal(existing)
            openCodeTerminalStartInProgress.set(false)
            return BridgeResult.Success
        }
        scheduleOpenCodeTerminalStart(virtualFileHint)
        return BridgeResult.Scheduled
    }

    /** Creates a new Claude Code terminal and starts claude. */
    fun startClaudeCodeTerminal(virtualFileHint: VirtualFile? = null): BridgeResult {
        if (!claudeCodeTerminalStartInProgress.compareAndSet(false, true)) {
            return BridgeResult.Scheduled
        }
        val existing = findExistingTerminal(AiTool.CLAUDE_CODE)
        if (existing != null) {
            activateTerminal(existing)
            claudeCodeTerminalStartInProgress.set(false)
            return BridgeResult.Success
        }
        scheduleClaudeCodeTerminalStart(virtualFileHint)
        return BridgeResult.Scheduled
    }

    /** Creates a new Pi terminal and starts pi. */
    fun startPiTerminal(virtualFileHint: VirtualFile? = null): BridgeResult {
        if (!piTerminalStartInProgress.compareAndSet(false, true)) {
            return BridgeResult.Scheduled
        }
        val existing = findExistingTerminal(AiTool.PI)
        if (existing != null) {
            activateTerminal(existing)
            piTerminalStartInProgress.set(false)
            return BridgeResult.Success
        }
        schedulePiTerminalStart(virtualFileHint)
        return BridgeResult.Scheduled
    }

    private fun scheduleOpenCodeTerminalStart(virtualFileHint: VirtualFile?) {
        val workingDirectory = try {
            ProjectBasePath.resolveTerminalExecutionRoot(project, virtualFileHint)
        } catch (exception: Throwable) {
            openCodeTerminalStartInProgress.set(false)
            notify(project, exception.message.orEmpty(), NotificationType.WARNING)
            return
        }
        // OpenCode: inject monitoring context and start through the launcher script.
        val tabId = UUID.randomUUID().toString()
        val token = generateSecureToken()

        val port = try {
            project.service<AiTurnEventServer>().ensureStarted()
        } catch (exception: Throwable) {
            log.error("Failed to start AiTurnEventServer", exception)
            openCodeTerminalStartInProgress.set(false)
            notify(project, "Failed to start the AI Turn Event Server: ${exception.message}", NotificationType.WARNING)
            return
        }

        val launcherCommand = try {
            val installer = AiTurnOpenCodeInstaller(project)
            val launcherPaths = installer.installOpenCodePlugin(workingDirectory, tabId, token, port)
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

        val tabContext = AiTerminalTabContext(
            tabId = tabId,
            token = token,
            tool = AiTool.OPENCODE,
            workingDirectory = workingDirectory,
            createdAtMillis = System.currentTimeMillis()
        )
        project.service<AiTurnMonitorService>().registerTab(tabContext)

        scheduleTerminalStart(
            tabName = nextTerminalTabName(OPEN_CODE_TAB_NAME),
            command = launcherCommand,
            toolName = OPEN_CODE_TAB_NAME,
            tool = AiTool.OPENCODE,
            inProgress = openCodeTerminalStartInProgress,
            projectPath = workingDirectory,
            tabId = tabId,
            cleanup = { AiTurnOpenCodeInstaller(project).cleanupLauncherScripts(workingDirectory, tabId) }
        )
    }

    private fun scheduleClaudeCodeTerminalStart(virtualFileHint: VirtualFile?) {
        val workingDirectory = try {
            ProjectBasePath.resolveTerminalExecutionRoot(project, virtualFileHint)
        } catch (exception: Throwable) {
            claudeCodeTerminalStartInProgress.set(false)
            notify(project, exception.message.orEmpty(), NotificationType.WARNING)
            return
        }
        // Claude Code: inject monitoring context and start through the launcher script.
        val tabId = UUID.randomUUID().toString()
        val token = generateSecureToken()

        val port = try {
            project.service<AiTurnEventServer>().ensureStarted()
        } catch (exception: Throwable) {
            log.error("Failed to start AiTurnEventServer", exception)
            claudeCodeTerminalStartInProgress.set(false)
            notify(project, "Failed to start the AI Turn Event Server: ${exception.message}", NotificationType.WARNING)
            return
        }

        val launcherCommand = try {
            val installer = AiTurnHookInstaller(project)
            val launcherPaths = installer.installClaudeHooks(workingDirectory, tabId, token, port)
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

        val tabContext = AiTerminalTabContext(
            tabId = tabId,
            token = token,
            tool = AiTool.CLAUDE_CODE,
            workingDirectory = workingDirectory,
            createdAtMillis = System.currentTimeMillis()
        )
        project.service<AiTurnMonitorService>().registerTab(tabContext)

        scheduleTerminalStart(
            tabName = nextTerminalTabName(CLAUDE_CODE_TAB_NAME),
            command = launcherCommand,
            toolName = CLAUDE_CODE_TAB_NAME,
            tool = AiTool.CLAUDE_CODE,
            inProgress = claudeCodeTerminalStartInProgress,
            projectPath = workingDirectory,
            tabId = tabId,
            cleanup = { AiTurnHookInstaller(project).cleanupLauncherScripts(workingDirectory, tabId) }
        )
    }

    private fun schedulePiTerminalStart(virtualFileHint: VirtualFile?) {
        val workingDirectory = try {
            ProjectBasePath.resolveTerminalExecutionRoot(project, virtualFileHint)
        } catch (exception: Throwable) {
            piTerminalStartInProgress.set(false)
            notify(project, exception.message.orEmpty(), NotificationType.WARNING)
            return
        }
        val tabId = UUID.randomUUID().toString()
        val token = generateSecureToken()

        val port = try {
            project.service<AiTurnEventServer>().ensureStarted()
        } catch (exception: Throwable) {
            log.error("Failed to start AiTurnEventServer", exception)
            piTerminalStartInProgress.set(false)
            notify(project, "Failed to start the AI Turn Event Server: ${exception.message}", NotificationType.WARNING)
            return
        }

        val launcherCommand = try {
            val installer = AiTurnPiInstaller(project)
            val launcherPaths = installer.installPiExtension(workingDirectory, tabId, token, port)
            if (isWindows()) {
                launcherPaths.cmdPath.toString()
            } else {
                launcherPaths.shPath.toString()
            }
        } catch (exception: Throwable) {
            log.error("Failed to install Pi extension", exception)
            piTerminalStartInProgress.set(false)
            notify(project, "Failed to install the Pi extension: ${exception.message}", NotificationType.WARNING)
            return
        }

        val tabContext = AiTerminalTabContext(
            tabId = tabId,
            token = token,
            tool = AiTool.PI,
            workingDirectory = workingDirectory,
            createdAtMillis = System.currentTimeMillis()
        )
        project.service<AiTurnMonitorService>().registerTab(tabContext)

        scheduleTerminalStart(
            tabName = nextTerminalTabName(PI_TAB_NAME),
            command = launcherCommand,
            toolName = PI_TAB_NAME,
            tool = AiTool.PI,
            inProgress = piTerminalStartInProgress,
            projectPath = workingDirectory,
            tabId = tabId,
            cleanup = { AiTurnPiInstaller(project).cleanupLauncherScripts(workingDirectory, tabId) }
        )
    }

    private fun scheduleTerminalStart(
        tabName: String, command: String, toolName: String, tool: AiTool, inProgress: AtomicBoolean,
        projectPath: Path, tabId: String, cleanup: () -> Unit
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                failTerminalStart(tabId, cleanup, null)
                inProgress.set(false)
                return@invokeLater
            }

            try {
                val terminalToolWindowManager = TerminalToolWindowManager.getInstance(project)
                val toolWindow = terminalToolWindow(terminalToolWindowManager)
                if (toolWindow == null) {
                    failTerminalStart(tabId, cleanup, "Terminal tool window was not found.")
                    inProgress.set(false)
                    return@invokeLater
                }

                toolWindow.activate(Runnable {
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            val workingDirectory = try {
                                ProjectBasePath.requireValid(projectPath)
                            } catch (exception: Throwable) {
                                failTerminalStart(
                                    tabId,
                                    cleanup,
                                    "Could not start $toolName: ${exception.message}. Reopen the project or close the stale project."
                                )
                                return@invokeLater
                            }
                            val workingDirectoryString = workingDirectory.toString()
                            val result = startFrontendTerminal(tabName, workingDirectoryString, command, toolName, tool)
                                ?: if (shouldSkipLegacyReworkedTerminal(toolName)) {
                                    startClassicTerminal(tabName, workingDirectoryString, command, toolName, tool)
                                } else {
                                    startLegacyReworkedTerminal(tabName, workingDirectoryString, command, toolName, tool)
                                        ?: run {
                                            notifyLegacyReworkedFallbackIfNeeded(toolName)
                                            startClassicTerminal(tabName, workingDirectoryString, command, toolName, tool)
                                        }
                                }
                            if (result is BridgeResult.Error) {
                                failTerminalStart(tabId, cleanup, result.message)
                            }
                        } catch (exception: Throwable) {
                            failTerminalStart(tabId, cleanup, "Could not start $toolName: ${exception.message}")
                        } finally {
                            inProgress.set(false)
                        }
                    }
                }, true, true)
            } catch (exception: Throwable) {
                failTerminalStart(tabId, cleanup, "Could not start $toolName: ${exception.message}")
                inProgress.set(false)
            }
        }
    }

    private fun failTerminalStart(tabId: String, cleanup: () -> Unit, message: String?) {
        try {
            cleanup()
        } catch (_: Throwable) {
        }
        if (!project.isDisposed) {
            try {
                project.service<AiTurnMonitorService>().unregisterTab(tabId)
            } catch (_: Throwable) {
            }
            if (message != null) {
                try {
                    notify(project, message, NotificationType.WARNING)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun shouldSkipLegacyReworkedTerminal(toolName: String): Boolean {
        return toolName == OPEN_CODE_TAB_NAME && ideBaselineVersion() in 251..252
    }

    private fun startFrontendTerminal(tabName: String, workingDirectory: String, command: String, toolName: String, tool: AiTool): BridgeResult? {
        val helper = frontendHelper ?: return null
        return try {
            val tab = helper.createAiTerminal(tabName, workingDirectory)
            helper.runCommand(
                tab,
                command,
                "$toolName terminal started",
                "Failed to start $toolName"
            ) {
                registerAiTerminal(TargetTerminal.Frontend(tab), tool)
            }
        } catch (exception: Throwable) {
            notify(project, "The new terminal is unavailable; falling back to Classic Terminal: ${exception.message}", NotificationType.WARNING)
            null
        }
    }

    private fun startLegacyReworkedTerminal(tabName: String, workingDirectory: String, command: String, toolName: String, tool: AiTool): BridgeResult? {
        return try {
            val widget = legacyReworkedTerminalHelper.createAiTerminal(tabName, workingDirectory)
                ?: return null
            legacyReworkedTerminalHelper.runCommand(
                widget = widget,
                command = command,
                successMessage = "$toolName terminal started",
                failurePrefix = "Failed to run $command",
                onCommandSent = {
                    registerAiTerminal(TargetTerminal.LegacyReworked(widget), tool)
                },
                onCommandFailed = {
                    val result = startClassicTerminal(tabName, workingDirectory, command, toolName, tool)
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

    private fun startClassicTerminal(tabName: String, workingDirectory: String, command: String, toolName: String, tool: AiTool): BridgeResult {
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
                registerAiTerminal(TargetTerminal.Classic(widget), tool)
                notify(project, "$toolName terminal started", NotificationType.INFORMATION)
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
                "Started $toolName using Classic Terminal.",
                NotificationType.WARNING
            )
            252 -> notify(
                project,
                "Started $toolName using Classic Terminal.",
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
                    ?: return BridgeResult.Error("The new terminal API is unavailable in this IDE.")
                helper.injectDirectInput(terminal.tab, payload, settleAtLineEnd)
            }
        }
    }

    private fun registerAiTerminal(terminal: TargetTerminal, tool: AiTool) {
        when (terminal) {
            is TargetTerminal.Classic -> aiClassicTerminals[terminal.widget] = tool
            is TargetTerminal.LegacyReworked -> {
                aiLegacyReworkedTerminals[terminal.widget] = tool
                project.service<AiTerminalFileLinkService>().setupWidget(terminal.widget)
            }
            is TargetTerminal.Frontend -> {
                aiFrontendTerminals[terminal.tab] = tool
                project.service<AiTerminalFileLinkService>().setupFrontendTab(terminal.tab)
            }
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
            aiFrontendTerminals.keys.removeAll { !helper.isTabExists(it) }
        }

        aiLegacyReworkedTerminals.keys.removeAll { widget ->
            !legacyReworkedTerminalHelper.isWidgetContentExists(widget)
        }

        aiClassicTerminals.keys.removeAll { widget ->
            try {
                widget.ttyConnector?.isConnected != true
            } catch (_: Throwable) {
                true
            }
        }
    }

    private fun findExistingTerminal(tool: AiTool): TargetTerminal? {
        pruneInvalidAiTerminalRecords()

        for ((tab, tabTool) in aiFrontendTerminals) {
            if (tabTool == tool && isUsable(TargetTerminal.Frontend(tab))) {
                return TargetTerminal.Frontend(tab)
            }
        }
        for ((widget, widgetTool) in aiLegacyReworkedTerminals) {
            if (widgetTool == tool && isUsable(TargetTerminal.LegacyReworked(widget))) {
                return TargetTerminal.LegacyReworked(widget)
            }
        }
        for ((widget, widgetTool) in aiClassicTerminals) {
            if (widgetTool == tool && isUsable(TargetTerminal.Classic(widget))) {
                return TargetTerminal.Classic(widget)
            }
        }
        return null
    }

    private fun activateTerminal(terminal: TargetTerminal) {
        when (terminal) {
            is TargetTerminal.Frontend -> {
                frontendHelper?.selectTab(terminal.tab)
            }
            is TargetTerminal.Classic, is TargetTerminal.LegacyReworked -> {
                val widget = when (terminal) {
                    is TargetTerminal.Classic -> terminal.widget
                    is TargetTerminal.LegacyReworked -> terminal.widget
                    is TargetTerminal.Frontend -> return
                }
                val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
                toolWindow.activate(Runnable {
                    val content = findContentForWidget(toolWindow, widget) ?: return@Runnable
                    toolWindow.contentManager.setSelectedContent(content, true)
                }, true, true)
            }
        }
    }

    private fun findContentForWidget(toolWindow: ToolWindow, targetWidget: TerminalWidget): Content? {
        for (content in toolWindow.contentManager.contents) {
            val widget = TerminalToolWindowManager.findWidgetByContent(content)
            if (widget === targetWidget) return content
        }
        return null
    }

    private fun isFrontendContentOf(helper: FrontendTerminalHelper, tab: Any, content: Content): Boolean {
        return try {
            helper.isContentOf(tab, content)
        } catch (_: Throwable) {
            false
        }
    }

    /** Writes text directly to a Classic Terminal through its TTY connector. */
    private fun injectClassicDirectInput(terminal: TerminalWidget, payload: String, settleAtLineEnd: Boolean): BridgeResult {
        val connector = try {
            terminal.ttyConnector
        } catch (_: Throwable) {
            return BridgeResult.Error("The current Terminal does not expose a writable TTY connection.")
        } ?: return BridgeResult.Error("The current Terminal does not expose a writable TTY connection.")

        if (!connector.isConnected) {
            return BridgeResult.Error("The active Terminal is disconnected.")
        }

        return try {
            terminal.requestFocus()
            connector.write(payload)
            if (settleAtLineEnd) {
                scheduleClassicLineEndSpace { connector.write(LINE_END_SPACE) }
            }
            BridgeResult.Success
        } catch (exception: IOException) {
            BridgeResult.Error("Failed to send input to AI Terminal: ${exception.message}")
        }
    }

    /** Delays the Classic Terminal line-ending space by 300 ms while it processes the input. */
    private fun scheduleClassicLineEndSpace(writeLineEndSpace: () -> Unit) {
        Timer(SETTLE_INPUT_DELAY_MS) {
            try {
                writeLineEndSpace()
            } catch (exception: Throwable) {
                notify(project, "Failed to send the AI Terminal line-ending space: ${exception.message}", NotificationType.WARNING)
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    /** Terminal lookup priority: DataContext, then the currently selected terminal. */
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

    /** Prefer the frontend terminal, then the Classic Terminal. */
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

    /** Checks terminal availability: TTY connection for Classic, tab existence for frontend terminals. */
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
        private const val PI_TAB_NAME = "Pi"
        private const val NO_ACTIVE_TERMINAL_MESSAGE = "Start and activate an OpenCode, Claude Code, or Pi terminal first."
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

    /** Result types for send operations. */
    sealed class BridgeResult {
        data object Success : BridgeResult()
        data object Scheduled : BridgeResult()
        data class Error(val message: String) : BridgeResult()
    }

    /** Terminal abstraction: Classic or frontend (the tab is a TerminalToolWindowTab at runtime). */
    private sealed class TargetTerminal {
        data class Classic(val widget: TerminalWidget) : TargetTerminal()
        data class LegacyReworked(val widget: TerminalWidget) : TargetTerminal()
        data class Frontend(val tab: Any) : TargetTerminal()
    }
}
