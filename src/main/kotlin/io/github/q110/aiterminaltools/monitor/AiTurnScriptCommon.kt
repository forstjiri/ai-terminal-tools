// Loads shared generated-script snippets from plugin resources
package io.github.q110.aiterminaltools.monitor

import com.intellij.openapi.diagnostic.Logger
import java.io.InputStream

/**
 * The installers generate self-contained JS/TS files (OpenCode loads local plugins
 * without dependency installation), so shared helpers live as resource snippets and
 * are inlined into every generated script at install time.
 */
object AiTurnScriptCommon {
    private val log = Logger.getInstance(AiTurnScriptCommon::class.java)

    private const val RESOURCE_BASE = "/scripts/"

    fun load(name: String): String {
        val stream: InputStream? = AiTurnScriptCommon::class.java.getResourceAsStream(RESOURCE_BASE + name)
        if (stream == null) {
            log.error("Shared script snippet not found: $name")
            throw IllegalStateException("Missing shared script snippet: $name")
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }.trim() + "\n"
    }
}
