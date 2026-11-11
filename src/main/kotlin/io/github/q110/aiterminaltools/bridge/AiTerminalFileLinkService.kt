// Terminal file-link overlays — floating diamonds next to file references in the terminal editor
package io.github.q110.aiterminaltools.bridge

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.impl.EditorHyperlinkSupport
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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.terminal.ui.TerminalWidget
import io.github.q110.aiterminaltools.filter.FilterPatterns
import io.github.q110.aiterminaltools.filter.displayPath
import io.github.q110.aiterminaltools.filter.findProjectPath
import io.github.q110.aiterminaltools.filter.isPathReference
import io.github.q110.aiterminaltools.filter.localPathFromUri
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
import java.util.WeakHashMap
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
    private val watchedFrontendTabs = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val overlayIcon = IconLoader.getIcon("/icons/send-selection.svg", AiTerminalFileLinkService::class.java)
    private val osc8LoggedMessages = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Each distinct OSC 8 outcome is logged once at INFO (deduplicated by message),
     * so recurring rescans do not spam the log but new situations still surface.
     */
    private fun logOsc8Diagnostic(message: String) {
        if (osc8LoggedMessages.size > 32) osc8LoggedMessages.clear()
        if (osc8LoggedMessages.add(message)) {
            log.info("OSC8 file links: $message")
        }
    }

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
        watchFrontendAlternateBufferEditor(tab)
        scheduleMultiSetup("frontend terminal", {
            getFrontendEditors(tab)
        }, 0, frontendTab = tab, onEmpty = {
            getFrontendComponent(tab)?.let { setupTerminalComponent(it, tab) }
        })
    }

    private fun scheduleMultiSetup(
        target: String,
        editorLookup: () -> List<Editor>,
        attempt: Int,
        frontendTab: Any? = null,
        onEmpty: () -> Unit = {}
    ) {
        if (project.isDisposed || attempt >= MAX_SETUP_ATTEMPTS) return
        log.debug("File-link setup attempt ${attempt + 1}/$MAX_SETUP_ATTEMPTS for $target")
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val editors = editorLookup()
            if (editors.isNotEmpty()) {
                log.info("File-link editor lookup succeeded for $target: ${editors.size} editor(s)")
                editors.forEach { setupEditor(it, frontendTab) }
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

    private fun setupEditor(editor: Editor, frontendTab: Any? = null) {
        if (editor in managedEditors) return
        log.info("Managing terminal file-link editor ${editor.javaClass.name}")
        val modelProvider = frontendTab?.let { tab -> { activeOutputModelsSet(tab) } }
        val tracker = EditorTracker(editor, modelProvider)
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
        ReadAction.nonBlocking(Callable { computeReferences(editor, text, tracker) })
            .finishOnUiThread(ModalityState.any()) { refs ->
                if (editor.isDisposed || project.isDisposed) return@finishOnUiThread
                val added = tracker.update(editor, refs, project, overlayIcon)
                if (added > 0) {
                    log.info("File-link scan: ${refs.size} references, $added overlays added")
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Combine text-based, OSC 8 hyperlink, and console hyperlink references; hyperlinks win over text overlaps */
    private fun computeReferences(editor: Editor, text: CharSequence, tracker: EditorTracker): List<FileRef> {
        val osc8Refs = findOsc8References(editor, tracker.modelProvider)
        val consoleRefs = findConsoleHyperlinkReferences(editor).filter { ref ->
            osc8Refs.none { osc -> ref.offset < osc.linkEndOffset && osc.offset < ref.linkEndOffset }
        }
        val textRefs = findFileReferences(text).filter { ref ->
            val overlapping = osc8Refs.any { osc -> ref.offset < osc.linkEndOffset && osc.offset < ref.linkEndOffset } ||
                consoleRefs.any { con -> ref.offset < con.linkEndOffset && con.offset < ref.linkEndOffset }
            !overlapping
        }
        return textRefs + osc8Refs + consoleRefs
    }

    /** Resolve a path from within an already running non-blocking read action */
    private fun resolvePathSync(path: String): VirtualFile? {
        return ReadAction.nonBlocking(Callable {
            findProjectPath(project, path)
        }).executeSynchronously()
    }

    private fun findFileReferences(text: CharSequence): List<FileRef> {
        val refs = mutableListOf<FileRef>()
        val claimedRanges = mutableListOf<IntRange>()

        for (match in FilterPatterns.markdownLinkPattern.findAll(text)) {
            val destination = localPathFromUri(match.groupValues[2]) ?: continue
            val target = resolvePathSync(destination) ?: continue

            val destinationRange = match.groups[2]!!.range
            claimedRanges += match.range
            refs.add(
                FileRef(
                    offset = destinationRange.first,
                    reference = destination,
                    fileName = destination.substringAfterLast('/'),
                    requestedPath = destination,
                    candidates = listOf(target),
                    hasLineNumber = false,
                    lineNumber = 1,
                    endLineNumber = null,
                    target = target,
                    linkEndOffset = destinationRange.last + 1
                )
            )
        }

        for (match in FilterPatterns.atPathRefPattern.findAll(text)) {
            val reference = normalizePath(match.groupValues[1])
            val target = resolvePathSync(reference) ?: continue
            if (target.isDirectory && !ProjectFileIndex.getInstance(project).isInProject(target)) continue

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
                    candidates = listOf(target),
                    hasLineNumber = hasLineNumber,
                    lineNumber = lineNumber,
                    endLineNumber = endLineNumber,
                    target = target,
                    linkEndOffset = match.range.last + 1
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
                findFileCandidates(fileName, requestedPath)
            }).executeSynchronously()
            if (files.isEmpty()) continue

            claimedRanges += match.range
            refs.add(
                FileRef(
                    offset = match.range.first,
                    reference = reference,
                    fileName = fileName,
                    requestedPath = requestedPath,
                    candidates = files,
                    hasLineNumber = hasLineNumber,
                    lineNumber = lineNumber,
                    endLineNumber = endLineNumber,
                    target = files.first(),
                    linkEndOffset = match.range.last + 1
                )
            )
        }

        return refs
    }

    /**
     * References behind OSC 8 hyperlinks of the frontend terminal output model.
     * The visible link label conceals the destination, so it cannot be found by the text scan;
     * the model exposes the raw link URIs instead.
     */
    private fun findOsc8References(editor: Editor, modelProvider: (() -> Any?)?): List<FileRef> {
        val provider = modelProvider ?: return emptyList()
        val modelsSet = try {
            provider()
        } catch (exception: Throwable) {
            logOsc8Diagnostic("models lookup failed: ${exception.message}")
            return emptyList()
        } ?: return emptyList()

        val models = outputModels(modelsSet)
        if (models.isEmpty()) {
            logOsc8Diagnostic("no output models available")
            return emptyList()
        }

        var diagnostic: String? = null
        for (model in models) {
            val access = try {
                TerminalOutputModelAccess(model)
            } catch (exception: Throwable) {
                diagnostic = diagnostic ?: "model API not accessible: ${exception.message}"
                continue
            }
            if (access.hyperlinks.isEmpty()) {
                if (diagnostic == null) {
                    diagnostic = "model without OSC8 hyperlinks"
                    log.debug("OSC8 model without hyperlinks: textLength=${access.textLength}")
                }
                continue
            }

            val result = osc8FileRefs(access, editor)
            if (result.refs.isNotEmpty()) {
                logOsc8Diagnostic(
                    "${result.refs.size} local file(s) from ${access.hyperlinks.size} OSC8 link(s)"
                )
                log.debug(
                    "OSC8 arrows: model textLength=${access.textLength}, editor=${editor.document.textLength}"
                )
                return result.refs
            }
            if (diagnostic == null) {
                diagnostic = "links did not map to this editor" +
                    (result.dropSummary?.let { " (dropped: $it)" } ?: "")
                log.debug(
                    "OSC8 mapping failed: model textLength=${access.textLength}, " +
                        "editor=${editor.document.textLength}, links=${access.hyperlinks.size}"
                )
            }
        }
        diagnostic?.let { logOsc8Diagnostic(it) }
        return emptyList()
    }

    /** The terminal's output models, active first, deduplicated (regular vs alternate buffer) */
    private fun outputModels(modelsSet: Any): List<Any> {
        val result = mutableListOf<Any>()
        val getActive = accessibleMethod(modelsSet.javaClass, "getActive")
        if (getActive != null) {
            val active = try {
                getActive.invoke(modelsSet)
            } catch (_: Throwable) {
                null
            }
            if (active != null) {
                val getValue = accessibleMethod(active.javaClass.methods, "getValue")
                val activeModel = try {
                    getValue?.invoke(active)
                } catch (_: Throwable) {
                    null
                }
                if (activeModel != null) result += activeModel
            }
        }
        for (name in listOf("getRegular", "getAlternative")) {
            val model = try {
                accessibleMethod(modelsSet.javaClass, name)?.invoke(modelsSet)
            } catch (_: Throwable) {
                null
            }
            if (model != null && result.none { it === model }) result += model
        }
        return result
    }

    private class Osc8ScanResult(val refs: List<FileRef>, val dropSummary: String?)

    private fun osc8FileRefs(access: TerminalOutputModelAccess, editor: Editor): Osc8ScanResult {
        val refs = mutableListOf<FileRef>()
        val drops = linkedMapOf<String, Int>()
        fun drop(reason: String) {
            drops[reason] = (drops[reason] ?: 0) + 1
        }

        val firstLink = access.hyperlinks.firstOrNull()
            ?: return Osc8ScanResult(emptyList(), null)
        val getUri = accessibleMethod(firstLink.javaClass.methods, "getUri")
            ?: return Osc8ScanResult(emptyList(), "missing getUri API")
        val getStartOffset = accessibleMethod(firstLink.javaClass.methods, "getStartOffset")
            ?: return Osc8ScanResult(emptyList(), "missing getStartOffset API")
        val getEndOffset = accessibleMethod(firstLink.javaClass.methods, "getEndOffset")
            ?: return Osc8ScanResult(emptyList(), "missing getEndOffset API")

        for (link in access.hyperlinks) {
            try {
                val uri = getUri.invoke(link) as? String ?: continue
                val path = localPathFromUri(uri)
                if (path == null) {
                    drop("not-local")
                    continue
                }
                val target = resolvePathSync(path)
                if (target == null) {
                    drop("unresolved")
                    continue
                }

                val startObject = getStartOffset.invoke(link) ?: continue
                val endObject = getEndOffset.invoke(link) ?: continue
                val startEditorOffset = access.editorOffset(startObject, editor.document)
                val endEditorOffset = access.editorOffset(endObject, editor.document)
                if (startEditorOffset == null || endEditorOffset == null) {
                    drop("offset-mapping")
                    continue
                }

                // Verify the mapping: the editor text at the mapped range must equal the model's link label
                val label = access.linkText(startObject, endObject)
                if (label != null) {
                    val editorText = try {
                        if (endEditorOffset > startEditorOffset && endEditorOffset <= editor.document.textLength) {
                            editor.document.getText(
                                com.intellij.openapi.util.TextRange(startEditorOffset, endEditorOffset)
                            )
                        } else {
                            null
                        }
                    } catch (_: Throwable) {
                        null
                    }
                    if (editorText == null || editorText != label) {
                        drop("verify")
                        continue
                    }
                } else if (Math.abs(access.textLength - editor.document.textLength) > OSC8_LENGTH_TOLERANCE) {
                    drop("length-guard")
                    continue
                }

                refs.add(
                    FileRef(
                        offset = startEditorOffset,
                        reference = path,
                        fileName = path.substringAfterLast('/'),
                        requestedPath = path,
                        candidates = listOf(target),
                        hasLineNumber = false,
                        lineNumber = 1,
                        endLineNumber = null,
                        target = target,
                        linkEndOffset = endEditorOffset,
                        arrowAfter = true
                    )
                )
            } catch (exception: Throwable) {
                drop("error:${exception.javaClass.simpleName}")
                log.debug("OSC8 hyperlink processing failed: ${exception.message}")
            }
        }
        return Osc8ScanResult(refs, if (drops.isEmpty()) null else drops.entries.joinToString { "${it.key}=${it.value}" })
    }

    /**
     * References from console-style hyperlinks installed by the reworked (Gen1) terminal.
     * Only local file destinations are converted; web links keep their native navigation.
     */
    private fun findConsoleHyperlinkReferences(editor: Editor): List<FileRef> {
        val support = try {
            EditorHyperlinkSupport.get(editor, false)
        } catch (_: Throwable) {
            return emptyList()
        } ?: return emptyList()

        val links = try {
            support.hyperlinks
        } catch (_: Throwable) {
            return emptyList()
        }

        val refs = mutableListOf<FileRef>()
        for ((highlighter, info) in links) {
            try {
                val url = hyperlinkInfoUrl(info) ?: continue
                val path = localPathFromUri(url) ?: continue
                val target = resolvePathSync(path) ?: continue

                val start = highlighter.getStartOffset()
                val end = highlighter.getEndOffset()
                if (start >= end) continue
                refs.add(
                    FileRef(
                        offset = start,
                        reference = path,
                        fileName = path.substringAfterLast('/'),
                        requestedPath = path,
                        candidates = listOf(target),
                        hasLineNumber = false,
                        lineNumber = 1,
                        endLineNumber = null,
                        target = target,
                        linkEndOffset = end,
                        arrowAfter = true
                    )
                )
            } catch (exception: Throwable) {
                log.debug("Console hyperlink processing failed: ${exception.message}")
            }
        }
        return refs
    }

    /** Best-effort URL extraction from a console hyperlink info */
    private fun hyperlinkInfoUrl(info: HyperlinkInfo): String? {
        for (name in listOf("getUrl", "getURL")) {
            val method = info.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0 && it.returnType == String::class.java
            } ?: continue
            val value = try {
                method.invoke(info) as? String
            } catch (_: Throwable) {
                null
            } ?: continue
            if (value.isNotBlank()) return value
        }
        return null
    }

    /**
     * Reflective access to the frontend terminal output model
     * (org.jetbrains.plugins.terminal.view.TerminalOutputModel and friends).
     */
    private class TerminalOutputModelAccess(model: Any) {
        val textLength: Int

        /** org.jetbrains.plugins.terminal.view.TerminalOutputOsc8Hyperlink instances */
        val hyperlinks: List<Any>

        private val modelObject: Any = model
        private val getLineByOffset: java.lang.reflect.Method?
        private val getStartOfLine: java.lang.reflect.Method?
        private val lineIndexOf: java.lang.reflect.Method?
        private val toAbsolute: java.lang.reflect.Method?
        private val getText: java.lang.reflect.Method?
        private val offsetOf: java.lang.reflect.Method?

        init {
            val methods = modelObject.javaClass.methods
            textLength = accessibleMethod(methods, "getTextLength")?.invoke(modelObject) as? Int ?: 0
            val rawLinks = accessibleMethod(methods, "getOsc8Hyperlinks")
                ?.invoke(modelObject) as? List<Any>
            hyperlinks = rawLinks.orEmpty()
            getLineByOffset = accessibleNamedMethod(methods, "getLineByOffset")
            getStartOfLine = accessibleNamedMethod(methods, "getStartOfLine")
            getText = methods.firstOrNull {
                it.name == "getText" && it.parameterCount == 2
            }?.apply { isAccessible = true }

            val offsetType = getLineByOffset?.parameterTypes?.firstOrNull()
            toAbsolute = offsetType?.let { accessibleMethod(it.methods, "toAbsolute") }
            offsetOf = offsetType?.methods?.firstOrNull {
                it.name == "of" && it.parameterCount == 1 &&
                    (it.parameterTypes[0] == java.lang.Long.TYPE || it.parameterTypes[0] == java.lang.Long::class.java)
            }?.apply { isAccessible = true }
            val lineType = getStartOfLine?.parameterTypes?.firstOrNull()
            lineIndexOf = lineType?.let { accessibleMethod(it.methods, "of") }
        }

        /** The visible label text of a link, read from the model; null when unavailable */
        fun linkText(start: Any, end: Any): String? {
            val method = getText ?: return null
            val makeOffset = offsetOf ?: return null
            val toAbs = toAbsolute ?: return null
            return try {
                val startAbs = toAbs.invoke(start) as? Long ?: return null
                val endAbs = toAbs.invoke(end) as? Long ?: return null
                val text = method.invoke(modelObject, makeOffset.invoke(null, startAbs), makeOffset.invoke(null, endAbs))
                (text as? CharSequence)?.toString()
            } catch (_: Throwable) {
                null
            }
        }

        /** Model absolute offset -> editor document offset via line/column mapping */
        fun editorOffset(offset: Any, document: com.intellij.openapi.editor.Document): Int? {
            val toAbs = toAbsolute ?: return null
            val absolute = toAbs.invoke(offset) as? Long ?: return null
            val getLine = getLineByOffset ?: return null
            val lineObject = getLine.invoke(modelObject, offset) ?: return null
            val line = lineObject.javaClass.methods.firstOrNull { it.name == "toAbsolute" }
                ?.apply { isAccessible = true }
                ?.invoke(lineObject) as? Long ?: return null
            val lineInt = line.toInt()
            if (lineInt < 0 || lineInt >= document.lineCount) return null
            val getStart = getStartOfLine ?: return null
            val makeLineIndex = lineIndexOf ?: return null
            val lineStartObject = getStart.invoke(modelObject, makeLineIndex.invoke(null, line)) ?: return null
            val lineStart = lineStartObject.javaClass.methods.firstOrNull { it.name == "toAbsolute" }
                ?.apply { isAccessible = true }
                ?.invoke(lineStartObject) as? Long ?: return null
            val column = (absolute - lineStart).toInt()
            val lineEnd = document.getLineEndOffset(lineInt)
            val lineStartDoc = document.getLineStartOffset(lineInt)
            return (lineStartDoc + column).coerceAtMost(lineEnd)
        }
    }

    /** Resolve the output models set of a frontend terminal tab (regular + alternate buffer) */
    private fun activeOutputModelsSet(tab: Any): Any? {
        val view = accessibleMethod(tab.javaClass, "getView")?.invoke(tab) ?: return null
        return accessibleMethod(view.javaClass, "getOutputModels")?.invoke(view)
    }

    /** Resolve the active output model of a frontend terminal tab (regular or alternate buffer) */
    private fun activeOutputModel(tab: Any): Any? {
        val modelsSet = activeOutputModelsSet(tab) ?: return null
        val active = accessibleMethod(modelsSet.javaClass, "getActive")?.invoke(modelsSet) ?: return null
        val getValue = accessibleMethod(active.javaClass.methods, "getValue") ?: return null
        return getValue.invoke(active)
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
            val files = ref.candidates.filter { it.isValid && !it.isDirectory }
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

    private fun findFileCandidates(fileName: String, requestedPath: String?): List<VirtualFile> {
        val exactPath = requestedPath?.let { findProjectPath(project, it) }
            ?.takeIf { it.isValid && !it.isDirectory }
        if (exactPath != null) return listOf(exactPath)

        val projectFiles = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
            .filter { it.isValid && !it.isDirectory }
            .sortedBy { displayPath(project, it) }
            .let { all ->
                if (requestedPath == null) all else all.filter { pathMatches(project, it, requestedPath) }
            }
        if (projectFiles.isNotEmpty()) return projectFiles

        // AI CLI plans live outside the project (e.g. ~/.opencode/plan); match bare file names there
        openCodePlanFileCandidate(fileName)?.let { return listOf(it) }
        return emptyList()
    }

    private fun openCodePlanFileCandidate(fileName: String): VirtualFile? {
        return try {
            val file = java.nio.file.Path.of(System.getProperty("user.home"), ".opencode", "plan", fileName)
            if (!java.nio.file.Files.isRegularFile(file)) return null
            val localFileSystem = LocalFileSystem.getInstance()
            (localFileSystem.findFileByNioFile(file) ?: localFileSystem.refreshAndFindFileByNioFile(file))
                ?.takeIf { it.isValid && !it.isDirectory }
        } catch (_: Throwable) {
            null
        }
    }

    private inner class EditorTracker(
        val editor: Editor,
        /** Resolves the frontend terminal's active output model, when known */
        val modelProvider: (() -> Any?)? = null
    ) {
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
                val point = editor.offsetToXY(if (ref.arrowAfter) ref.linkEndOffset else ref.offset)
                val x = if (ref.arrowAfter) {
                    point.x + editorSpaceWidth(editor) + OVERLAY_PADDING
                } else {
                    point.x - icon.iconWidth - (OVERLAY_PADDING * 2)
                }
                val overlayIcon = OpacityIcon(icon, OVERLAY_ICON_OPACITY)
                val label = JLabel(overlayIcon).apply {
                    border = BorderFactory.createEmptyBorder(OVERLAY_PADDING, OVERLAY_PADDING, OVERLAY_PADDING, OVERLAY_PADDING)
                    bounds = Rectangle(
                        x,
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
                val model = activeOutputModel(tab) ?: return
                val snapshot = model.javaClass.methods.first { it.name == "takeSnapshot" }
                    .apply { isAccessible = true }
                    .invoke(model)
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
        val candidates: List<VirtualFile>,
        val hasLineNumber: Boolean,
        val lineNumber: Int,
        val endLineNumber: Int?,
        val target: VirtualFile,
        /** End of the whole reference/link in document coordinates */
        val linkEndOffset: Int = offset,
        /** Draw the arrow after the link end instead of before its start */
        val arrowAfter: Boolean = false
    )

    companion object {
        /** A public method by name, made accessible for implementations that are package-private at runtime */
        private fun accessibleMethod(methods: Array<java.lang.reflect.Method>, name: String): java.lang.reflect.Method? {
            return methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.apply { isAccessible = true }
        }

        /** Same as [accessibleMethod] but also matches methods with parameters */
        private fun accessibleNamedMethod(methods: Array<java.lang.reflect.Method>, name: String): java.lang.reflect.Method? {
            return methods.firstOrNull { it.name == name }?.apply { isAccessible = true }
        }

        private fun accessibleMethod(type: Class<*>, name: String): java.lang.reflect.Method? {
            return try {
                type.getMethod(name).apply { isAccessible = true }
            } catch (_: Throwable) {
                null
            }
        }

        private const val SETUP_RETRY_DELAY_MS = 500
        private const val MAX_SETUP_ATTEMPTS = 21
        private const val RESCAN_DEBOUNCE_MS = 300
        private const val OVERLAY_PADDING = 4
        private const val OVERLAY_ICON_OPACITY = 0.4f
        private const val OSC8_LENGTH_TOLERANCE = 2

        /** Approximate advance of one terminal character cell for arrow placement */
        private fun editorSpaceWidth(editor: Editor): Int {
            return try {
                val font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
                editor.contentComponent.getFontMetrics(font).charWidth('M')
            } catch (_: Throwable) {
                10
            }
        }

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
                // 2026.3+ keeps the alternate-buffer editor behind a Deferred; unwrap it when already completed.
                try {
                    (findAlternateBufferDeferred(view)?.let { completedValue(it) } as? Editor)?.let { alt ->
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

        /** Reads the completed value of a Deferred without blocking; returns null when incomplete or cancelled. */
        private fun completedValue(deferred: Any): Any? {
            return try {
                val isCompleted = deferred.javaClass.getMethod("isCompleted").invoke(deferred) as? Boolean ?: false
                if (!isCompleted) return null
                // CompletableDeferredImpl is internal to kotlinx.coroutines; make the interface method accessible.
                val getCompleted = deferred.javaClass.methods.firstOrNull { it.name == "getCompleted" } ?: return null
                getCompleted.isAccessible = true
                getCompleted.invoke(deferred)
            } catch (_: Throwable) {
                null
            }
        }

        /** The 2026.3 frontend terminal stores the alternate-buffer editor in a Deferred instead of a field. */
        private fun findAlternateBufferDeferred(view: Any): Any? {
            return try {
                view.javaClass.getMethod("getAlternateBufferEditorDeferred").invoke(view)
            } catch (_: NoSuchMethodException) {
                try {
                    findField(view.javaClass, "alternateBufferEditorDeferred")
                        ?.also { it.isAccessible = true }
                        ?.get(view)
                } catch (_: Throwable) {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** Hooks the alternate-buffer editor creation so overlays attach even after a TUI starts. */
    private fun watchFrontendAlternateBufferEditor(tab: Any) {
        if (watchedFrontendTabs.containsKey(tab)) return

        try {
            val view = tab.javaClass.getMethod("getView").invoke(tab) ?: return
            val deferred = findAlternateBufferDeferred(view) ?: return

            synchronized(watchedFrontendTabs) {
                if (watchedFrontendTabs.put(tab, true) != null) return
            }

            val onEditorReady = { editor: Editor ->
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    log.info("Managing frontend alternate-buffer file-link editor ${editor.javaClass.name}")
                    setupEditor(editor, tab)
                }, ModalityState.any())
            }

            // A TUI may already be running on the alternate buffer when the watcher is registered.
            (completedValue(deferred) as? Editor)?.let {
                onEditorReady(it)
                return
            }

            val job = deferred as? kotlinx.coroutines.Job ?: run {
                log.debug("Frontend alternate-buffer editor holder is not a Job for ${tab.javaClass.name}")
                return
            }
            job.invokeOnCompletion { cause ->
                if (cause != null) {
                    if (!project.isDisposed) {
                        log.debug("Frontend alternate-buffer editor creation was cancelled: ${cause.message}")
                    }
                    return@invokeOnCompletion
                }
                (completedValue(deferred) as? Editor)?.let(onEditorReady)
                    ?: log.debug("Frontend alternate-buffer deferred completed without an Editor")
            }
        } catch (exception: Throwable) {
            watchedFrontendTabs.remove(tab)
            log.debug("Could not watch frontend alternate-buffer editor for ${tab.javaClass.name}: ${exception.message}")
        }
    }
}
