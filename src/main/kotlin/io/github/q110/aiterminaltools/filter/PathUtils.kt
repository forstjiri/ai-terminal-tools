// Path utilities — VirtualFile lookup, normalization, and matching
package io.github.q110.aiterminaltools.filter

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/** Convert a VirtualFile to a project-relative display path (project base → content root → absolute path) */
internal fun displayPath(project: Project, file: VirtualFile): String {
    val projectBasePath = project.basePath
    if (projectBasePath != null) {
        val normalizedBasePath = projectBasePath.replace('\\', '/')
        if (file.path == normalizedBasePath) {
            return "."
        }
        if (file.path.startsWith("$normalizedBasePath/")) {
            return file.path.removePrefix("$normalizedBasePath/")
        }
    }

    val fileIndex = ProjectRootManager.getInstance(project).fileIndex
    val contentRoot = fileIndex.getContentRootForFile(file)
    if (contentRoot != null) {
        return VfsUtilCore.getRelativePath(file, contentRoot, '/') ?: file.path
    }

    return file.path
}

/** Check whether a file path ends with the given path suffix (compare after normalization) */
internal fun pathMatches(project: Project, file: VirtualFile, path: String): Boolean {
    val normalizedPath = normalizePath(path)
    return normalizePath(displayPath(project, file)).endsWith(normalizedPath) ||
        normalizePath(file.path).endsWith(normalizedPath)
}

/** Find the VirtualFile for a string path in the project (project root → content roots) */
internal fun findProjectPath(project: Project, path: String): VirtualFile? {
    val normalizedPath = normalizePath(path).trimStart('/')
    val roots = mutableListOf<VirtualFile>()
    val basePath = project.basePath
    if (basePath != null) {
        LocalFileSystem.getInstance().findFileByPath(basePath.replace('\\', '/'))?.let { roots += it }
    }
    roots += ProjectRootManager.getInstance(project).contentRoots

    return roots.asSequence()
        .distinctBy { it.path }
        .mapNotNull { it.findFileByRelativePath(normalizedPath) }
        .firstOrNull()
}

/** Check whether a reference string contains a path separator */
internal fun isPathReference(reference: String): Boolean {
    return reference.contains('/')
}

/** Check whether two ranges overlap (for deduplicating fileLinks) */
internal fun rangesOverlap(range: IntRange, ranges: List<IntRange>): Boolean {
    return ranges.any { range.first <= it.last && range.last >= it.first }
}

/** Truncate long text for notification display */
internal fun shortStatusText(text: String): String {
    return if (text.length > 80) text.take(77) + "..." else text
}

/** Normalize paths: unify slashes and remove whitespace and trailing punctuation */
internal fun normalizePath(path: String): String {
    return path.replace('\\', '/').trim().trimEnd('.', ',', ';', ':', ')', ']', '}')
}
