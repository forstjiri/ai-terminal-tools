package io.github.q110.aiterminaltools

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/** Resolves the project path without guessing when IntelliJ holds a stale basePath. */
object ProjectBasePath {
    fun resolve(project: Project): Path {
        val actualPath = project.basePath ?: "<null>"
        val path = try {
            Path.of(actualPath).toAbsolutePath().normalize()
        } catch (exception: Throwable) {
            throw invalid(actualPath, exception)
        }
        return requireValid(path)
    }

    fun requireValid(path: Path): Path {
        val normalizedPath = path.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalizedPath) || !Files.isReadable(normalizedPath)) {
            throw invalid(normalizedPath.toString())
        }
        return normalizedPath
    }

    fun resolveTerminalExecutionRoot(project: Project, hint: VirtualFile?): Path {
        try {
            return resolve(project)
        } catch (_: IllegalStateException) {
            // A stale basePath may still be recoverable from the active project root.
        }

        val rootManager = ProjectRootManager.getInstance(project)
        if (hint?.isValid == true) {
            rootManager.fileIndex.getContentRootForFile(hint)?.let { contentRoot ->
                try {
                    return requireValid(Path.of(contentRoot.path))
                } catch (_: IllegalStateException) {
                }
            }
        }

        val candidates = rootManager.contentRoots
            .mapNotNull { root ->
                try {
                    requireValid(Path.of(root.path))
                } catch (_: IllegalStateException) {
                    null
                }
            }
            .distinct()
        return when (candidates.size) {
            1 -> candidates.single()
            0 -> throw IllegalStateException(
                "Unable to determine the project working directory: the cached path is invalid and no usable project content root exists. Reopen the project at its current location or close the stale project."
            )
            else -> throw IllegalStateException(
                "Unable to determine the project working directory: multiple usable project content roots exist (${candidates.joinToString()}). Activate a file under the target root and try again, or reopen the project."
            )
        }
    }

    private fun invalid(path: String, cause: Throwable? = null): IllegalStateException {
        return IllegalStateException(
            "The project working directory is invalid: $path. Reopen the project at its current location or close the stale project.",
            cause
        )
    }
}
