// Terminal file-link overlays — floating diamonds next to file references in the terminal editor
package io.github.q110.aiterminaltools.bridge

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.Icon
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.Timer

@Service(Service.Level.PROJECT)
class AiTerminalFileLinkService(
    private val project: Project
) : Disposable {

    private val log = Logger.getInstance(AiTerminalFileLinkService::class.java)
    private val managedEditors = Collections.synchronizedMap(IdentityHashMap<Editor, EditorTracker>())
    private val managedTerminalComponents = Collections.synchronizedMap(IdentityHashMap<JComponent, TerminalComponentTracker>())
    private val overlayIcon = IconLoader.getIcon("/icons/send-selection.svg", AiTerminalFileLinkService::class.java)

    init {
        val editorFactory = com.intellij.openapi.editor.EditorFactory.getInstance()
        editorFactory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                managedEditors.remove(event.editor)?.clearAll()
            }
        }, this)
        // Re-register terminals that were already open when the plugin was installed or reloaded.
        ApplicationManager.getApplication().invokeLater {
            try {
                FrontendTerminalHelper(project).allTerminals().forEach { setupFrontendTab(it) }
            } catch (_: Throwable) {
                // The frontend terminal is optional on older IDE builds.
            }
        }
    }

    fun setupWidget(widget: TerminalWidget) {
        log.info("File-link setup requested for reworked terminal ${widget.javaClass.name}")
        scheduleMultiSetup("reworked terminal", { getReworkedEditors(widget) }, 0)
    }

    fun setupFrontendTab(tab: Any) {
        log.info("File-link setup requested for frontend terminal ${tab.javaClass.name}")
        scheduleMultiSetup("frontend terminal", {
            getFrontendEditors(tab)
        }, 0, onEmpty = {
            getFrontendComponent(tab)?.let { setupTerminalComponent(it, tab) }
        })
    }

    private fun scheduleMultiSetup(
        target: String,
        editorLookup: () -> List<Editor>,
        attempt: Int,
        onEmpty: () -> Unit = {}
    ) {
        if (project.isDisposed || attempt >= MAX_SETUP_ATTEMPTS) return
        log.debug("File-link setup attempt ${attempt + 1}/$MAX_SETUP_ATTEMPTS for $target")
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val editors = editorLookup()
            if (editors.isNotEmpty()) {
                log.info("File-link editor lookup succeeded for $target: ${editors.size} editor(s)")
                editors.forEach { setupEditor(it) }
            } else if (attempt + 1 < MAX_SETUP_ATTEMPTS) {
                onEmpty()
                Timer(SETUP_RETRY_DELAY_MS) {
                    scheduleMultiSetup(target, editorLookup, attempt + 1, onEmpty)
                }.apply {
                    isRepeats = false
                    start()
                }
            } else {
                onEmpty()
                log.warn("File-link setup failed for $target after $MAX_SETUP_ATTEMPTS attempts")
            }
        }
    }

    private fun setupEditor(editor: Editor) {
        if (editor in managedEditors) return
        log.info("Managing terminal file-link editor ${editor.javaClass.name}")
        val tracker = EditorTracker(editor)
        managedEditors[editor] = tracker

        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                tracker.scheduleRescan(this@AiTerminalFileLinkService)
            }
        }, this)

        tracker.scheduleRescan(this)
    }

    private fun setupTerminalComponent(component: JComponent, tab: Any) {
        if (component in managedTerminalComponents) return
        log.info("Managing frontend terminal component ${component.javaClass.name} for ${tab.javaClass.name}")
        val tracker = TerminalComponentTracker(component, tab)
        managedTerminalComponents[component] = tracker
        tracker.start()
    }

    private fun getFrontendComponent(tab: Any): JComponent? {
        return try {
            val view = tab.javaClass.getMethod("getView").invoke(tab) ?: return null
            view.javaClass.getMethod("getComponent").invoke(view) as? JComponent
        } catch (_: Throwable) {
            null
        }
    }

    private fun rescan(editor: Editor, tracker: EditorTracker) {
        if (editor.isDisposed || project.isDisposed) return

        val settings = AiTerminalToolsSettings.getInstance().getState()
        if (!settings.fileLinksEnabled) {
            tracker.clearAll()
            return
        }

        val text = editor.document.charsSequence.toString()
        ReadAction.nonBlocking(Callable { findFileReferences(text) })
            .finishOnUiThread(ModalityState.any()) { refs ->
                if (editor.isDisposed || project.isDisposed) return@finishOnUiThread
                val added = tracker.update(editor, refs, project, overlayIcon)
                if (refs.isNotEmpty()) {
                    log.info("File-link scan: ${refs.size} references, $added overlays added")
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
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
        managedTerminalComponents.values.forEach { it.clearAll() }
        managedTerminalComponents.clear()
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
            if (files.isEmpty()) null else FileReferenceHyperlinkInfo(
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
            } catch (exception: Throwable) {
                log.warn("Failed to add terminal file-link overlay at offset ${ref.offset}: ${exception.message}")
                0
            }
        }

        fun clearAll() {
            overlayLabels.values.forEach { editor.contentComponent.remove(it) }
            overlayLabels.clear()
            editor.contentComponent.repaint()
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

    private inner class TerminalComponentTracker(
        private val component: JComponent,
        private val tab: Any
    ) {
        private val overlayLabels = mutableMapOf<Int, JLabel>()
        private val timer = Timer(RESCAN_DEBOUNCE_MS) { rescan() }

        fun start() {
            timer.isRepeats = true
            timer.start()
            rescan()
        }

        private fun rescan() {
            if (project.isDisposed || !component.isDisplayable) return
            val settings = AiTerminalToolsSettings.getInstance().getState()
            if (!settings.fileLinksEnabled) {
                clearAll()
                return
            }

            try {
                val view = tab.javaClass.getMethod("getView").invoke(tab) ?: return
                val models = view.javaClass.getMethod("getOutputModels").invoke(view) ?: return
                val active = models.javaClass.getMethod("getActive").invoke(models)
                val model = active.javaClass.getMethod("getValue").invoke(active) ?: return
                val snapshot = model.javaClass.getMethod("takeSnapshot").invoke(model)
                val start = snapshot.javaClass.getMethod("getStartOffset").invoke(snapshot)
                val end = snapshot.javaClass.getMethod("getEndOffset").invoke(snapshot)
                val getText = snapshot.javaClass.methods.firstOrNull {
                    it.name == "getText" && it.parameterTypes.size == 2
                } ?: return
                val text = getText.invoke(snapshot, start, end) as? CharSequence ?: return
                ReadAction.nonBlocking(Callable { findFileReferences(text) })
                    .finishOnUiThread(ModalityState.any()) { refs ->
                        if (!project.isDisposed && component.isDisplayable) update(text.toString(), refs)
                    }
                    .submit(AppExecutorUtil.getAppExecutorService())
            } catch (exception: Throwable) {
                // The frontend terminal API is still evolving; retry on the next tick.
                if (lastErrorType != exception.javaClass.name) {
                    lastErrorType = exception.javaClass.name
                    log.warn("Frontend terminal output lookup failed for ${tab.javaClass.name}: ${exception.message}")
                }
            }
        }

        private var lastErrorType: String? = null

        private fun update(text: String, refs: List<FileRef>) {
            if (text.isNotEmpty() && (refs.isNotEmpty() || lastReferenceCount != refs.size)) {
                log.info(
                    "Frontend terminal scan: ${text.length} chars, ${refs.size} project file reference(s), " +
                        "component=${component.width}x${component.height}"
                )
                lastReferenceCount = refs.size
            }
            val expectedOffsets = refs.mapTo(mutableSetOf()) { it.offset }
            overlayLabels.entries.removeIf { (offset, label) ->
                if (offset !in expectedOffsets) {
                    component.remove(label)
                    true
                } else false
            }

            val metrics = component.getFontMetrics(component.font)
            val charWidth = metrics.charWidth('M').coerceAtLeast(1)
            val lineHeight = metrics.height.coerceAtLeast(1)
            val lineCount = text.count { it == '\n' } + 1
            val visibleLines = (component.height / lineHeight).coerceAtLeast(1)
            val firstVisibleLine = (lineCount - visibleLines).coerceAtLeast(0)

            for (ref in refs) {
                if (ref.offset in overlayLabels) continue
                val info = createHyperlinkInfo(ref, project) ?: continue
                val line = text.take(ref.offset).count { it == '\n' }
                if (line < firstVisibleLine) continue
                val lineStart = text.lastIndexOf('\n', ref.offset - 1) + 1
                val column = ref.offset - lineStart
                val x = column * charWidth
                val y = (line - firstVisibleLine) * lineHeight
                val icon = overlayIcon
                val overlayIcon = OpacityIcon(icon, OVERLAY_ICON_OPACITY)
                val label = JLabel(overlayIcon).apply {
                    border = BorderFactory.createEmptyBorder(OVERLAY_PADDING, OVERLAY_PADDING, OVERLAY_PADDING, OVERLAY_PADDING)
                    bounds = Rectangle(
                        x - icon.iconWidth - (OVERLAY_PADDING * 2),
                        y - OVERLAY_PADDING,
                        icon.iconWidth + (OVERLAY_PADDING * 2),
                        icon.iconHeight + (OVERLAY_PADDING * 2)
                    )
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Open ${ref.fileName} (overlay)"
                }
                label.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = info.navigate(project)
                    override fun mouseEntered(e: MouseEvent) {
                        overlayIcon.opacity = 1.0f
                        label.repaint()
                    }
                    override fun mouseExited(e: MouseEvent) {
                        overlayIcon.opacity = OVERLAY_ICON_OPACITY
                        label.repaint()
                    }
                })
                component.add(label)
                overlayLabels[ref.offset] = label
            }
            component.revalidate()
            component.repaint()
        }

        fun clearAll() {
            timer.stop()
            overlayLabels.values.forEach { component.remove(it) }
            overlayLabels.clear()
            component.revalidate()
            component.repaint()
        }

        private var lastReferenceCount = -1
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
                for (fieldName in listOf("outputEditor", "alternateBufferEditor")) {
                    try {
                        val field = findField(view.javaClass, fieldName) ?: continue
                        field.isAccessible = true
                        (field.get(view) as? Editor)?.let { editor ->
                            if (editors.none { it === editor }) editors.add(editor)
                        }
                    } catch (_: Throwable) {}
                }
                // Preview builds have moved these fields between implementation classes.
                var current: Class<*>? = view.javaClass
                while (current != null) {
                    for (field in current.declaredFields) {
                        if (!Editor::class.java.isAssignableFrom(field.type)) continue
                        try {
                            field.isAccessible = true
                            (field.get(view) as? Editor)?.let { editor ->
                                if (editors.none { it === editor }) editors.add(editor)
                            }
                        } catch (_: Throwable) {}
                    }
                    current = current.superclass
                }
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
    }
}
