// Folder jump hyperlink handler - selects and expands the folder in Project View when clicked
package io.github.q110.aiterminaltools.jump

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

internal class FolderReferenceHyperlinkInfo(
    private val project: Project,
    private val folder: VirtualFile
) : HyperlinkInfo {
    override fun navigate(project: Project) {
        // Use the constructor project so navigation always happens in the project that created the link.
        ProjectView.getInstance(this.project).select(null, folder, true)
    }
}
