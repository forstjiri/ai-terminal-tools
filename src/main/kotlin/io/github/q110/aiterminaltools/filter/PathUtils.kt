// Path utilities — VirtualFile lookup, normalization, and matching
package io.github.q110.aiterminaltools.filter

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

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

/** Find the VirtualFile for a string path, including existing files outside the project. */
internal fun findProjectPath(project: Project, path: String): VirtualFile? {
    val normalizedPath = normalizePath(path)
    if (Path.of(normalizedPath).isAbsolute) {
        val localFileSystem = LocalFileSystem.getInstance()
        val nioPath = Path.of(normalizedPath)
        return (localFileSystem.findFileByNioFile(nioPath)
            ?: localFileSystem.refreshAndFindFileByNioFile(nioPath))
            ?.takeIf { it.isValid }
    }

    val projectRelativePath = normalizedPath.trimStart('/')
    val roots = mutableListOf<VirtualFile>()
    val basePath = project.basePath
    if (basePath != null) {
        LocalFileSystem.getInstance().findFileByPath(basePath.replace('\\', '/'))?.let { roots += it }
    }
    roots += ProjectRootManager.getInstance(project).contentRoots

    return roots.asSequence()
        .distinctBy { it.path }
        .mapNotNull { it.findFileByRelativePath(projectRelativePath) }
        .firstOrNull { it.isValid }
}

/** Check whether a reference string contains a path separator */
internal fun isPathReference(reference: String): Boolean {
    return reference.contains('/')
}

private val nonFileUriPrefixes = listOf(
    "http://", "https://", "mailto:", "ftp://", "ftps://", "ssh:", "tel:", "data:", "javascript:", "ws://", "wss://"
)

/**
 * Extract a local filesystem path from an OSC 8 / markdown link destination.
 * Accepts raw absolute paths, home shorthand, drive-letter paths, and file: URIs.
 * Returns null for web links and unknown schemes so they keep their native handling.
 */
internal fun localPathFromUri(uri: String): String? {
    val trimmed = uri.trim().trim('"', '\'', '<', '>')
    if (trimmed.isEmpty()) return null

    val lower = trimmed.lowercase()
    if (nonFileUriPrefixes.any { lower.startsWith(it) }) return null

    if (lower.startsWith("file:")) {
        var rest = trimmed.substring("file:".length)
        // file:///path, file://localhost/path, file:/path
        while (rest.startsWith("//")) rest = rest.substring(2)
        if (rest.startsWith("localhost/")) rest = rest.substring("localhost".length)
        val decoded = try {
            java.net.URLDecoder.decode(rest, Charsets.UTF_8)
        } catch (_: Throwable) {
            rest
        }
        return normalizePath(decoded)
    }

    val normalized = normalizePath(trimmed)
    val looksLikeLocalPath = normalized.startsWith("/") ||
        normalized.startsWith("~") ||
        (normalized.length >= 2 && normalized[0].isLetter() && normalized[1] == ':')
    return if (looksLikeLocalPath) normalized else null
}

/** Normalize paths: unify slashes, expand a leading home shorthand, and remove trailing punctuation. */
internal fun normalizePath(path: String): String {
    val normalized = path.replace('\\', '/').trim().trimEnd('.', ',', ';', ':', ')', ']', '}')
    val home = System.getProperty("user.home")?.replace('\\', '/')?.trimEnd('/')
        ?: return normalized

    return when {
        normalized == "~" -> home
        normalized.startsWith("~/") -> home + normalized.removePrefix("~")
        else -> normalized
    }
}
