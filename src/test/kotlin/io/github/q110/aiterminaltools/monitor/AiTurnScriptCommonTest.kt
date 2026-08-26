// Verifies the shared script snippets inline correctly into the generated installers
package io.github.q110.aiterminaltools.monitor

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText

class AiTurnScriptCommonTest {

    private fun readSnippet(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/scripts/$name")) { "missing snippet $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `shared snippets exist and expose the expected functions`() {
        val post = readSnippet("common-post.js")
        val paths = readSnippet("common-path-helpers.js")

        for (needle in listOf("function aittPost(", "function aittEnv(", "const AITT_PORT")) {
            assertTrue("common-post.js must contain $needle", needle in post)
        }
        for (needle in listOf(
            "function aittUnique(", "function aittExtractPaths(", "function aittExtractPatchPaths(",
            "function aittIsWriteToolName(", "function aittInputPathsFromText("
        )) {
            assertTrue("common-path-helpers.js must contain $needle", needle in paths)
        }
    }

    @Test
    fun `installers reference only shared helper functions they inline`() {
        val base = Paths.get("src/main/kotlin/io/github/q110/aiterminaltools/monitor")
        if (!Files.isDirectory(base)) return // not running from repo root

        val v1 = base.resolve("AiTurnOpenCodeInstaller.kt").readText()
        val v2 = base.resolve("AiTurnOpenCodeV2Installer.kt").readText()
        val pi = base.resolve("AiTurnPiInstaller.kt").readText()

        for (source in listOf(v1, v2, pi)) {
            assertTrue(
                "installers must load shared snippets",
                "AiTurnScriptCommon.load(" in source
            )
            // Legacy private helper names must be gone from generated content
            for (legacy in listOf("function unique(", "function extractPaths(", "function extractPatchPaths(", "function inputPathsFromText(")) {
                if (legacy in source) fail("$legacy still present in an installer — use the aitt* shared helper instead")
            }
        }
    }

    @Test
    fun `generated scripts are syntactically valid javascript`() {
        val base = Paths.get("src/main/kotlin/io/github/q110/aiterminaltools/monitor")
        if (!Files.isDirectory(base)) return // needs repo root

        val post = readSnippet("common-post.js")
        val paths = readSnippet("common-path-helpers.js")

        val cases = listOf(
            "AiTurnOpenCodeInstaller.kt" to "val js = \"\"\"",
            "AiTurnOpenCodeV2Installer.kt" to "val js = \"\"\"",
            "AiTurnPiInstaller.kt" to "val ts = \"\"\"",
        )
        for ((fileName, startMarker) in cases) {
            val source = base.resolve(fileName).readText().split("\n")
            val start = source.indexOfFirst { startMarker in it }
            check(start >= 0) { "$fileName has no raw string" }
            val end = source.indexOfFirst { it.trim() == "\"\"\".trimIndent()" && it != source[start] }
            check(end > start) { "$fileName has no raw-string end" }
            val body = source.subList(start + 1, end).joinToString("\n") { line ->
                val trimmed = line.trim()
                when {
                    trimmed == "\${AiTurnScriptCommon.load(\"common-post.js\")}\${AiTurnScriptCommon.load(\"common-path-helpers.js\")}" -> post + paths
                    trimmed == "\${AiTurnScriptCommon.load(\"common-path-helpers.js\")}" -> paths
                    trimmed == "\${AiTurnScriptCommon.load(\"common-post.js\")}" -> post
                    line.isBlank() -> ""
                    else -> line.substring(12)
                }
            }
            // Pi generates TypeScript; strip the few annotations for the node syntax check.
            val javascript = if (fileName.endsWith("Pi.kt")) body.replace(": any", "") else body
            val temp = Files.createTempFile("aitt-script", ".mjs")
            Files.writeString(temp, javascript)

            val process = ProcessBuilder("node", "--check", temp.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val exit = process.waitFor()
            assertTrue("node --check failed for $fileName:\n$output", exit == 0)
            Files.deleteIfExists(temp)
        }
    }
}
