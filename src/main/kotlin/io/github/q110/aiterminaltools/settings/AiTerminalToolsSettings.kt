// Configuration persistence layer - app-level singleton stored in ai-terminal-tools.xml
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

    /** Persistent field definitions. Default values are enabled unless noted otherwise. */
    class StateData {
        var fileLinksEnabled: Boolean = true
        var copyLinksEnabled: Boolean = true
        var errorToAiTerminalIconsEnabled: Boolean = true
        var dragToAiTerminalEnabled: Boolean? = null
        var commitMessageAiTool: String = "opencode"
        var commitMessageModel: String = ""
        var claudeCommitMessageModel: String = ""
        var commitMessageAdditionalPrompt: String = ""
        var additionalFileExtensions: String = ""

        /** Missing field in older persisted configs falls back to enabled. */
        fun isDragToAiTerminalEnabled(): Boolean {
            return dragToAiTerminalEnabled ?: true
        }

        /** Use the plugin default additional prompt when the user has not configured one. */
        fun resolvedCommitMessageAdditionalPrompt(): String {
            val customPrompt = commitMessageAdditionalPrompt.trim()
            return if (customPrompt.isNotEmpty()) customPrompt else DEFAULT_COMMIT_MESSAGE_ADDITIONAL_PROMPT
        }

        /** Merge the default extensions with user-provided additions. */
        fun resolvedFileExtensions(): Set<String> {
            val customExtensions = additionalFileExtensions
                .split(";")
                .map { it.trim().removePrefix(".").lowercase() }
                .filter { it.isNotEmpty() && it.matches(EXTENSION_PATTERN) }
                .toSet()

            return DEFAULT_FILE_EXTENSIONS + customExtensions
        }

        companion object {
            private val EXTENSION_PATTERN = Regex("[a-z][a-z0-9]*")

            const val DEFAULT_COMMIT_MESSAGE_BASE_PROMPT: String =
                "Generate concise English commit messages, output them as bullet points, and return results quickly without overthinking."

            const val DEFAULT_COMMIT_MESSAGE_ADDITIONAL_PROMPT: String =
                "Only write the result of the change. Do not include technical details. Keep each item short. Avoid backticks, Markdown code blocks, long sentences, and implementation details. Output plain text bullets only."

            val DEFAULT_FILE_EXTENSIONS = setOf(
                "java", "kt", "kts", "gradle",
                "js", "ts", "vue",
                "html", "css", "scss", "sass", "less",
                "py", "c", "cpp", "cc",
                "ps1", "cmd",
                "json", "toml", "yaml", "yml", "conf", "env", "properties", "xml",
                "md", "sql"
            )
        }
    }

    companion object {
        fun getInstance(): AiTerminalToolsSettings {
            return ApplicationManager.getApplication().getService(AiTerminalToolsSettings::class.java)
        }
    }
}
