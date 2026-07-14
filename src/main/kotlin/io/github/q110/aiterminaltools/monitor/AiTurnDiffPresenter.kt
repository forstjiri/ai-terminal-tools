// Diff presenter — uses the native IntelliJ Diff API to open a multi-file Diff window
package io.github.q110.aiterminaltools.monitor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class AiTurnDiffPresenter(
    private val project: Project
) {
    private val log = Logger.getInstance(AiTurnDiffPresenter::class.java)

    /** Most recently completed turn state, used by "Show Last AI Turn Diff" */
    @Volatile
    var lastTurn: AiTurnState? = null
        private set

    /**
     * Display Diffs for all files modified in the specified turn.
     * Open a multi-file Diff window through IntelliJ DiffManager on the EDT.
     */
    fun showDiff(turn: AiTurnState) {
        if (turn.changedFiles.isEmpty()) {
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val requests = buildRequests(turn)
            if (requests.isEmpty()) {
                return@invokeLater
            }

            try {
                lastTurn = turn
                AiTurnDiffDialog(project, requests).show()
            } catch (exception: Throwable) {
                log.error("Failed to show diff", exception)
                notify("Failed to open the Diff window: ${exception.message}", NotificationType.WARNING)
            }
        }
    }

    /** Reopen the last Diff */
    fun showLastDiff() {
        val turn = lastTurn
        if (turn == null) {
            notify("No AI Turn Diff record is available to display.", NotificationType.INFORMATION)
            return
        }
        showDiff(turn)
    }

    private fun buildRequests(turn: AiTurnState): List<SimpleDiffRequest> {
        val contentFactory = DiffContentFactory.getInstance()
        val projectBasePath = project.basePath?.let { Path.of(it).normalize() }
        var skippedBinaryCount = 0

        val requests = turn.changedFiles.mapNotNull { path ->
            val oldSnapshot = turn.beforeSnapshots[path] ?: FileSnapshot.Missing
            if (!hasContentChange(path, oldSnapshot)) {
                return@mapNotNull null
            }

            // Skip binary files
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
                    // Continue trying if reading fails
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
                    // Already skipped; this point is unreachable
                    return@mapNotNull null
                }
            }

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
            )
        }

        if (skippedBinaryCount > 0) {
            notify("Skipped $skippedBinaryCount binary files.", NotificationType.INFORMATION)
        }

        return requests
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
