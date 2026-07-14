// Settings panel for terminal links, console sending, drag-and-drop, and commit message generation.
package io.github.q110.aiterminaltools.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class AiTerminalToolsConfigurable : Configurable {
    private var fileLinksCheckBox: JBCheckBox? = null
    private var copyLinksCheckBox: JBCheckBox? = null
    private var errorToAiTerminalIconsCheckBox: JBCheckBox? = null
    private var dragToAiTerminalCheckBox: JBCheckBox? = null
    private var commitMessageAiToolCombo: javax.swing.JComboBox<String>? = null
    private var commitMessageModelField: JBTextField? = null
    private var commitMessageAdditionalPromptArea: JBTextArea? = null
    private var openCodeTerminalCommandField: JBTextField? = null
    private var claudeCodeTerminalCommandField: JBTextField? = null
    private var onTurnEndCommandField: JBTextField? = null
    private var appendChangesToNextMessageCheckBox: JBCheckBox? = null
    private var panel: JPanel? = null
    private var selectedCommitMessageAiTool: String = COMMIT_MESSAGE_AI_TOOL_OPENCODE
    private var openCodeCommitMessageModel: String = ""
    private var claudeCommitMessageModel: String = ""
    private var updatingCommitMessageUi: Boolean = false

    override fun getDisplayName(): String {
        return "AI Terminal Tools"
    }

    override fun createComponent(): JComponent {
        val fileLinksCheckBox = JBCheckBox("Enable file navigation links")
        val copyLinksCheckBox = JBCheckBox("Enable click-to-copy links")
        val errorToAiTerminalIconsCheckBox = JBCheckBox("Enable console error send icons")
        val dragToAiTerminalCheckBox = JBCheckBox("Enable dragging files/folders to AI terminals")
        val commitMessageAiToolCombo = javax.swing.JComboBox(arrayOf(COMMIT_MESSAGE_AI_TOOL_OPENCODE_LABEL, COMMIT_MESSAGE_AI_TOOL_CLAUDE_LABEL))
        val commitMessageModelField = JBTextField()
        val commitMessageAdditionalPromptArea = JBTextArea(4, 48)
        val openCodeTerminalCommandField = JBTextField()
        val claudeCodeTerminalCommandField = JBTextField()
        val onTurnEndCommandField = JBTextField()
        val appendChangesToNextMessageCheckBox = JBCheckBox("Append changes made in diff window to next agent message")
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(12)

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.anchor = GridBagConstraints.WEST
        constraints.fill = GridBagConstraints.HORIZONTAL
        constraints.weightx = 1.0
        constraints.insets = JBUI.insetsBottom(8)
        panel.add(fileLinksCheckBox, constraints)

        constraints.gridy = 1
        panel.add(copyLinksCheckBox, constraints)

        constraints.gridy = 2
        panel.add(errorToAiTerminalIconsCheckBox, constraints)

        constraints.gridy = 3
        constraints.insets = JBUI.insetsBottom(8)
        panel.add(dragToAiTerminalCheckBox, constraints)

        constraints.gridy = 4
        constraints.insets = JBUI.insetsTop(4)
        val dragHelpLabel = JBLabel("When enabled, dragging files/folders to any terminal sends them as @path. When disabled, this applies only to terminals started by the plugin.")
        dragHelpLabel.foreground = JBColor.namedColor("Label.disabledForeground", JBColor(0x8c8c8c, 0x999999))
        dragHelpLabel.border = JBUI.Borders.emptyLeft(20)
        panel.add(dragHelpLabel, constraints)

        constraints.gridy = 5
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("Commit message AI tool:"), constraints)

        constraints.gridy = 6
        constraints.insets = JBUI.insetsTop(4)
        panel.add(commitMessageAiToolCombo, constraints)

        constraints.gridy = 7
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("Commit message model:"), constraints)

        constraints.gridy = 8
        constraints.insets = JBUI.insetsTop(4)
        panel.add(commitMessageModelField, constraints)

        constraints.gridy = 9
        constraints.insets = JBUI.insetsTop(4)
        val modelHelpLabel = JBLabel("Use full model name with provider prefix, e.g. openai/gpt-5.6-luna")
        modelHelpLabel.foreground = JBColor.namedColor("Label.disabledForeground", JBColor(0x8c8c8c, 0x999999))
        modelHelpLabel.border = JBUI.Borders.emptyLeft(20)
        panel.add(modelHelpLabel, constraints)

        constraints.gridy = 10
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("Additional commit message prompt:"), constraints)

        constraints.gridy = 11
        constraints.insets = JBUI.insetsTop(4)
        commitMessageAdditionalPromptArea.lineWrap = true
        commitMessageAdditionalPromptArea.wrapStyleWord = true
        panel.add(JBScrollPane(commitMessageAdditionalPromptArea), constraints)

        constraints.gridy = 12
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("OpenCode startup command:"), constraints)

        constraints.gridy = 13
        constraints.insets = JBUI.insetsTop(4)
        panel.add(openCodeTerminalCommandField, constraints)

        constraints.gridy = 14
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("Claude Code startup command:"), constraints)

        constraints.gridy = 15
        constraints.insets = JBUI.insetsTop(4)
        panel.add(claudeCodeTerminalCommandField, constraints)

        constraints.gridy = 16
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("On turn end command:"), constraints)

        constraints.gridy = 17
        constraints.insets = JBUI.insetsTop(4)
        panel.add(onTurnEndCommandField, constraints)

        constraints.gridy = 18
        constraints.insets = JBUI.insetsTop(16)
        panel.add(appendChangesToNextMessageCheckBox, constraints)

        constraints.gridy = 19
        constraints.weighty = 1.0
        constraints.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), constraints)

        commitMessageAiToolCombo.addActionListener {
            if (updatingCommitMessageUi) return@addActionListener
            saveCurrentCommitMessageModel()
            selectedCommitMessageAiTool = commitMessageAiToolFromLabel(commitMessageAiToolCombo.selectedItem as? String)
            updateCommitMessageModelUi()
        }

        this.fileLinksCheckBox = fileLinksCheckBox
        this.copyLinksCheckBox = copyLinksCheckBox
        this.errorToAiTerminalIconsCheckBox = errorToAiTerminalIconsCheckBox
        this.dragToAiTerminalCheckBox = dragToAiTerminalCheckBox
        this.commitMessageAiToolCombo = commitMessageAiToolCombo
        this.commitMessageModelField = commitMessageModelField
        this.commitMessageAdditionalPromptArea = commitMessageAdditionalPromptArea
        this.openCodeTerminalCommandField = openCodeTerminalCommandField
        this.claudeCodeTerminalCommandField = claudeCodeTerminalCommandField
        this.onTurnEndCommandField = onTurnEndCommandField
        this.appendChangesToNextMessageCheckBox = appendChangesToNextMessageCheckBox
        this.panel = panel
        return panel
    }

    override fun isModified(): Boolean {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        val currentAiTool = selectedCommitMessageAiTool
        val settingsAiTool = normalizedCommitMessageAiTool(settings.commitMessageAiTool)
        val currentModel = if (currentAiTool == COMMIT_MESSAGE_AI_TOOL_OPENCODE) {
            commitMessageModelField?.text?.trim().orEmpty()
        } else {
            claudeCommitMessageModel
        }
        val settingsModel = if (settingsAiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
            settings.claudeCommitMessageModel
        } else {
            settings.commitMessageModel
        }
        return fileLinksCheckBox?.isSelected != settings.fileLinksEnabled ||
            copyLinksCheckBox?.isSelected != settings.copyLinksEnabled ||
            errorToAiTerminalIconsCheckBox?.isSelected != settings.errorToAiTerminalIconsEnabled ||
            dragToAiTerminalCheckBox?.isSelected != settings.isDragToAiTerminalEnabled() ||
            currentAiTool != settingsAiTool ||
            currentModel != settingsModel ||
            commitMessageAdditionalPromptArea?.text?.trim() != settings.commitMessageAdditionalPrompt ||
            openCodeTerminalCommandField?.text?.trim() != settings.openCodeTerminalCommand ||
            claudeCodeTerminalCommandField?.text?.trim() != settings.claudeCodeTerminalCommand ||
            onTurnEndCommandField?.text?.trim() != settings.onTurnEndCommand ||
            appendChangesToNextMessageCheckBox?.isSelected != settings.appendChangesToNextMessage
    }

    override fun apply() {
        saveCurrentCommitMessageModel()
        val settings = AiTerminalToolsSettings.getInstance().getState()
        settings.fileLinksEnabled = fileLinksCheckBox?.isSelected == true
        settings.copyLinksEnabled = copyLinksCheckBox?.isSelected == true
        settings.errorToAiTerminalIconsEnabled = errorToAiTerminalIconsCheckBox?.isSelected == true
        settings.dragToAiTerminalEnabled = dragToAiTerminalCheckBox?.isSelected == true
        settings.commitMessageAiTool = selectedCommitMessageAiTool
        settings.commitMessageModel = openCodeCommitMessageModel
        settings.claudeCommitMessageModel = claudeCommitMessageModel
        settings.commitMessageAdditionalPrompt = commitMessageAdditionalPromptArea?.text?.trim().orEmpty()
        settings.openCodeTerminalCommand = openCodeTerminalCommandField?.text?.trim().orEmpty()
        settings.claudeCodeTerminalCommand = claudeCodeTerminalCommandField?.text?.trim().orEmpty()
        settings.onTurnEndCommand = onTurnEndCommandField?.text?.trim().orEmpty()
        settings.appendChangesToNextMessage = appendChangesToNextMessageCheckBox?.isSelected == true
    }

    override fun reset() {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        fileLinksCheckBox?.isSelected = settings.fileLinksEnabled
        copyLinksCheckBox?.isSelected = settings.copyLinksEnabled
        errorToAiTerminalIconsCheckBox?.isSelected = settings.errorToAiTerminalIconsEnabled
        dragToAiTerminalCheckBox?.isSelected = settings.isDragToAiTerminalEnabled()
        selectedCommitMessageAiTool = normalizedCommitMessageAiTool(settings.commitMessageAiTool)
        openCodeCommitMessageModel = settings.commitMessageModel
        claudeCommitMessageModel = settings.claudeCommitMessageModel
        commitMessageAdditionalPromptArea?.text = settings.commitMessageAdditionalPrompt
        openCodeTerminalCommandField?.text = settings.openCodeTerminalCommand
        claudeCodeTerminalCommandField?.text = settings.claudeCodeTerminalCommand
        onTurnEndCommandField?.text = settings.onTurnEndCommand
        appendChangesToNextMessageCheckBox?.isSelected = settings.appendChangesToNextMessage
        updatingCommitMessageUi = true
        commitMessageAiToolCombo?.selectedItem = commitMessageAiToolLabel(selectedCommitMessageAiTool)
        updatingCommitMessageUi = false
        updateCommitMessageModelUi()
    }

    override fun disposeUIResources() {
        fileLinksCheckBox = null
        copyLinksCheckBox = null
        errorToAiTerminalIconsCheckBox = null
        dragToAiTerminalCheckBox = null
        commitMessageAiToolCombo = null
        commitMessageModelField = null
        commitMessageAdditionalPromptArea = null
        openCodeTerminalCommandField = null
        claudeCodeTerminalCommandField = null
        onTurnEndCommandField = null
        appendChangesToNextMessageCheckBox = null
        panel = null
    }

    private fun saveCurrentCommitMessageModel() {
        val model = commitMessageModelField?.text?.trim().orEmpty()
        if (selectedCommitMessageAiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
            claudeCommitMessageModel = model
        } else {
            openCodeCommitMessageModel = model
        }
    }

    private fun updateCommitMessageModelUi() {
        val model = if (selectedCommitMessageAiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
            claudeCommitMessageModel
        } else {
            openCodeCommitMessageModel
        }
        commitMessageModelField?.text = model
    }

    private fun commitMessageAiToolFromLabel(label: String?): String {
        return if (label == COMMIT_MESSAGE_AI_TOOL_CLAUDE_LABEL) {
            COMMIT_MESSAGE_AI_TOOL_CLAUDE
        } else {
            COMMIT_MESSAGE_AI_TOOL_OPENCODE
        }
    }

    private fun commitMessageAiToolLabel(aiTool: String): String {
        return if (aiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
            COMMIT_MESSAGE_AI_TOOL_CLAUDE_LABEL
        } else {
            COMMIT_MESSAGE_AI_TOOL_OPENCODE_LABEL
        }
    }

    private fun normalizedCommitMessageAiTool(aiTool: String): String {
        return if (aiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
            COMMIT_MESSAGE_AI_TOOL_CLAUDE
        } else {
            COMMIT_MESSAGE_AI_TOOL_OPENCODE
        }
    }

    companion object {
        private const val COMMIT_MESSAGE_AI_TOOL_OPENCODE = "opencode"
        private const val COMMIT_MESSAGE_AI_TOOL_CLAUDE = "claude"
        private const val COMMIT_MESSAGE_AI_TOOL_OPENCODE_LABEL = "OpenCode"
        private const val COMMIT_MESSAGE_AI_TOOL_CLAUDE_LABEL = "Claude Code"
    }
}
