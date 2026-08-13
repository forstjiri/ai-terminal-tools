// File snapshot service — saves file contents before modification
package io.github.q110.aiterminaltools.monitor

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

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

    /** Capture the project state for CLIs that only announce completed turns. */
    fun captureProjectBefore(turn: AiTurnState, root: Path) {
        projectFiles(root).forEach { captureBeforeIfAbsent(turn, it) }
    }

    /** Capture only Git-tracked project files for CLIs that do not report changed paths. */
    fun captureGitTrackedProjectBefore(turn: AiTurnState, root: Path) {
        gitTrackedProjectFiles(root).forEach { captureBeforeIfAbsent(turn, it) }
    }

    /** Find project files whose current contents differ from a turn's baseline. */
    fun changedProjectFiles(turn: AiTurnState, root: Path): Set<Path> {
        val paths = LinkedHashSet<Path>()
        paths.addAll(turn.beforeSnapshots.keys)
        paths.addAll(projectFiles(root))
        return paths.filterTo(linkedSetOf()) { path ->
            val before = turn.beforeSnapshots[path]
            if (before == null) {
                turn.beforeSnapshots[path] = FileSnapshot.Missing
                true
            } else {
                hasContentChange(path, before)
            }
        }
    }

    /** Find changed Git-tracked files, including files newly added to the index during the turn. */
    fun changedGitTrackedProjectFiles(turn: AiTurnState, root: Path): Set<Path> {
        val paths = LinkedHashSet<Path>()
        paths.addAll(turn.beforeSnapshots.keys)
        paths.addAll(gitTrackedProjectFiles(root))
        return paths.filterTo(linkedSetOf()) { path ->
            val before = turn.beforeSnapshots[path]
            if (before == null) {
                turn.beforeSnapshots[path] = FileSnapshot.Missing
                true
            } else {
                hasContentChange(path, before)
            }
        }
    }

    private fun gitTrackedProjectFiles(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()

        val projectRoot = root.toAbsolutePath().normalize()
        val gitRoot = gitWorktreeRoot(projectRoot) ?: return projectFiles(root)
        val result = runGit(
            listOf(
                "-C", gitRoot.toString(),
                "ls-files", "-z", "--cached", "--"
            )
        )

        if (result == null || result.exitCode != 0 || result.truncated) {
            log.warn("Failed to enumerate Git-tracked files for Codex snapshots")
            return emptyList()
        }

        return gitPathRecords(result.stdout)
            .filter { it.isNotEmpty() }
            .mapNotNull { rawPath ->
                val path = try {
                    gitRoot.resolve(String(rawPath, StandardCharsets.UTF_8)).normalize()
                } catch (_: Throwable) {
                    null
                }
                if (path != null && path.startsWith(projectRoot)) path else null
            }
            .take(MAX_PROJECT_FILES.toInt())
            .toList()
    }

    private fun gitPathRecords(output: ByteArray): Sequence<ByteArray> = sequence {
        var recordStart = 0
        for (index in output.indices) {
            if (output[index] != 0.toByte()) continue
            yield(output.copyOfRange(recordStart, index))
            recordStart = index + 1
        }
    }

    private fun gitWorktreeRoot(root: Path): Path? {
        val worktreeResult = runGit(
            listOf("-C", root.toString(), "rev-parse", "--is-inside-work-tree")
        ) ?: return null
        if (worktreeResult.exitCode != 0 || worktreeResult.stdoutText.trim() != "true") return null

        val rootResult = runGit(listOf("-C", root.toString(), "rev-parse", "--show-toplevel"))
            ?: return null
        if (rootResult.exitCode != 0 || rootResult.stdoutText.isBlank()) return null

        return try {
            Path.of(rootResult.stdoutText.trim()).toAbsolutePath().normalize()
        } catch (_: Throwable) {
            null
        }
    }

    private fun runGit(arguments: List<String>): GitCommandResult? {
        val command = buildList {
            add("git")
            addAll(arguments)
        }

        return try {
            val process = ProcessBuilder(command).start()
            val stdout = ProcessOutputCollector(process.inputStream)
            val stderr = ProcessOutputCollector(process.errorStream)
            val stdoutThread = Thread({ stdout.collect() }, "ai-terminal-tools-git-stdout")
            val stderrThread = Thread({ stderr.collect() }, "ai-terminal-tools-git-stderr")
            stdoutThread.isDaemon = true
            stderrThread.isDaemon = true
            stdoutThread.start()
            stderrThread.start()

            val completed = process.waitFor(GIT_PROCESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(GIT_PROCESS_TERMINATION_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(GIT_PROCESS_TERMINATION_MILLIS, TimeUnit.MILLISECONDS)
                }
            }

            stdoutThread.join(GIT_PROCESS_TERMINATION_MILLIS)
            stderrThread.join(GIT_PROCESS_TERMINATION_MILLIS)
            if (!completed) return null

            GitCommandResult(
                exitCode = process.exitValue(),
                stdout = stdout.bytes(),
                stderr = stderr.bytes(),
                truncated = stdout.truncated || stderr.truncated
            )
        } catch (exception: Throwable) {
            log.debug("Git process execution failed", exception)
            null
        }
    }

    private fun projectFiles(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return try {
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { path -> !isIgnored(root, path) }
                    .limit(MAX_PROJECT_FILES)
                    .collect(Collectors.toList())
            }
        } catch (exception: Throwable) {
            log.warn("Failed to enumerate project files for Codex snapshots", exception)
            emptyList()
        }
    }

    private fun isIgnored(root: Path, path: Path): Boolean {
        val relative = root.relativize(path)
        return relative.any { it.toString() in IGNORED_DIRECTORIES }
    }

    private fun hasContentChange(path: Path, before: FileSnapshot): Boolean = when (before) {
        FileSnapshot.Missing -> Files.exists(path) && !Files.isDirectory(path)
        is FileSnapshot.Text -> !Files.exists(path) || try {
            Files.readString(path, before.charset) != before.text
        } catch (_: Throwable) {
            true
        }
        is FileSnapshot.Binary -> true
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

    private data class GitCommandResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
        val truncated: Boolean
    ) {
        val stdoutText: String
            get() = String(stdout, StandardCharsets.UTF_8)
    }

    private class ProcessOutputCollector(private val input: InputStream) {
        private val output = ByteArrayOutputStream()
        @Volatile
        var truncated: Boolean = false
            private set

        fun collect() {
            try {
                input.use { stream ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        val remaining = MAX_GIT_OUTPUT_BYTES - total
                        if (remaining > 0) {
                            val kept = minOf(count.toLong(), remaining).toInt()
                            output.write(buffer, 0, kept)
                            if (kept < count) truncated = true
                        } else {
                            truncated = true
                        }
                        total += count
                    }
                }
            } catch (_: Throwable) {
                // The process may close its streams while it is being terminated.
            }
        }

        fun bytes(): ByteArray = output.toByteArray()
    }

    companion object {
        /** Maximum single-file snapshot size: 2 MB */
        const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024
        private const val MAX_PROJECT_FILES = 10_000L
        private const val MAX_GIT_OUTPUT_BYTES = 32L * 1024 * 1024
        private const val GIT_PROCESS_TIMEOUT_MILLIS = 10L * 1000
        private const val GIT_PROCESS_TERMINATION_MILLIS = 1L * 1000
        private val IGNORED_DIRECTORIES = setOf(
            ".git", ".idea", ".codex", "node_modules", "target", "build", "dist"
        )
    }
}
