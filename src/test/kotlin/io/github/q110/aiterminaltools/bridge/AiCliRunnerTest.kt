package io.github.q110.aiterminaltools.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCliRunnerTest {
    @Test
    fun `recognizes Codex as a commit message tool`() {
        assertEquals("codex", AiCliRunner.normalizedCommitMessageAiTool("codex"))
        assertEquals("Codex", AiCliRunner.toolDisplayName("codex"))
    }

    @Test
    fun `uses pure mode only for OpenCode 1`() {
        assertTrue(AiCliRunner.opencodeRunCommand("opencode", "prompt", true).contains("--pure"))
        assertFalse(AiCliRunner.opencodeRunCommand("opencode2", "prompt", false).contains("--pure"))
    }
}
