// Folder navigation link handler — locates and expands the folder in Project View when clicked
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
        // Use the constructor's project so navigation always targets the project for which the link was created.
        ProjectView.getInstance(this.project).select(null, folder, true)
    }
}
