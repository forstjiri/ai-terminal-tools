// File snapshot service — saves file contents before modification
package io.github.q110.aiterminaltools.monitor

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class AiTurnSnapshotService(
    private val project: Project
) {
    private val log = Logger.getInstance(AiTurnSnapshotService::class.java)

    /**
     * Save a snapshot of the old contents before a file is modified.
     * Save only the first snapshot for each path; later modifications do not overwrite it.
     */
    fun captureBeforeIfAbsent(turn: AiTurnState, path: Path) {
        if (turn.beforeSnapshots.containsKey(path)) return

        val snapshot = try {
            captureSnapshot(path)
        } catch (exception: Throwable) {
            log.warn("Failed to capture snapshot for $path", exception)
            return
        }

        turn.beforeSnapshots[path] = snapshot
    }

    private fun captureSnapshot(path: Path): FileSnapshot {
        if (!Files.exists(path)) {
            return FileSnapshot.Missing
        }

        val size = Files.size(path)
        if (size > MAX_FILE_SIZE_BYTES) {
            log.info("Skipping snapshot for $path: size $size exceeds limit $MAX_FILE_SIZE_BYTES")
            return FileSnapshot.Missing
        }

        val bytes = Files.readAllBytes(path)

        if (isBinary(bytes)) {
            return FileSnapshot.Binary(
                bytes = bytes,
                fileTypeName = fileTypeName(path)
            )
        }

        val charset = detectCharset(bytes)
        return FileSnapshot.Text(
            text = bytes.toString(charset),
            charset = charset,
            fileTypeName = fileTypeName(path)
        )
    }

    private fun isBinary(bytes: ByteArray): Boolean {
        // Check the first 8000 bytes for a NUL character (a binary-file characteristic)
        val checkLength = minOf(bytes.size, 8000)
        for (i in 0 until checkLength) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        // BOM detection
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return Charsets.UTF_16LE
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return Charsets.UTF_16BE
            }
        }
        return Charsets.UTF_8
    }

    private fun fileTypeName(path: Path): String? {
        val fileName = path.fileName?.toString() ?: return null
        return try {
            FileTypeManager.getInstance().getFileTypeByFileName(fileName).name
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** Maximum single-file snapshot size: 2 MB */
        const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024
    }
}
