// Persistent settings service shared at the application level and stored in ai-terminal-tools.xml.
package io.github.q110.aiterminaltools.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "AiTerminalToolsSettings",
    storages = [Storage("ai-terminal-tools.xml")]
)
class AiTerminalToolsSettings : PersistentStateComponent<AiTerminalToolsSettings.StateData> {
    private var state = StateData()

    override fun getState(): StateData {
        return state
    }

    override fun loadState(state: StateData) {
        this.state = state
    }

    /** Persistent fields; feature flags are enabled by default. */
    class StateData {
        var fileLinksEnabled: Boolean = true
        var errorToAiTerminalIconsEnabled: Boolean = true
        var dragToAiTerminalEnabled: Boolean? = null
        var commitMessageAiTool: String = "opencode"
        var commitMessageModel: String = ""
        var claudeCommitMessageModel: String = ""
        var piCommitMessageModel: String = ""
        var codexCommitMessageModel: String = ""
        var openCodeTerminalCommand: String = ""
        var claudeCodeTerminalCommand: String = ""
        var piTerminalCommand: String = ""
        var codexTerminalCommand: String = ""
        var onTurnEndCommand: String = ""
        var appendChangesToNextMessage: Boolean = true
        var commitMessageAdditionalPrompt: String = ""

        /** Fall back to enabled when older configuration files lack this field. */
        fun isDragToAiTerminalEnabled(): Boolean {
            return dragToAiTerminalEnabled ?: true
        }

        /** Use the plugin default additional prompt when the user has not configured one. */
        fun resolvedCommitMessageAdditionalPrompt(): String {
            val customPrompt = commitMessageAdditionalPrompt.trim()
            return if (customPrompt.isNotEmpty()) customPrompt else DEFAULT_COMMIT_MESSAGE_ADDITIONAL_PROMPT
        }

        companion object {
            const val DEFAULT_COMMIT_MESSAGE_BASE_PROMPT: String =
                "Write concise English commit messages. Output one message per item without overthinking."

            const val DEFAULT_COMMIT_MESSAGE_ADDITIONAL_PROMPT: String =
                "Describe only the changes, not technical details. Keep each item short. Do not use backticks, Markdown code blocks, long sentences, or implementation details; output plain-text items only."
        }
    }

    companion object {
        fun getInstance(): AiTerminalToolsSettings {
            return ApplicationManager.getApplication().getService(AiTerminalToolsSettings::class.java)
        }
    }
}
