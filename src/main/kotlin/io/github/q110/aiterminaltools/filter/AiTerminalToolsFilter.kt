// Core Filter — parses each terminal output line into file navigation and click-to-copy links
package io.github.q110.aiterminaltools.filter

import com.intellij.execution.filters.Filter
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.q110.aiterminaltools.copy.CopyTextHyperlinkInfo
import io.github.q110.aiterminaltools.jump.FileReferenceHyperlinkInfo
import io.github.q110.aiterminaltools.jump.FolderReferenceHyperlinkInfo
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings

internal class AiTerminalToolsFilter(
    private val project: Project
) : Filter {
    /** LRU cache: filename → most recently seen path */
    private val recentFilePathsByName = LinkedHashMap<String, String>()
    private var cachedFileExtensions: Set<String> = emptySet()
    private var cachedFileRefPattern: Regex =
        FilterPatterns.fileRefPattern(AiTerminalToolsSettings.StateData.DEFAULT_FILE_EXTENSIONS)

    /** @param line current output line; entireLength is the total content length (for calculating baseOffset) */
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        if (!settings.fileLinksEnabled && !settings.copyLinksEnabled) {
            return null
        }

        // Stage one: cache file path references in the current line
        if (settings.fileLinksEnabled) {
            val fileRefPattern = currentFileRefPattern(settings.resolvedFileExtensions())
            rememberPathReferences(line, fileRefPattern)
        }

        val baseOffset = entireLength - line.length
        val items = mutableListOf<Filter.ResultItem>()
        val fileLinkRanges = mutableListOf<IntRange>()
        val copyTextAttributes = normalTextAttributes()

        // Stage two: parse @path references (high priority, exact matches)
        if (settings.fileLinksEnabled) {
            for (match in FilterPatterns.atPathRefPattern.findAll(line)) {
                val reference = normalizePath(match.groupValues[1])
                val target = findProjectPathReference(reference) ?: continue
                val hasLineNumber = match.groupValues[2].isNotEmpty()
                val lineNumber = match.groupValues[2].toIntOrNull() ?: 1
                val endLineNumber = match.groupValues[3].toIntOrNull()

                fileLinkRanges += match.range
                items += if (target.isDirectory) {
                    Filter.ResultItem(
                        baseOffset + match.range.first,
                        baseOffset + match.range.last + 1,
                        FolderReferenceHyperlinkInfo(project, target)
                    )
                } else {
                    Filter.ResultItem(
                        baseOffset + match.range.first,
                        baseOffset + match.range.last + 1,
                        FileReferenceHyperlinkInfo(
                            project,
                            listOf(target),
                            target.name,
                            reference,
                            hasLineNumber,
                            lineNumber,
                            endLineNumber,
                            recentFilePathsByName.toMap()
                        )
                    )
                }
            }

            // Stage three: parse regular file references (filename-indexed, possibly multiple matches)
            val fileRefPattern = currentFileRefPattern(settings.resolvedFileExtensions())
            for (match in fileRefPattern.findAll(line)) {
                if (rangesOverlap(match.range, fileLinkRanges)) continue

                val reference = normalizePath(match.groupValues[1])
                val fileName = reference.substringAfterLast('/')
                val requestedPath = if (isPathReference(reference)) reference else null
                val hasLineNumber = match.groupValues[2].isNotEmpty()
                val lineNumber = match.groupValues[2].toIntOrNull() ?: 1
                val endLineNumber = match.groupValues[3].toIntOrNull()
                val files = findProjectFiles(fileName, requestedPath)
                if (files.isEmpty()) continue

                fileLinkRanges += match.range
                items += Filter.ResultItem(
                    baseOffset + match.range.first,
                    baseOffset + match.range.last + 1,
                    FileReferenceHyperlinkInfo(
                        project,
                        files,
                        fileName,
                        requestedPath,
                        hasLineNumber,
                        lineNumber,
                        endLineNumber,
                        recentFilePathsByName.toMap()
                    )
                )
            }
        }

        // Stage four: parse click-to-copy patterns (skip ranges occupied by file links)
        // Classic terminals create copy links through DropService's MouseAdapter instead of Filter to avoid styling issues
        if (settings.copyLinksEnabled && isInTerminalToolWindow() && !isCurrentTerminalClassic()) {
            for (match in findCopyMatches(line, fileLinkRanges)) {
                items += Filter.ResultItem(
                    baseOffset + match.range.first,
                    baseOffset + match.range.last + 1,
                    CopyTextHyperlinkInfo(project, match.text),
                    copyTextAttributes,
                    copyTextAttributes
                )
            }
        }

        return if (items.isEmpty()) null else Filter.Result(items)
    }

    /** Reuse the compiled regex and refresh it only when configured extensions change. */
    private fun currentFileRefPattern(extensions: Set<String>): Regex {
        if (extensions != cachedFileExtensions) {
            cachedFileExtensions = extensions
            cachedFileRefPattern = FilterPatterns.fileRefPattern(extensions)
        }

        return cachedFileRefPattern
    }

