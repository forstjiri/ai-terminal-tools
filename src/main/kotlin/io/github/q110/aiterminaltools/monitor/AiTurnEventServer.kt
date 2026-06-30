// Local HTTP event server - receives event callbacks from Claude hooks / the OpenCode plugin
package io.github.q110.aiterminaltools.monitor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

@Service(Service.Level.PROJECT)
class AiTurnEventServer(
    private val project: Project
) : Disposable {
    private val log = Logger.getInstance(AiTurnEventServer::class.java)

    private var server: HttpServer? = null
    private var port: Int = -1

    /** Ensure the HTTP service is running and return the listening port. */
    @Synchronized
    fun ensureStarted(): Int {
        if (server != null) return port

        val address = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)
        val created = HttpServer.create(address, 0)

        created.createContext("/event") { exchange ->
            handleEvent(exchange)
        }

        created.executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AiTurnEventServer").apply { isDaemon = true }
        }
        created.start()

        server = created
        port = created.address.port
        log.info("AiTurnEventServer started on port $port")
        return port
    }

    private fun handleEvent(exchange: HttpExchange) {
        try {
            // Accept POST only.
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                return
            }

            // Enforce the Content-Length upper bound (2 MB).
            val contentLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0
            if (contentLength > MAX_BODY_SIZE) {
                log.warn("Request body too large: $contentLength bytes")
                exchange.sendResponseHeaders(413, -1)
                return
            }

            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (body.length > MAX_BODY_SIZE) {
                log.warn("Request body too large after reading: ${body.length} chars")
                exchange.sendResponseHeaders(413, -1)
                return
            }

            val token = exchange.requestHeaders.getFirst("X-AITT-Token")

            val event = parseEvent(body, token)
            if (event == null) {
                log.warn("Failed to parse event from body")
                exchange.sendResponseHeaders(400, -1)
                return
            }

            project.service<AiTurnMonitorService>().handle(event)

            exchange.sendResponseHeaders(204, -1)
        } catch (throwable: Throwable) {
            log.error("Error handling event", throwable)
            try {
                exchange.sendResponseHeaders(500, -1)
            } catch (_: Throwable) {
                // 忽略
            }
        } finally {
            exchange.close()
        }
    }

    /**
     * Parse the JSON body into an `AiTurnEvent`.
     * Uses a simple manual JSON parser to avoid adding third-party dependencies such as Gson/Jackson.
     */
    private fun parseEvent(body: String, headerToken: String?): AiTurnEvent? {
        val source = extractJsonString(body, "source") ?: return null
        val type = extractJsonString(body, "type") ?: return null
        val tabId = extractJsonString(body, "tabId") ?: return null
        val sessionId = extractJsonString(body, "sessionID")
            ?: extractJsonString(body, "sessionId")
        val paths = extractJsonStringArray(body, "paths")

        val aiTool = when (source.lowercase()) {
            "claude" -> AiTool.CLAUDE_CODE
            "opencode" -> AiTool.OPENCODE
            else -> return null
        }

        val eventType = when (type.lowercase().replace("-", "_")) {
            "turn_start" -> AiTurnEventType.TURN_START
            "before_write" -> AiTurnEventType.BEFORE_WRITE
            "file_changed" -> AiTurnEventType.FILE_CHANGED
            "turn_end" -> AiTurnEventType.TURN_END
            "turn_end_failed" -> AiTurnEventType.TURN_END_FAILED
            else -> return null
        }

        return AiTurnEvent(
            source = aiTool,
            type = eventType,
            tabId = tabId,
            token = headerToken,
            sessionId = sessionId,
            paths = paths,
            rawJson = body
        )
    }

    /** Extract a string value for the given key from a JSON string (simple implementation). */
    private fun extractJsonString(json: String, key: String): String? {
        // Match `"key": "value"` or `"key":"value"`.
        val pattern = Regex(""""${Regex.escape(key)}"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
        return pattern.find(json)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\/", "/")
            ?.replace("\\n", "\n")
            ?.replace("\\r", "\r")
            ?.replace("\\t", "\t")
    }

    /** Extract a string array value for the given key from a JSON string. */
    private fun extractJsonStringArray(json: String, key: String): List<String> {
        // Match `"key": [...]` or `"key":[...]`.
        val arrayPattern = Regex(""""${Regex.escape(key)}"\s*:\s*\[([^\]]*)]""")
        val arrayContent = arrayPattern.find(json)?.groupValues?.get(1) ?: return emptyList()

        val stringPattern = Regex(""""([^"\\]*(?:\\.[^"\\]*)*)"""")
        return stringPattern.findAll(arrayContent).map { matchResult ->
            matchResult.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
        }.toList()
    }

    override fun dispose() {
        server?.stop(0)
        server = null
        log.info("AiTurnEventServer stopped")
    }

    companion object {
        private const val MAX_BODY_SIZE = 2L * 1024 * 1024 // 2 MB
    }
}
