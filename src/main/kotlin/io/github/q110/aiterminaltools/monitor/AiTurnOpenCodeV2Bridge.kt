// File-based bridge between the IDEA plugin and the generated OpenCode V2 JS plugin
package io.github.q110.aiterminaltools.monitor

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * OpenCode V2 runs plugins inside a shared background server process, which does not
 * inherit AITT_* environment variables from the terminal launcher. The generated JS
 * plugin therefore reads the event-server port and tab credentials from this JSON file:
 *
 *   { "port": 43127, "tabs": [ { "tabId": "...", "token": "..." } ] }
 *
 * The IDEA side rewrites the file whenever an OpenCode 2 terminal starts or closes.
 * The file lives under .idea/ai-terminal-tools next to the launcher scripts that
 * already embed the same token, so it does not widen the trust boundary.
 */
object AiTurnOpenCodeV2Bridge {
    private val log = Logger.getInstance(AiTurnOpenCodeV2Bridge::class.java)

    private val tabEntryPattern = Regex("""\{\s*"tabId"\s*:\s*"([^"]+)"\s*,\s*"token"\s*:\s*"([^"]+)"\s*}""")

    fun bridgeFile(basePath: Path): Path =
        basePath.resolve(".idea").resolve("ai-terminal-tools").resolve("opencode2-bridge.json")

    /** Write the complete bridge file; tabs are ordered oldest-first so the JS side can pick the newest */
    fun write(basePath: Path, port: Int, tabs: List<Pair<String, String>>) {
        try {
            val file = bridgeFile(basePath)
            Files.createDirectories(file.parent)
            val json = buildString {
                appendLine("{")
                appendLine("  \"port\": $port,")
                appendLine("  \"tabs\": [")
                tabs.forEachIndexed { index, (tabId, token) ->
                    append("    { \"tabId\": \"${escape(tabId)}\", \"token\": \"${escape(token)}\" }")
                    if (index < tabs.lastIndex) appendLine(",") else appendLine()
                }
                appendLine("  ]")
                appendLine("}")
            }
            Files.writeString(file, json)
        } catch (throwable: Throwable) {
            log.warn("Failed to write OpenCode V2 bridge file: ${throwable.message}")
        }
    }

    /** Add or refresh one tab entry, keeping existing entries and port */
    fun installTab(basePath: Path, tabId: String, token: String, port: Int) {
        val existing = readTabs(basePath)
        write(basePath, port, existing.filter { it.first != tabId } + (tabId to token))
    }

    fun removeTab(basePath: Path, tabId: String, fallbackPort: Int) {
        write(basePath, fallbackPort, readTabs(basePath).filter { it.first != tabId })
    }

    private fun readTabs(basePath: Path): List<Pair<String, String>> {
        val file = bridgeFile(basePath)
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            tabEntryPattern
                .findAll(Files.readString(file))
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
