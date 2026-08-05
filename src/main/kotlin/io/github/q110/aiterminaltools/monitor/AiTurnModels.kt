// Shared data models for the monitor module
package io.github.q110.aiterminaltools.monitor

import java.nio.charset.Charset
import java.nio.file.Path

/** AI terminal tool type */
enum class AiTool {
    OPENCODE,
    CLAUDE_CODE,
    PI,
}

/** Turn event type */
enum class AiTurnEventType {
    TURN_START,
    BEFORE_WRITE,
    FILE_CHANGED,
    TURN_END,
    TURN_END_FAILED
}

/** Event received from the HTTP endpoint */
data class AiTurnEvent(
    val source: AiTool,
    val type: AiTurnEventType,
    val tabId: String,
    val token: String?,
    val sessionId: String?,
    val paths: List<String>,
    val rawJson: String
)

/** State of one conversation turn */
data class AiTurnState(
    val turnId: String,
    val tabId: String,
    val tool: AiTool,
    val startedAtMillis: Long,
    val cwd: Path,
    val upstreamSessionId: String?,
    val beforeSnapshots: MutableMap<Path, FileSnapshot> = linkedMapOf(),
    val changedFiles: LinkedHashSet<Path> = linkedSetOf()
)

/** Snapshot taken before a file modification */
sealed interface FileSnapshot {
    /** File does not exist (new-file case) */
    data object Missing : FileSnapshot

    /** Text-file snapshot */
    data class Text(
        val text: String,
        val charset: Charset,
        val fileTypeName: String?
    ) : FileSnapshot

    /** Binary-file snapshot */
    data class Binary(
        val bytes: ByteArray,
        val fileTypeName: String?
    ) : FileSnapshot {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false
            return bytes.contentEquals(other.bytes) && fileTypeName == other.fileTypeName
        }

        override fun hashCode(): Int {
            return 31 * bytes.contentHashCode() + (fileTypeName?.hashCode() ?: 0)
        }
    }
}

/** Context for a registered AI terminal tab */
data class AiTerminalTabContext(
    val tabId: String,
    val token: String,
    val tool: AiTool,
    val workingDirectory: Path,
    val createdAtMillis: Long
) {
    /** Validate the event token: allow a missing token; otherwise it must match */
    fun accepts(event: AiTurnEvent): Boolean {
        return event.token == null || event.token == token
    }
}
