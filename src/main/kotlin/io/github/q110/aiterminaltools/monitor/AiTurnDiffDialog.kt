// Standalone non-modal dialog for AI Turn Diff, using DialogWrapper for proper Escape/close handling.
package io.github.q110.aiterminaltools.monitor

import com.intellij.diff.DiffManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

class AiTurnDiffDialog(
    project: Project,
    private val requests: List<DiffRequest>,
    private val onClosed: (() -> Unit)? = null
) : DialogWrapper(project, false) {

    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    private val fileComboBox = ComboBox(requests.map { it.title }.toTypedArray()).apply {
        isEnabled = requests.size > 1
        toolTipText = "Select a file changed this turn"
    }

    private var currentIndex = 0

    init {
        title = "AI Terminal changes this turn - ${requests.size} files"
        isModal = false
        isResizable = true
        init()
    }

    override fun getInitialSize(): Dimension = Dimension(1100, 760)

    override fun createCenterPanel(): JComponent {
        fileComboBox.addActionListener {
            val index = fileComboBox.selectedIndex
            if (index >= 0 && index < requests.size && index != currentIndex) {
                currentIndex = index
                diffPanel.setRequest(requests[index])
            }
        }

        val container = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(createHeaderPanel(), BorderLayout.NORTH)
            add(diffPanel.component, BorderLayout.CENTER)
        }

        if (requests.isNotEmpty()) {
            diffPanel.setRequest(requests[0])
        }

        return container
    }

    override fun createActions(): Array<javax.swing.Action> = emptyArray()

    override fun doCancelAction() {
        onClosed?.invoke()
        super.doCancelAction()
    }

    private fun createHeaderPanel(): JPanel {
        val header = JPanel(GridBagLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8, 10)
        }
        val countLabel = JBLabel("Changes this turn: ${requests.size} files").apply {
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.emptyRight(10)
        }

        header.add(countLabel, GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 0.0
            fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.WEST
        })
        header.add(fileComboBox, GridBagConstraints().apply {
            gridx = 1
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        })
        return header
    }
}
