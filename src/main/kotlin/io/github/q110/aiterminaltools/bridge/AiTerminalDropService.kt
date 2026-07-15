// Terminal drag-and-drop service — sends paths dropped onto the active terminal tab to that terminal.
package io.github.q110.aiterminaltools.bridge

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
class AiTerminalDropService(
    private val project: Project
) : Disposable {
    private val contentManagerListeners = mutableMapOf<ContentManager, ContentManagerListener>()
    private val dropTargetDisposables = mutableMapOf<Content, Disposable>()
    private var initialized = false

    fun initialize() {
        if (initialized) {
            return
        }
        initialized = true

        project.messageBus.connect(this)
            .subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == TERMINAL_TOOL_WINDOW_ID) {
                        installForToolWindow(toolWindow)
                    }
                }

                override fun toolWindowsRegistered(ids: List<String>, toolWindowManager: ToolWindowManager) {
                    if (TERMINAL_TOOL_WINDOW_ID in ids) {
                        installForCurrentTerminalToolWindow()
                    }
                }
            })

        ApplicationManager.getApplication().invokeLater {
            installForCurrentTerminalToolWindow()
        }
    }

    fun refreshDropTarget() {
        val targetContent = currentActiveContent()
        val dropEnabled = targetContent != null && (dragToAiTerminalEnabled() || isRecordedAiTerminalContent(targetContent))

        dropTargetDisposables.keys
            .filter { it !== targetContent || !dropEnabled }
            .toList()
            .forEach { disposeDropTarget(it) }

        if (dropEnabled) {
            installForContent(targetContent!!)
        }
    }

    private fun installForCurrentTerminalToolWindow() {
        val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow
            ?: ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: return
        installForToolWindow(toolWindow)
    }

    private fun installForToolWindow(toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        if (!contentManagerListeners.containsKey(contentManager)) {
            val listener = object : ContentManagerListener {
                override fun contentAdded(event: ContentManagerEvent) {
                    refreshDropTarget()
                }

                override fun contentRemoved(event: ContentManagerEvent) {
                    project.service<AiTerminalBridgeService>().unregisterAiTerminalContent(event.content)
                    disposeDropTarget(event.content)
                    refreshDropTarget()
                }

                override fun selectionChanged(event: ContentManagerEvent) {
                    refreshDropTarget()
                }
            }
            contentManager.addContentManagerListener(listener)
            contentManagerListeners[contentManager] = listener
        }
        refreshDropTarget()
    }

    private fun currentActiveContent(): Content? {
        val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow
            ?: ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: return null
        return toolWindow.contentManager.selectedContent
    }

    private fun installForContent(content: Content) {
        if (!dragToAiTerminalEnabled() && !isRecordedAiTerminalContent(content)) {
            return
        }
        val component = content.component ?: return
        if (dropTargetDisposables.containsKey(content)) {
            return
        }

        val disposable = Disposer.newDisposable("AI terminal drop target")
        dropTargetComponents(content, component).forEach { targetComponent ->
            installDropTarget(targetComponent, content, disposable)
        }

        dropTargetDisposables[content] = disposable
    }

    private fun installDropTarget(component: JComponent, content: Content, disposable: Disposable) {
        DnDSupport.createBuilder(component)
            .disableAsSource()
            .enableAsNativeTarget()
            .setTargetChecker { event ->
                val files = draggedFiles(event)
                val canDrop = canHandleDrop(files, content)
                if (canDrop) {
                    event.setDropPossible(true, "Send Path to AI Terminal")
                }
                canDrop
            }
            .setDropHandler { event ->
                val files = draggedFiles(event)
                if (!canHandleDrop(files, content)) {
                    return@setDropHandler
                }
                val result = project.service<AiTerminalBridgeService>().sendDroppedPaths(files)
                if (result is AiTerminalBridgeService.BridgeResult.Error) {
                    AiTerminalBridgeService.notify(project, result.message, NotificationType.WARNING)
                }
            }
            .setDisposableParent(disposable)
            .install()
    }

    private fun dropTargetComponents(content: Content, component: JComponent): List<JComponent> {
        val components = mutableListOf<JComponent>()
        components += component
        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        if (widget != null) {
            try {
                components += widget.component
            } catch (_: Throwable) {
            }
            try {
                components += widget.preferredFocusableComponent
            } catch (_: Throwable) {
            }
        }
        return components.filter { it.isShowing || it === component }.distinct()
    }

    private fun canHandleDrop(files: List<VirtualFile>, content: Content): Boolean {
        return files.isNotEmpty() &&
            currentActiveContent() === content &&
            (dragToAiTerminalEnabled() || isRecordedAiTerminalContent(content))
    }

    private fun dragToAiTerminalEnabled(): Boolean {
        return AiTerminalToolsSettings.getInstance().getState().isDragToAiTerminalEnabled()
    }

    private fun isRecordedAiTerminalContent(content: Content): Boolean {
        return project.service<AiTerminalBridgeService>().isRecordedAiTerminalContent(content)
    }

    private fun disposeDropTarget(content: Content) {
        val disposable = dropTargetDisposables.remove(content) ?: return
        Disposer.dispose(disposable)
    }

    private fun draggedFiles(event: DnDEvent): List<VirtualFile> {
        val attachedFiles = try {
            FileCopyPasteUtil.getVirtualFileListFromAttachedObject(event.attachedObject)
        } catch (_: Throwable) {
            emptyList()
        }
        if (attachedFiles.isNotEmpty()) {
            return attachedFiles.filter { it.isValid }
        }

        val localFiles = try {
            FileCopyPasteUtil.getFileList(event)
        } catch (_: Throwable) {
            emptyList()
        }
        return localFiles.orEmpty()
            .mapNotNull { LocalFileSystem.getInstance().findFileByIoFile(it) }
            .filter { it.isValid }
    }

    override fun dispose() {
        dropTargetDisposables.keys.toList().forEach { disposeDropTarget(it) }
        contentManagerListeners.forEach { (contentManager, listener) ->
            contentManager.removeContentManagerListener(listener)
        }
        contentManagerListeners.clear()
    }

    companion object {
        private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
    }
}
