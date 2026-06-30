// Turn state machine - tracks each round of AI file modifications by terminal tab and upstream session
package io.github.q110.aiterminaltools.monitor

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class AiTurnMonitorService(
    private val project: Project
) {
    private val log = Logger.getInstance(AiTurnMonitorService::class.java)

    private val tabs = ConcurrentHashMap<String, AiTerminalTabContext>()
    private val turns = ConcurrentHashMap<String, AiTurnState>()

    /** Register an AI terminal started by the plugin; later HTTP events must match tabId/token. */
    fun registerTab(context: AiTerminalTabContext) {
        tabs[context.tabId] = context
        log.info("Registered AI terminal tab: ${context.tabId} (${context.tool})")
    }

    fun unregisterTab(tabId: String) {
        tabs.remove(tabId)
        val turn = turns.remove(tabId)
        if (turn != null && turn.changedFiles.isNotEmpty()) {
            log.info("Tab $tabId unregistered with ${turn.changedFiles.size} uncommitted changes, finishing turn")
            refreshAndShowDiff(turn)
        }
    }

    /** Expose the current active turns for downstream VFS fallback listeners. */
    fun activeTurns(): List<AiTurnState> {
        return turns.values.toList()
    }

    /** Handle standardized events returned by Claude hooks / the OpenCode plugin. */
    fun handle(event: AiTurnEvent) {
        val tab = tabs[event.tabId]
        if (tab == null) {
            log.warn("Received event for unknown tabId: ${event.tabId}")
            return
        }

        if (!tab.accepts(event)) {
            log.warn("Token mismatch for tabId: ${event.tabId}")
            return
        }

        when (event.type) {
            AiTurnEventType.TURN_START -> startTurn(tab, event)
            AiTurnEventType.BEFORE_WRITE -> beforeWrite(tab, event)
            AiTurnEventType.FILE_CHANGED -> fileChanged(tab, event)
            AiTurnEventType.TURN_END -> finishTurn(tab, event, failed = false)
            AiTurnEventType.TURN_END_FAILED -> finishTurn(tab, event, failed = true)
        }
    }

    private fun startTurn(tab: AiTerminalTabContext, event: AiTurnEvent) {
        val existing = turns[tab.tabId]
        if (existing != null) {
            if (tab.tool == AiTool.OPENCODE && attachOpenCodeSessionIfMissing(tab, existing, event)) {
                return
            }

            // The same OpenCode session may send busy repeatedly, so do not end the current turn early.
            if (isSameKnownOpenCodeSession(tab, existing, event)) {
                log.debug("Duplicate turn_start for OpenCode tab ${tab.tabId}, turn ${existing.turnId} continues")
                return
            }

            // Claude should normally end via Stop; if the previous turn is still open when a new one starts, finish the old one first.
            log.info("Previous turn ${existing.turnId} for tab ${tab.tabId} not finished, auto-finishing")
            turns.remove(tab.tabId, existing)
            if (existing.changedFiles.isNotEmpty()) {
                refreshAndShowDiff(existing)
            }
        }

        val turnId = UUID.randomUUID().toString()
        turns[tab.tabId] = AiTurnState(
            turnId = turnId,
            tabId = tab.tabId,
            tool = tab.tool,
            startedAtMillis = System.currentTimeMillis(),
            cwd = tab.workingDirectory,
            upstreamSessionId = event.sessionId
        )
        log.info("Started turn $turnId for tab ${tab.tabId}, session=${event.sessionId}")
    }

    private fun beforeWrite(tab: AiTerminalTabContext, event: AiTurnEvent) {
        val turn = turnForWriteEvent(tab, event) ?: return
        captureSnapshots(turn, event.paths)
    }

    private fun fileChanged(tab: AiTerminalTabContext, event: AiTurnEvent) {
        val turn = turnForWriteEvent(tab, event) ?: return
        markChangedPaths(turn, event)
    }

    private fun turnForWriteEvent(tab: AiTerminalTabContext, event: AiTurnEvent): AiTurnState? {
        val existing = turns[tab.tabId]
        if (existing == null) {
            log.debug("${event.type} received but no active turn for tab ${tab.tabId}, auto-starting turn")
            startTurn(tab, event)
            return turns[tab.tabId]
        }

        if (isDifferentKnownOpenCodeSession(tab, existing, event)) {
            // Late file events from an old session must not contaminate the current session's Diff.
            log.debug(
                "Ignoring ${event.type} for OpenCode session ${event.sessionId}; " +
                    "active turn ${existing.turnId} belongs to ${existing.upstreamSessionId}"
            )
            return null
        }

        if (tab.tool == AiTool.OPENCODE && attachOpenCodeSessionIfMissing(tab, existing, event)) {
            return turns[tab.tabId]
        }

        return existing
    }

    private fun attachOpenCodeSessionIfMissing(
        tab: AiTerminalTabContext,
        turn: AiTurnState,
        event: AiTurnEvent
    ): Boolean {
        if (tab.tool != AiTool.OPENCODE) return false
        val eventSessionId = event.sessionId
        if (turn.upstreamSessionId.isNullOrBlank() && !eventSessionId.isNullOrBlank()) {
            // `file_changed` / `before_write` may arrive before busy, so use the write event to fill in the sessionID.
            turns[tab.tabId] = turn.copy(upstreamSessionId = eventSessionId)
            log.debug("Attached OpenCode session $eventSessionId to turn ${turn.turnId}")
            return true
        }
        return false
    }

    private fun isSameKnownOpenCodeSession(
        tab: AiTerminalTabContext,
        turn: AiTurnState,
        event: AiTurnEvent
    ): Boolean {
        return tab.tool == AiTool.OPENCODE &&
            !turn.upstreamSessionId.isNullOrBlank() &&
            turn.upstreamSessionId == event.sessionId
    }

    private fun isDifferentKnownOpenCodeSession(
        tab: AiTerminalTabContext,
        turn: AiTurnState,
        event: AiTurnEvent
    ): Boolean {
        return tab.tool == AiTool.OPENCODE &&
            !turn.upstreamSessionId.isNullOrBlank() &&
            !event.sessionId.isNullOrBlank() &&
            turn.upstreamSessionId != event.sessionId
    }

    private fun markChangedPaths(turn: AiTurnState, event: AiTurnEvent) {
        // Use event paths when available; otherwise fall back to the set of paths captured for this turn.
        val paths = if (event.paths.isNotEmpty()) {
            event.paths.mapNotNull { normalizePath(turn.cwd, it) }
        } else {
            turn.beforeSnapshots.keys.toList()
        }

        val missingSnapshots = paths.filter { it !in turn.beforeSnapshots }
        if (missingSnapshots.isNotEmpty()) {
            log.warn(
                "file_changed for ${missingSnapshots.size} path(s) without before_snapshot: " +
                    missingSnapshots.joinToString { it.fileName?.toString() ?: it.toString() }
            )
        }

        for (path in paths) {
            turn.changedFiles.add(path)
        }
    }

    private fun finishTurn(tab: AiTerminalTabContext, event: AiTurnEvent, failed: Boolean) {
        val turn = turns[tab.tabId]
        if (turn == null) {
            log.debug("turn_end received but no active turn for tab ${tab.tabId}")
            return
        }

        if (tab.tool == AiTool.OPENCODE && !canFinishOpenCodeTurn(turn, event)) {
            // A late `session.idle` cannot end an active turn from a later session.
            log.debug(
                "Ignoring turn_end for OpenCode session ${event.sessionId}; " +
                    "active turn ${turn.turnId} belongs to ${turn.upstreamSessionId}"
            )
            return
        }

        if (!turns.remove(tab.tabId, turn)) {
            log.debug("Turn ${turn.turnId} was already finished for tab ${tab.tabId}")
            return
        }

        log.info(
            "Finishing turn ${turn.turnId} for tab ${tab.tabId}, " +
                "changed files: ${turn.changedFiles.size}, failed: $failed"
        )

        if (turn.changedFiles.isEmpty()) {
            return
        }

        refreshAndShowDiff(turn)
    }

    private fun canFinishOpenCodeTurn(turn: AiTurnState, event: AiTurnEvent): Boolean {
        val eventSessionId = event.sessionId
        if (eventSessionId.isNullOrBlank()) {
            log.warn("OpenCode turn_end without sessionID ignored for turn ${turn.turnId}")
            return false
        }

        val turnSessionId = turn.upstreamSessionId
        return turnSessionId.isNullOrBlank() || turnSessionId == eventSessionId
    }

    private fun captureSnapshots(turn: AiTurnState, rawPaths: List<String>) {
        val snapshotService = project.service<AiTurnSnapshotService>()
        val paths = rawPaths.mapNotNull { normalizePath(turn.cwd, it) }
        for (path in paths) {
            snapshotService.captureBeforeIfAbsent(turn, path)
        }
    }

    private fun refreshAndShowDiff(turn: AiTurnState) {
        try {
            // After an external CLI modifies files, refresh the VFS first so the Diff reads the latest content.
            val files = turn.changedFiles.map { it.toFile() }
            LocalFileSystem.getInstance().refreshIoFiles(files, false, true, null)
        } catch (exception: Throwable) {
            log.warn("Failed to refresh files", exception)
        }

        project.service<AiTurnDiffPresenter>().showDiff(turn)
    }

    internal fun normalizePath(cwd: Path, raw: String): Path? {
        val cleaned = raw.trim()
            .removePrefix("@")

        if (cleaned.isBlank()) return null

        val cleanedPath = cleaned.replace('\\', '/')

        val path = try {
            Path.of(cleanedPath)
        } catch (_: Throwable) {
            return null
        }

        val absolute = if (path.isAbsolute) path else cwd.resolve(path)
        val normalized = absolute.normalize()

        // Events are only allowed to reference files inside the project to avoid local HTTP access to out-of-project paths.
        val projectBase = project.basePath?.let { Path.of(it).normalize() } ?: return null
        if (!normalized.startsWith(projectBase)) {
            log.debug("Path $normalized is outside project base $projectBase, ignoring")
            return null
        }

        val relativePath = projectBase.relativize(normalized).toString()
        if (relativePath.isEmpty()) {
            log.debug("Path $normalized is project base, skipping")
            return null
        }

        if (Files.isDirectory(normalized)) {
            log.debug("Path $normalized is a directory, skipping")
            return null
        }

        for (ignored in IGNORED_DIRECTORIES) {
            if (relativePath.startsWith("$ignored/") || relativePath.startsWith("$ignored${File.separator}")) {
                log.debug("Path $normalized is in ignored directory $ignored, skipping")
                return null
            }
        }

        return normalized
    }

    companion object {
        private val IGNORED_DIRECTORIES = setOf(
            ".git", "node_modules", "target", "build", "dist", ".idea"
        )
    }
}
