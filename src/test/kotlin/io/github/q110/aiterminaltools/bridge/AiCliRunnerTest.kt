package io.github.q110.aiterminaltools.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class AiCliRunnerTest {
    @Test
    fun `recognizes Codex as a commit message tool`() {
        assertEquals("codex", AiCliRunner.normalizedCommitMessageAiTool("codex"))
        assertEquals("Codex", AiCliRunner.toolDisplayName("codex"))
    }
}
