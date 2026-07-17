// Diff presenter — uses the native IntelliJ Diff API to open a multi-file Diff window
package io.github.q110.aiterminaltools.monitor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.q110.aiterminaltools.bridge.AiTerminalBridgeService
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class AiTurnDiffPresenter(
    private val project: Project
) {
    private val log = Logger.getInstance(AiTurnDiffPresenter::class.java)

    @Volatile
    var lastTurn: AiTurnState? = null
        private set

    private var pendingAfterContents: Map<Path, String> = emptyMap()

    fun showDiff(turn: AiTurnState) {
        if (turn.changedFiles.isEmpty()) {
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val result = buildRequests(turn)
            val requests = result.requests
            val afterContents = result.afterContents
            if (requests.isEmpty()) {
                return@invokeLater
            }

            try {
                lastTurn = turn
                pendingAfterContents = afterContents
                AiTurnDiffDialog(project, requests) { handleDiffClosed(afterContents) }.show()
            } catch (exception: Throwable) {
                log.error("Failed to show diff", exception)
                notify("Failed to open the Diff window: ${exception.message}", NotificationType.WARNING)
            }
        }
    }

    fun showLastDiff() {
        val turn = lastTurn
        if (turn == null) {
            notify("No AI Turn Diff record is available to display.", NotificationType.INFORMATION)
            return
        }
        showDiff(turn)
    }

    private fun handleDiffClosed(afterContents: Map<Path, String>) {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        if (!settings.appendChangesToNextMessage) return

        val projectBasePath = project.basePath?.let { Path.of(it).normalize() }

        val revertedDiffs = StringBuilder()
        var revertedFileCount = 0

        for ((path, afterText) in afterContents) {
            val currentText = try {
                if (Files.exists(path)) Files.readString(path) else null
            } catch (_: Throwable) {
                null
            }

            if (currentText == null) continue
            if (currentText == afterText) continue

            val displayPath = projectBasePath
                ?.let { base -> runCatching { base.relativize(path).toString() }.getOrNull() }
                ?: path.toString()

            val diff = generateUnifiedDiff(displayPath, afterText, currentText)
            if (diff.isNotEmpty()) {
                if (revertedFileCount > 0) revertedDiffs.append("\n")
                revertedDiffs.append(diff)
                revertedFileCount++
            }
        }

        if (revertedFileCount == 0) return

        val payload = buildString {
            append("Lines reverted by user\n")
            append("-------\n")
            append(revertedDiffs)
            append("-------\n")
            append("The user reverted changes shown above in the diff window. Respect these reversions in your next response.")
        }

        try {
            val result = AiTerminalBridgeService.getInstance(project).sendDirectPasteToSelectedAiTerminal(payload)
            if (result is AiTerminalBridgeService.BridgeResult.Error) {
                log.warn("Failed to inject revert context: ${result.message}")
            }
        } catch (e: Throwable) {
            log.warn("Failed to inject revert context", e)
        }
    }

    private data class BuildResult(
        val requests: List<SimpleDiffRequest>,
        val afterContents: Map<Path, String>
    )

    private fun buildRequests(turn: AiTurnState): BuildResult {
        val contentFactory = DiffContentFactory.getInstance()
        val projectBasePath = project.basePath?.let { Path.of(it).normalize() }
        var skippedBinaryCount = 0
        val afterContents = linkedMapOf<Path, String>()

        val requests = turn.changedFiles.mapNotNull { path ->
            val oldSnapshot = turn.beforeSnapshots[path] ?: FileSnapshot.Missing
            if (!hasContentChange(path, oldSnapshot)) {
                return@mapNotNull null
            }

            if (oldSnapshot is FileSnapshot.Binary) {
                skippedBinaryCount++
                return@mapNotNull null
            }
            if (oldSnapshot is FileSnapshot.Missing && Files.exists(path)) {
                try {
                    val probe = Files.readAllBytes(path)
                    if (probe.any { it == 0.toByte() }) {
                        skippedBinaryCount++
                        return@mapNotNull null
                    }
                } catch (_: Throwable) {
                }
            }

            val virtualFile = try {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            } catch (_: Throwable) {
                null
            }

            val oldContent = when (oldSnapshot) {
                FileSnapshot.Missing -> {
                    contentFactory.createEmpty()
                }
                is FileSnapshot.Text -> {
                    if (virtualFile != null) {
                        contentFactory.create(project, oldSnapshot.text, virtualFile.fileType)
                    } else {
                        contentFactory.create(oldSnapshot.text)
                    }
                }
                is FileSnapshot.Binary -> {
                    return@mapNotNull null
                }
            }

            val currentText = try {
                if (Files.exists(path)) Files.readString(path) else ""
            } catch (_: Throwable) {
                ""
            }
            afterContents[path] = currentText

            val newContent = try {
                if (Files.exists(path)) {
                    if (virtualFile != null) {
                        contentFactory.create(project, virtualFile)
                    } else {
                        contentFactory.create(Files.readString(path))
                    }
                } else {
                    contentFactory.createEmpty()
                }
            } catch (exception: Throwable) {
                log.warn("Failed to read new content for $path", exception)
                contentFactory.createEmpty()
            }

            val displayPath = projectBasePath
                ?.let { base -> runCatching { base.relativize(path).toString() }.getOrNull() }
                ?: path.toString()

            SimpleDiffRequest(
                "AI Terminal change: $displayPath",
                oldContent,
                newContent,
                "Before AI turn",
                "After AI turn"
            ).apply {
                putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, false))
            }
        }

        if (skippedBinaryCount > 0) {
            notify("Skipped $skippedBinaryCount binary files.", NotificationType.INFORMATION)
        }

        return BuildResult(requests, afterContents)
    }

    private fun hasContentChange(path: Path, oldSnapshot: FileSnapshot): Boolean {
        return when (oldSnapshot) {
            FileSnapshot.Missing -> Files.exists(path) && !Files.isDirectory(path)
            is FileSnapshot.Text -> {
                if (!Files.exists(path)) {
                    true
                } else if (Files.isDirectory(path)) {
                    false
                } else {
                    try {
                        Files.readString(path, oldSnapshot.charset) != oldSnapshot.text
                    } catch (_: Throwable) {
                        true
                    }
                }
            }
            is FileSnapshot.Binary -> true
        }
    }

    private fun generateUnifiedDiff(displayPath: String, oldText: String, newText: String): String {
        return try {
            val oldLines = oldText.lines()
            val newLines = newText.lines()
            val changes = ComparisonManager.getInstance()
                .compareLines(oldText, newText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
            val sb = StringBuilder()
            sb.append("--- a/$displayPath\n")
            sb.append("+++ b/$displayPath\n")
            for (change in changes) {
                val oldStart = change.startLine1 + 1
                val newStart = change.startLine2 + 1
                val oldCount = change.endLine1 - change.startLine1
                val newCount = change.endLine2 - change.startLine2
                sb.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
                for (i in change.startLine1 until change.endLine1.coerceAtMost(oldLines.size)) {
                    sb.append("-${oldLines[i]}\n")
                }
                for (i in change.startLine2 until change.endLine2.coerceAtMost(newLines.size)) {
                    sb.append("+${newLines[i]}\n")
                }
            }
            sb.toString()
        } catch (e: Throwable) {
            log.warn("Failed to generate unified diff for $displayPath", e)
            ""
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message, type)
            .notify(project)
    }

    companion object {
        private const val NOTIFICATION_GROUP_ID = "AI Terminal Tools"
    }
}
