// File navigation link handler — automatically chooses the best file and jumps to the specified line
package io.github.q110.aiterminaltools.jump

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.q110.aiterminaltools.filter.displayPath
import io.github.q110.aiterminaltools.filter.normalizePath
import com.intellij.openapi.roots.ProjectFileIndex
import io.github.q110.aiterminaltools.filter.pathMatches

internal class FileReferenceHyperlinkInfo(
    private val project: Project,
    /** Same-name file candidates (sorted by display path) */
    private val files: List<VirtualFile>,
    private val fileName: String,
    /** Path context contained in the current line */
    private val requestedPath: String?,
    private val hasLineNumber: Boolean,
    private val lineNumber: Int,
    /** Optional end of the line range */
    private val endLineNumber: Int?,
    /** Recent path cache (indexed by filename) */
    private val recentFilePathsByName: Map<String, String>
) : HyperlinkInfo {
    /** Open the file, select the line range, and center the view */
    override fun navigate(project: Project) {
        val target = chooseBestFile() ?: chooseFile()
        if (target != null) {
            val descriptorLine = if (hasLineNumber) (lineNumber - 1).coerceAtLeast(0) else 0
            val editor = FileEditorManager.getInstance(this.project).openTextEditor(
                OpenFileDescriptor(this.project, target, descriptorLine, 0),
                true
            )

            if (editor != null) {
                if (hasLineNumber) {
                    val document = editor.document
                    val startLine = (lineNumber - 1).coerceIn(0, document.lineCount - 1)
                    val endLine = ((endLineNumber ?: lineNumber) - 1).coerceIn(startLine, document.lineCount - 1)
                    val startOffset = document.getLineStartOffset(startLine)
                    val endOffset = document.getLineEndOffset(endLine)

                    editor.selectionModel.setSelection(startOffset, endOffset)
                    editor.caretModel.moveToOffset(startOffset)
                    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                }
            }
        }
    }

    /** Intelligently choose the best file from the candidates */
    private fun chooseBestFile(): VirtualFile? {
        if (files.size == 1) return files.first()

        // Prefer an exact path-suffix match
        if (requestedPath != null) {
            findByPathSuffix(requestedPath)?.let { return it }
        }

        // Then match a recently seen path
        val recentPath = recentFilePathsByName[fileName]
        if (recentPath != null) {
            findByPathSuffix(recentPath)?.let { return it }
        }

        // Finally choose by weighted score
        val scoredFiles = files.groupBy { scoreFile(it) }
        val bestScore = scoredFiles.keys.maxOrNull() ?: return null
        val bestFiles = scoredFiles[bestScore].orEmpty()
        return if (bestFiles.size == 1) bestFiles.first() else null
    }

    private fun findByPathSuffix(path: String): VirtualFile? {
        return files.firstOrNull {
            pathMatches(project, it, path)
        }
    }

    /** Score using the IntelliJ project index: source root +100, test root -60, excluded -200, library -100, exact filename suffix +10 */
    private fun scoreFile(file: VirtualFile): Int {
        val fileIndex = ProjectFileIndex.getInstance(project)
        var score = 0
        if (fileIndex.isInSourceContent(file)) score += 100
        if (fileIndex.isInTestSourceContent(file)) score -= 60
        if (fileIndex.isExcluded(file)) score -= 200
        if (fileIndex.isInLibrary(file)) score -= 100
        val path = normalizePath(displayPath(project, file))
        if (path.endsWith("/$fileName")) score += 10
        return score
    }

    /** Show a file chooser when automatic selection fails */
    private fun chooseFile(): VirtualFile? {
        val dialog = FileChoiceDialog(project, files)
        return if (dialog.showAndGet()) dialog.selectedFile else null
    }
}
