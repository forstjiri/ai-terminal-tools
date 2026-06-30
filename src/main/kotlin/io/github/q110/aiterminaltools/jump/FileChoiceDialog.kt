// Same-name file selection dialog - lets the user choose manually when multiple files match
package io.github.q110.aiterminaltools.jump

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import io.github.q110.aiterminaltools.filter.displayPath
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal class FileChoiceDialog(
    private val project: Project,
    private val files: List<VirtualFile>
) : DialogWrapper(project) {
    // Show relative/friendly paths in the list so users can distinguish same-named files.
    private val list = JBList(files.map { displayPath(project, it) })

    // The raw file for the currently selected entry; returns null if there is no valid selection.
    val selectedFile: VirtualFile?
        get() = files.getOrNull(list.selectedIndex)

    init {
        title = "Select File"
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.selectedIndex = 0
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(JLabel("Multiple files with the same name were found. Select the file you want to jump to."), BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.preferredSize = dialogSize(project)
        return panel
    }

    // Prefer the current window size, then fall back to the screen size, to avoid dialogs that are too large or too small.
    private fun dialogSize(project: Project): Dimension {
        val windowSize = WindowManager.getInstance().getFrame(project)?.size
            ?: Toolkit.getDefaultToolkit().screenSize
        return Dimension((windowSize.width * 0.5).toInt(), (windowSize.height * 0.5).toInt())
    }
}
