// Settings panel for terminal links, console sending, drag-and-drop, and commit message generation.
package io.github.q110.aiterminaltools.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class AiTerminalToolsConfigurable : Configurable {
    private var fileLinksCheckBox: JBCheckBox? = null
    private var copyLinksCheckBox: JBCheckBox? = null
    private var errorToAiTerminalIconsCheckBox: JBCheckBox? = null
    private var dragToAiTerminalCheckBox: JBCheckBox? = null
    private var commitMessageAiToolCombo: ComboBox<String>? = null
    private var commitMessageModelCombo: ComboBox<String>? = null
    private var commitMessageAdditionalPromptArea: JBTextArea? = null
    private var additionalFileExtensionsField: JBTextField? = null
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
        // Store commit message models separately for each AI tool before switching the dropdown.
        val fileLinksCheckBox = JBCheckBox("Enable file navigation links")
        val copyLinksCheckBox = JBCheckBox("Enable click-to-copy links")
        val errorToAiTerminalIconsCheckBox = JBCheckBox("Enable console error send icons")
        val dragToAiTerminalCheckBox = JBCheckBox("Enable dragging files/folders to AI terminals")
        val commitMessageAiToolCombo = ComboBox(arrayOf(COMMIT_MESSAGE_AI_TOOL_OPENCODE_LABEL, COMMIT_MESSAGE_AI_TOOL_CLAUDE_LABEL))
        val commitMessageModelCombo = ComboBox<String>()
        commitMessageModelCombo.isEditable = true
        val refreshModelsButton = JButton("Refresh")
        refreshModelsButton.toolTipText = "Refresh models from OpenCode"
        val commitMessageModelPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        commitMessageModelPanel.add(commitMessageModelCombo)
        commitMessageModelPanel.add(refreshModelsButton)
        val commitMessageAdditionalPromptArea = JBTextArea(4, 48)
        val additionalFileExtensionsField = JBTextField()
        val openCodeTerminalCommandField = JBTextField()
        val claudeCodeTerminalCommandField = JBTextField()
        val onTurnEndCommandField = JBTextField()
        val appendChangesToNextMessageCheckBox = JBCheckBox("Append changes made in diff window to next agent message")
        val defaultFileExtensionsArea = JBTextArea(
            AiTerminalToolsSettings.StateData.DEFAULT_FILE_EXTENSIONS.joinToString(", ")
        )
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
        panel.add(commitMessageModelPanel, constraints)

        constraints.gridy = 9
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("Additional commit message prompt:"), constraints)

        constraints.gridy = 10
        constraints.insets = JBUI.insetsTop(4)
        commitMessageAdditionalPromptArea.lineWrap = true
        commitMessageAdditionalPromptArea.wrapStyleWord = true
        panel.add(JBScrollPane(commitMessageAdditionalPromptArea), constraints)

        constraints.gridy = 11
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("Additional file extensions:"), constraints)

        constraints.gridy = 12
        constraints.insets = JBUI.insetsTop(4)
        panel.add(additionalFileExtensionsField, constraints)

        constraints.gridy = 13
        val additionalExtensionsHelpLabel = JBLabel("The extensions below are already supported. Enter only additional extensions, separated by semicolons.")
        additionalExtensionsHelpLabel.foreground = JBColor.namedColor("Label.disabledForeground", JBColor(0x8c8c8c, 0x999999))
        additionalExtensionsHelpLabel.border = JBUI.Borders.emptyLeft(20)
        panel.add(additionalExtensionsHelpLabel, constraints)

        constraints.gridy = 14
        constraints.insets = JBUI.insetsTop(4)
        defaultFileExtensionsArea.isEditable = false
        defaultFileExtensionsArea.lineWrap = true
        defaultFileExtensionsArea.wrapStyleWord = true
        defaultFileExtensionsArea.isOpaque = false
        defaultFileExtensionsArea.foreground = JBColor.namedColor("Label.disabledForeground", JBColor(0x8c8c8c, 0x999999))
        defaultFileExtensionsArea.border = JBUI.Borders.emptyLeft(20)
        panel.add(defaultFileExtensionsArea, constraints)

        constraints.gridy = 15
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("OpenCode startup command:"), constraints)

        constraints.gridy = 16
        constraints.insets = JBUI.insetsTop(4)
        panel.add(openCodeTerminalCommandField, constraints)

        constraints.gridy = 17
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("Claude Code startup command:"), constraints)

        constraints.gridy = 18
        constraints.insets = JBUI.insetsTop(4)
        panel.add(claudeCodeTerminalCommandField, constraints)

        constraints.gridy = 19
        constraints.insets = JBUI.insetsTop(16)
        panel.add(JLabel("On turn end command:"), constraints)

        constraints.gridy = 20
        constraints.insets = JBUI.insetsTop(4)
        panel.add(onTurnEndCommandField, constraints)

        constraints.gridy = 21
        constraints.insets = JBUI.insetsTop(16)
        panel.add(appendChangesToNextMessageCheckBox, constraints)

        constraints.gridy = 22
        constraints.weighty = 1.0
        constraints.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), constraints)

        commitMessageAiToolCombo.addActionListener {
            if (updatingCommitMessageUi) return@addActionListener
            saveCurrentCommitMessageModel()
            selectedCommitMessageAiTool = commitMessageAiToolFromLabel(commitMessageAiToolCombo.selectedItem as? String)
            updateCommitMessageModelUi()
        }

        refreshModelsButton.addActionListener {
            if (selectedCommitMessageAiTool == COMMIT_MESSAGE_AI_TOOL_OPENCODE) loadOpenCodeModels()
        }

        this.fileLinksCheckBox = fileLinksCheckBox
        this.copyLinksCheckBox = copyLinksCheckBox
        this.errorToAiTerminalIconsCheckBox = errorToAiTerminalIconsCheckBox
        this.dragToAiTerminalCheckBox = dragToAiTerminalCheckBox
        this.commitMessageAiToolCombo = commitMessageAiToolCombo
        this.commitMessageModelCombo = commitMessageModelCombo
        this.commitMessageAdditionalPromptArea = commitMessageAdditionalPromptArea
        this.additionalFileExtensionsField = additionalFileExtensionsField
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
        val currentOpenCodeModel = if (currentAiTool == COMMIT_MESSAGE_AI_TOOL_OPENCODE) {
            commitMessageModelCombo?.editor?.item?.toString()?.trim().orEmpty()
        } else {
            openCodeCommitMessageModel
        }
        val currentClaudeModel = if (currentAiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
            commitMessageModelCombo?.editor?.item?.toString()?.trim().orEmpty()
        } else {
            claudeCommitMessageModel
        }
        return fileLinksCheckBox?.isSelected != settings.fileLinksEnabled ||
            copyLinksCheckBox?.isSelected != settings.copyLinksEnabled ||
            errorToAiTerminalIconsCheckBox?.isSelected != settings.errorToAiTerminalIconsEnabled ||
            dragToAiTerminalCheckBox?.isSelected != settings.isDragToAiTerminalEnabled() ||
            additionalFileExtensionsField?.text?.trim() != settings.additionalFileExtensions ||
            currentAiTool != settingsAiTool ||
            currentOpenCodeModel != settings.commitMessageModel ||
            currentClaudeModel != settings.claudeCommitMessageModel ||
            commitMessageAdditionalPromptArea?.text?.trim() != settings.commitMessageAdditionalPrompt ||
            openCodeTerminalCommandField?.text?.trim() != settings.openCodeTerminalCommand ||
            claudeCodeTerminalCommandField?.text?.trim() != settings.claudeCodeTerminalCommand ||
            onTurnEndCommandField?.text?.trim() != settings.onTurnEndCommand ||
            appendChangesToNextMessageCheckBox?.isSelected != settings.appendChangesToNextMessage
    }

    /** Write the current UI state to persistent settings. */
    override fun apply() {
        saveCurrentCommitMessageModel()
        val settings = AiTerminalToolsSettings.getInstance().getState()
        settings.fileLinksEnabled = fileLinksCheckBox?.isSelected == true
        settings.copyLinksEnabled = copyLinksCheckBox?.isSelected == true
        settings.errorToAiTerminalIconsEnabled = errorToAiTerminalIconsCheckBox?.isSelected == true
        settings.dragToAiTerminalEnabled = dragToAiTerminalCheckBox?.isSelected == true
        settings.additionalFileExtensions = additionalFileExtensionsField?.text?.trim().orEmpty()
        settings.commitMessageAiTool = selectedCommitMessageAiTool
        settings.commitMessageModel = openCodeCommitMessageModel
        settings.claudeCommitMessageModel = claudeCommitMessageModel
        settings.commitMessageAdditionalPrompt = commitMessageAdditionalPromptArea?.text?.trim().orEmpty()
        settings.openCodeTerminalCommand = openCodeTerminalCommandField?.text?.trim().orEmpty()
        settings.claudeCodeTerminalCommand = claudeCodeTerminalCommandField?.text?.trim().orEmpty()
        settings.onTurnEndCommand = onTurnEndCommandField?.text?.trim().orEmpty()
        settings.appendChangesToNextMessage = appendChangesToNextMessageCheckBox?.isSelected == true
    }

    /** Restore the UI from persistent settings without triggering a second model write. */
    override fun reset() {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        fileLinksCheckBox?.isSelected = settings.fileLinksEnabled
        copyLinksCheckBox?.isSelected = settings.copyLinksEnabled
        errorToAiTerminalIconsCheckBox?.isSelected = settings.errorToAiTerminalIconsEnabled
        dragToAiTerminalCheckBox?.isSelected = settings.isDragToAiTerminalEnabled()
        additionalFileExtensionsField?.text = settings.additionalFileExtensions
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
        commitMessageModelCombo = null
        commitMessageAdditionalPromptArea = null
        additionalFileExtensionsField = null
        openCodeTerminalCommandField = null
        claudeCodeTerminalCommandField = null
        onTurnEndCommandField = null
        appendChangesToNextMessageCheckBox = null
        panel = null
    }

    private fun saveCurrentCommitMessageModel() {
        val model = commitMessageModelCombo?.editor?.item?.toString()?.trim().orEmpty()
        if (selectedCommitMessageAiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
            claudeCommitMessageModel = model
        } else {
            openCodeCommitMessageModel = model
        }
    }

    /** Show the model value for the currently selected AI tool. */
    private fun updateCommitMessageModelUi() {
        if (selectedCommitMessageAiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
            commitMessageModelCombo?.editor?.item = claudeCommitMessageModel
        } else {
            commitMessageModelCombo?.editor?.item = openCodeCommitMessageModel
        }
        if (selectedCommitMessageAiTool == COMMIT_MESSAGE_AI_TOOL_OPENCODE) loadOpenCodeModels()
    }

    private fun loadOpenCodeModels() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val models = try {
                val baseCommand = AiTerminalToolsSettings.getInstance().getState().openCodeTerminalCommand.trim().ifEmpty { "opencode" }
                val process = ProcessBuilder(baseCommand, "models")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (process.waitFor() == 0) {
                    output.replace(ANSI_ESCAPE_REGEX, "")
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .toList()
                } else null
            } catch (_: Exception) {
                null
            }

            if (models != null) {
                ApplicationManager.getApplication().invokeLater {
                    val combo = commitMessageModelCombo
                    if (combo != null && selectedCommitMessageAiTool == COMMIT_MESSAGE_AI_TOOL_OPENCODE) {
                        val currentModel = combo.editor.item?.toString()?.trim().orEmpty()
                        val values = (models + currentModel).filter { it.isNotEmpty() }.distinct()
                        combo.removeAllItems()
                        values.forEach(combo::addItem)
                        combo.editor.item = currentModel
                    }
                }
            }
        }
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
        private val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]")
    }
}
