package io.github.q110.aiterminaltools.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPathFromUriTest {

    @Test
    fun `extracts raw absolute paths`() {
        assertEquals("/home/jirka/plan.md", localPathFromUri("/home/jirka/plan.md"))
    }

    @Test
    fun `extracts home shorthand paths`() {
        val path = localPathFromUri("~/notes.md")
        assertEquals(System.getProperty("user.home") + "/notes.md", path)
    }

    @Test
    fun `extracts windows drive paths`() {
        assertEquals("C:/Users/dev/notes.md", localPathFromUri("C:\\Users\\dev\\notes.md"))
    }

    @Test
    fun `extracts file uris`() {
        assertEquals("/etc/hosts", localPathFromUri("file:///etc/hosts"))
        assertEquals("/home/jirka/my file.md", localPathFromUri("file:///home/jirka/my%20file.md"))
        assertEquals("/etc/hosts", localPathFromUri("file://localhost/etc/hosts"))
    }

    @Test
    fun `rejects web and unknown schemes`() {
        assertNull(localPathFromUri("https://example.com/page"))
        assertNull(localPathFromUri("http://localhost:3000/api"))
        assertNull(localPathFromUri("mailto:someone@example.com"))
        assertNull(localPathFromUri("javascript:alert(1)"))
        assertNull(localPathFromUri("example.com/path"))
    }

    @Test
    fun `rejects empty values`() {
        assertNull(localPathFromUri(""))
        assertNull(localPathFromUri("   "))
    }
}
