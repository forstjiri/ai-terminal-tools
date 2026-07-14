package io.github.q110.aiterminaltools.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticPayloadTest {
    @Test
    fun `keeps plain text generic diagnostics unchanged`() {
        assertEquals(
            "Expected List<String> but found List<Int>",
            DiagnosticPayload.message("Expected List<String> but found List<Int>", "<html>fallback</html>"),
        )
    }

    @Test
    fun `converts an HTML tooltip only when plain text is unavailable`() {
        assertEquals("First line\n\nSecond line", DiagnosticPayload.message(null, "<html>First line<br>Second line</html>"))
    }

    @Test
    fun `returns no message for empty diagnostics`() {
        assertNull(DiagnosticPayload.message("", "<html> </html>"))
    }

    @Test
    fun `formats a diagnostic with file and line context`() {
        assertEquals(
            "@src/App.kt:42\n-------\nUnresolved reference\n-------\n",
            DiagnosticPayload.format("src/App.kt", 42, "Unresolved reference"),
        )
    }

    @Test
    fun `formats a diagnostic without file context`() {
        assertEquals("-------\nWarning\n-------\n", DiagnosticPayload.format(null, 1, "Warning"))
    }
}
