// Core Filter — parses each terminal output line into file navigation links
package io.github.q110.aiterminaltools.filter

import com.intellij.execution.filters.Filter
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.q110.aiterminaltools.jump.FileReferenceHyperlinkInfo
import io.github.q110.aiterminaltools.jump.FolderReferenceHyperlinkInfo
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings

internal class AiTerminalToolsFilter(
    private val project: Project
) : Filter {
    private val recentFilePathsByName = LinkedHashMap<String, String>()

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        if (!settings.fileLinksEnabled) {
            return null
        }

        rememberPathReferences(line)

        val baseOffset = entireLength - line.length
        val items = mutableListOf<Filter.ResultItem>()
        val fileLinkRanges = mutableListOf<IntRange>()

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

            val fileRefPattern = FilterPatterns.fileRefPattern
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

        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun findProjectPathReference(path: String): VirtualFile? {
        return ReadAction.compute<VirtualFile?, RuntimeException> {
            findProjectPath(project, path)
        }
    }

    private fun findProjectFiles(fileName: String, requestedPath: String?): List<VirtualFile> {
        return ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val files = FilenameIndex
                .getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
                .filter { it.isValid && !it.isDirectory }
                .sortedBy { displayPath(project, it) }

            if (requestedPath == null) files else files.filter { pathMatches(project, it, requestedPath) }
        }
    }

    private fun rememberPathReferences(line: String) {
        for (match in FilterPatterns.fileRefPattern.findAll(line)) {
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
}
