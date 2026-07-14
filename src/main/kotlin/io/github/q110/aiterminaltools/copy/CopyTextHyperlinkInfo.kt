// Click-to-copy link handler — copies matched text to the clipboard and shows a "Copied" prompt
package io.github.q110.aiterminaltools.copy

import com.intellij.execution.filters.HyperlinkInfoBase
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class CopyTextHyperlinkInfo(
    private val project: Project,
    private val text: String
) : HyperlinkInfoBase() {
    /** Copy the text and show a Balloon prompt above the link for 700 ms */
    override fun navigate(project: Project, hyperlinkLocationPoint: RelativePoint?) {
        CopyPasteManager.copyTextToClipboard(text)
        if (hyperlinkLocationPoint == null) {
            return
        }

        val content = JPanel(BorderLayout())
        content.preferredSize = Dimension(64, 28)
        content.minimumSize = Dimension(64, 28)
        content.maximumSize = Dimension(64, 28)
        content.border = JBUI.Borders.empty()
        content.add(JBLabel("Copied", SwingConstants.CENTER), BorderLayout.CENTER)

        val balloon = JBPopupFactory.getInstance()
            .createBalloonBuilder(content)
            .setSmallVariant(true)
            .setShowCallout(false)
            .setCloseButtonEnabled(false)
            .setContentInsets(Insets(0, 0, 0, 0))
            .setFadeoutTime(700)
            .createBalloon()
        val component = hyperlinkLocationPoint.component

        val point = if (component.width > 0) {
            RelativePoint(component, Point(component.width / 2, 18))
        } else {
            hyperlinkLocationPoint
        }

        balloon.show(point, Balloon.Position.below)
    }
}