private fun normalTextAttributes(): TextAttributes {
        val scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme
        return TextAttributes(
            scheme.defaultForeground,
            null,
            null,
            null,
            0
        )
    }

    private fun isCurrentTerminalClassic(): Boolean {
        return project.getUserData(KEY_CURRENT_TERMINAL_CLASSIC) == true
    }

    /** Check whether the current console view is the Terminal tool window (not a Run/Debug console) */
    private fun isInTerminalToolWindow(): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
        return toolWindow?.isVisible == true
    }

    /** Find a VirtualFile by path inside a ReadAction */
    private fun findProjectPathReference(path: String): VirtualFile? {
        return ReadAction.compute<VirtualFile?, RuntimeException> {
            findProjectPath(project, path)
        }
    }

    /** Find project files by filename inside a ReadAction, with optional path filtering */
    private fun findProjectFiles(fileName: String, requestedPath: String?): List<VirtualFile> {
        return ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val files = FilenameIndex
                .getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
                .filter { it.isValid && !it.isDirectory }
                .sortedBy { displayPath(project, it) }

            if (requestedPath == null) files else files.filter { pathMatches(project, it, requestedPath) }
        }
    }

    /** Cache current-line paths in the LRU map to disambiguate later same-name files */
    private fun rememberPathReferences(line: String, fileRefPattern: Regex) {
        for (match in fileRefPattern.findAll(line)) {
            val path = normalizePath(match.groupValues[1])
            if (!isPathReference(path)) continue

            val fileName = path.substringAfterLast('/')
            recentFilePathsByName[fileName] = path
        }

        while (recentFilePathsByName.size > 200) {
            val firstKey = recentFilePathsByName.keys.firstOrNull() ?: break
            recentFilePathsByName.remove(firstKey)
        }
    }

    /** Scan copyPatterns by priority and skip occupied ranges */
    private fun findCopyMatches(line: String, blockedRanges: List<IntRange>): List<CopyMatch> {
        val usedRanges = blockedRanges.toMutableList()
        val matches = mutableListOf<CopyMatch>()

        for (pattern in FilterPatterns.copyPatterns) {
            for (match in pattern.findAll(line)) {
                if (rangesOverlap(match.range, usedRanges)) continue

                val text = match.value.trim()
                if (text.isEmpty() || isCopyNoise(text)) continue

                usedRanges += match.range
                matches += CopyMatch(match.range, text)
            }
        }

        return matches.sortedBy { it.range.first }
    }

    companion object {
        /** DropService sets this Key; Filter reads it to skip copy-link generation in Classic terminals */
        val KEY_CURRENT_TERMINAL_CLASSIC = Key.create<Boolean>("ai-terminal-tools.currentTerminalClassic")
    }
}

private data class CopyMatch(
    val range: IntRange,
    val text: String
)
