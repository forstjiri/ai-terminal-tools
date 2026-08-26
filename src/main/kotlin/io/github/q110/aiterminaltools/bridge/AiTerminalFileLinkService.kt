// Terminal file-link overlays — floating diamonds next to file references in the terminal editor
package io.github.q110.aiterminaltools.bridge

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.terminal.ui.TerminalWidget
import io.github.q110.aiterminaltools.filter.FilterPatterns
import io.github.q110.aiterminaltools.filter.displayPath
import io.github.q110.aiterminaltools.filter.findProjectPath
import io.github.q110.aiterminaltools.filter.isPathReference
import io.github.q110.aiterminaltools.filter.normalizePath
import io.github.q110.aiterminaltools.filter.pathMatches
import io.github.q110.aiterminaltools.jump.FileReferenceHyperlinkInfo
import io.github.q110.aiterminaltools.jump.FolderReferenceHyperlinkInfo
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import java.awt.AlphaComposite
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import javax.swing.Icon
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.Timer

@Service(Service.Level.PROJECT)
class AiTerminalFileLinkService(
    private val project: Project
) : Disposable {

    private val log = Logger.getInstance(AiTerminalFileLinkService::class.java)
    private val managedEditors = Collections.synchronizedMap(IdentityHashMap<Editor, EditorTracker>())
    private val overlayIcon = IconLoader.getIcon("/icons/send-selection.svg", AiTerminalFileLinkService::class.java)

    init {
        val editorFactory = com.intellij.openapi.editor.EditorFactory.getInstance()
        editorFactory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                managedEditors.remove(event.editor)?.clearAll()
            }
        }, this)
    }

    fun setupWidget(widget: TerminalWidget) {
        scheduleMultiSetup("reworked terminal", { getReworkedEditors(widget) }, 0)
    }

    fun setupFrontendTab(tab: Any) {
        scheduleMultiSetup("frontend terminal", { getFrontendEditors(tab) }, 0)
    }

    private fun scheduleMultiSetup(target: String, editorLookup: () -> List<Editor>, attempt: Int) {
        if (project.isDisposed || attempt >= MAX_SETUP_ATTEMPTS) return
        log.debug("File-link setup attempt ${attempt + 1}/$MAX_SETUP_ATTEMPTS for $target")
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val editors = editorLookup()
            if (editors.isNotEmpty()) {
                log.debug("File-link editor lookup succeeded for $target: ${editors.size} editor(s)")
                editors.forEach { setupEditor(it) }
            } else if (attempt + 1 < MAX_SETUP_ATTEMPTS) {
                Timer(SETUP_RETRY_DELAY_MS) {
                    scheduleMultiSetup(target, editorLookup, attempt + 1)
                }.apply {
                    isRepeats = false
                    start()
                }
            } else {
                log.warn("File-link setup failed for $target after $MAX_SETUP_ATTEMPTS attempts")
            }
        }
    }

    private fun setupEditor(editor: Editor) {
        if (editor in managedEditors) return
        val tracker = EditorTracker(editor)
        managedEditors[editor] = tracker

        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                tracker.scheduleRescan(this@AiTerminalFileLinkService)
            }
        }, this)

        tracker.scheduleRescan(this)
    }

    private fun rescan(editor: Editor, tracker: EditorTracker) {
        if (editor.isDisposed || project.isDisposed) return

        val settings = AiTerminalToolsSettings.getInstance().getState()
        if (!settings.fileLinksEnabled) {
            tracker.clearAll()
            return
        }

        val refs = ReadAction.nonBlocking(Callable {
            findFileReferences(editor.document.charsSequence)
        }).executeSynchronously()

        val added = tracker.update(editor, refs, project, overlayIcon)
        if (refs.isNotEmpty()) {
            log.debug("File-link scan: ${refs.size} references, $added overlays added")
        }
    }

    private fun findFileReferences(text: CharSequence): List<FileRef> {
        val refs = mutableListOf<FileRef>()
        val claimedRanges = mutableListOf<IntRange>()

        for (match in FilterPatterns.atPathRefPattern.findAll(text)) {
            val reference = normalizePath(match.groupValues[1])
            val target = ReadAction.nonBlocking(Callable {
                findProjectPath(project, reference)
            }).executeSynchronously() ?: continue

            val hasLineNumber = match.groupValues[2].isNotEmpty()
            val lineNumber = match.groupValues[2].toIntOrNull() ?: 1
            val endLineNumber = match.groupValues[3].toIntOrNull()
            claimedRanges += match.range
            refs.add(
                FileRef(
                    offset = match.range.first,
                    reference = reference,
                    fileName = reference.substringAfterLast('/'),
                    requestedPath = reference,
                    hasLineNumber = hasLineNumber,
                    lineNumber = lineNumber,
                    endLineNumber = endLineNumber,
                    target = target
                )
            )
        }

        for (match in FilterPatterns.fileRefPattern.findAll(text)) {
            if (claimedRanges.any { it.first <= match.range.last && match.range.first <= it.last }) continue

            val reference = normalizePath(match.groupValues[1])
            val fileName = reference.substringAfterLast('/')
            val requestedPath = if (isPathReference(reference)) reference else null
            val hasLineNumber = match.groupValues[2].isNotEmpty()
            val lineNumber = match.groupValues[2].toIntOrNull() ?: 1
            val endLineNumber = match.groupValues[3].toIntOrNull()

            val files = ReadAction.nonBlocking(Callable {
                FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
                    .filter { it.isValid && !it.isDirectory }
                    .sortedBy { displayPath(project, it) }
                    .let { all ->
                        if (requestedPath == null) all else all.filter { pathMatches(project, it, requestedPath) }
                    }
            }).executeSynchronously()
            if (files.isEmpty()) continue

            claimedRanges += match.range
            refs.add(
                FileRef(
                    offset = match.range.first,
                    reference = reference,
                    fileName = fileName,
                    requestedPath = requestedPath,
                    hasLineNumber = hasLineNumber,
                    lineNumber = lineNumber,
                    endLineNumber = endLineNumber,
                    target = files.first()
                )
            )
        }

        return refs
    }

    override fun dispose() {
        managedEditors.values.forEach { it.clearAll() }
        managedEditors.clear()
    }

    private inner class EditorTracker(val editor: Editor) {
        private val overlayLabels = mutableMapOf<Int, JLabel>()
        @Volatile
        private var rescanPending = false

        fun scheduleRescan(service: AiTerminalFileLinkService) {
            if (rescanPending) return
            rescanPending = true
            Timer(RESCAN_DEBOUNCE_MS) {
                rescanPending = false
                service.rescan(editor, this@EditorTracker)
            }.apply {
                isRepeats = false
                start()
            }
        }

        fun update(editor: Editor, refs: List<FileRef>, project: Project, icon: Icon): Int {
            val expectedOffsets = refs.mapTo(mutableSetOf()) { it.offset }

            overlayLabels.entries.removeIf { (offset, label) ->
                if (offset !in expectedOffsets) { editor.contentComponent.remove(label); true } else false
            }

            var added = 0
            for (ref in refs) {
                if (ref.offset in overlayLabels) continue

                val hyperlinkInfo = createHyperlinkInfo(ref, project) ?: continue
                added += addOverlay(editor, ref, hyperlinkInfo, project, icon)
            }

            if (overlayLabels.isNotEmpty()) {
                editor.contentComponent.revalidate()
                editor.contentComponent.repaint()
            }
            return added
        }

        private fun addOverlay(editor: Editor, ref: FileRef, info: HyperlinkInfo, project: Project, icon: Icon): Int {
            return try {
                val point = editor.offsetToXY(ref.offset)
                val overlayIcon = OpacityIcon(icon, OVERLAY_ICON_OPACITY)
                val label = JLabel(overlayIcon).apply {
                    border = BorderFactory.createEmptyBorder(OVERLAY_PADDING, OVERLAY_PADDING, OVERLAY_PADDING, OVERLAY_PADDING)
                    bounds = Rectangle(
                        point.x - icon.iconWidth - (OVERLAY_PADDING * 2),
                        point.y - OVERLAY_PADDING,
                        icon.iconWidth + (OVERLAY_PADDING * 2),
                        icon.iconHeight + (OVERLAY_PADDING * 2)
                    )
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Open ${ref.fileName} (overlay)"
                }
                label.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        info.navigate(project)
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        overlayIcon.opacity = 1.0f
                        label.repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        overlayIcon.opacity = OVERLAY_ICON_OPACITY
                        label.repaint()
                    }
                })
                editor.contentComponent.add(label)
                overlayLabels[ref.offset] = label
                1
            } catch (_: Throwable) { 0 }
        }

        fun clearAll() {
            overlayLabels.values.forEach { editor.contentComponent.remove(it) }
            overlayLabels.clear()
            editor.contentComponent.repaint()
        }

        private fun createHyperlinkInfo(ref: FileRef, project: Project): HyperlinkInfo? {
            return if (ref.target.isDirectory) {
                FolderReferenceHyperlinkInfo(project, ref.target)
            } else {
                val files = ReadAction.nonBlocking(Callable {
                    val all = FilenameIndex.getVirtualFilesByName(
                        ref.fileName,
                        GlobalSearchScope.projectScope(project)
                    ).filter { it.isValid && !it.isDirectory }
                        .sortedBy { displayPath(project, it) }
                    if (ref.requestedPath != null) all.filter { pathMatches(project, it, ref.requestedPath) } else all
                }).executeSynchronously()
                if (files.isEmpty()) return null
                FileReferenceHyperlinkInfo(
                    project,
                    files,
                    ref.fileName,
                    ref.requestedPath,
                    ref.hasLineNumber,
                    ref.lineNumber,
                    ref.endLineNumber,
                    emptyMap()
                )
            }
        }
    }

    private class OpacityIcon(private val delegate: Icon, var opacity: Float) : Icon {
        override fun getIconWidth(): Int = delegate.iconWidth

        override fun getIconHeight(): Int = delegate.iconHeight

        override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
            val graphicsCopy = graphics.create() as Graphics
            try {
                (graphicsCopy as java.awt.Graphics2D).composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity)
                delegate.paintIcon(component, graphicsCopy, x, y)
            } finally {
                graphicsCopy.dispose()
            }
        }
    }

    // ─── Data ───

    internal data class FileRef(
        val offset: Int,
        val reference: String,
        val fileName: String,
        val requestedPath: String?,
        val hasLineNumber: Boolean,
        val lineNumber: Int,
        val endLineNumber: Int?,
        val target: VirtualFile
    )

    companion object {
        private const val SETUP_RETRY_DELAY_MS = 500
        private const val MAX_SETUP_ATTEMPTS = 21
        private const val RESCAN_DEBOUNCE_MS = 300
        private const val OVERLAY_PADDING = 4
        private const val OVERLAY_ICON_OPACITY = 0.4f

        private fun getReworkedEditors(widget: TerminalWidget): List<Editor> {
            val editors = mutableListOf<Editor>()
            try {
                val viewField = widget.javaClass.getDeclaredField("view")
                viewField.isAccessible = true
                val view = viewField.get(widget) ?: return editors
                val outputView = view.javaClass.getMethod("getOutputView").invoke(view)
                if (outputView != null) {
                    val editorField = outputView.javaClass.getDeclaredField("editor")
                    editorField.isAccessible = true
                    (editorField.get(outputView) as? Editor)?.let { editors.add(it) }
                }
                try {
                    val altField = view.javaClass.getDeclaredField("alternateBufferView")
                    altField.isAccessible = true
                    val altView = altField.get(view)
                    if (altView != null) {
                        val altEditorField = altView.javaClass.getDeclaredField("editor")
                        altEditorField.isAccessible = true
                        (altEditorField.get(altView) as? Editor)?.let { alt ->
                            if (editors.none { it === alt }) editors.add(alt)
                        }
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            return editors
        }

        private fun getFrontendEditors(tab: Any): List<Editor> {
            val editors = mutableListOf<Editor>()
            try {
                val view = tab.javaClass.getMethod("getView").invoke(tab) ?: return editors
                try {
                    val ed = view.javaClass.getMethod("getOutputEditor").invoke(view) as? Editor
                    if (ed != null) editors.add(ed)
                } catch (_: Throwable) {}
                try {
                    val altField = view.javaClass.getDeclaredField("alternateBufferEditor")
                    altField.isAccessible = true
                    (altField.get(view) as? Editor)?.let { alt ->
                        if (editors.none { it === alt }) editors.add(alt)
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            return editors
        }
    }
}
