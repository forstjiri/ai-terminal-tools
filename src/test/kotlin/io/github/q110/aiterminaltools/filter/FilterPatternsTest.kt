package io.github.q110.aiterminaltools.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FilterPatternsTest {

    @Test
    fun `matches absolute file references with line ranges`() {
        val match = FilterPatterns.fileRefPattern.find(
            "See /home/jirka/Projekty/Bonami/MCP_INSTUCTIONS.md:12-14"
        )

        assertNotNull(match)
        assertEquals("/home/jirka/Projekty/Bonami/MCP_INSTUCTIONS.md", match!!.groupValues[1])
        assertEquals("12", match.groupValues[2])
        assertEquals("14", match.groupValues[3])
    }

    @Test
    fun `matches home shorthand file references`() {
        val match = FilterPatterns.fileRefPattern.find("Open ~/.opencode/plan/FOR-2528.md")

        assertNotNull(match)
        assertEquals("~/.opencode/plan/FOR-2528.md", match!!.groupValues[1])
    }

    @Test
    fun `matches file names followed by a sentence period`() {
        val match = FilterPatterns.fileRefPattern.find(
            "Plán uložen: service-trpc-generation-lifecycle-file-plan.md."
        )

        assertNotNull(match)
        assertEquals("service-trpc-generation-lifecycle-file-plan.md", match!!.groupValues[1])
    }

    @Test
    fun `matches absolute paths followed by a sentence period`() {
        val match = FilterPatterns.fileRefPattern.find(
            "Open ~/.opencode/plan/FOR-2528.md."
        )

        assertNotNull(match)
        assertEquals("~/.opencode/plan/FOR-2528.md", match!!.groupValues[1])
    }

    @Test
    fun `matches at references with absolute and home shorthand paths`() {
        val absolute = FilterPatterns.atPathRefPattern.find("@/home/jirka/Projekty/Bonami/MCP_INSTUCTIONS.md:7")
        val home = FilterPatterns.atPathRefPattern.find("@~/.opencode/plan/FOR-2528.md:3")

        assertNotNull(absolute)
        assertEquals("/home/jirka/Projekty/Bonami/MCP_INSTUCTIONS.md", absolute!!.groupValues[1])
        assertEquals("7", absolute.groupValues[2])
        assertNotNull(home)
        assertEquals("~/.opencode/plan/FOR-2528.md", home!!.groupValues[1])
        assertEquals("3", home.groupValues[2])
    }

    @Test
    fun `matches markdown links with local destinations`() {
        val match = FilterPatterns.markdownLinkPattern.find(
            "[plan](/home/jirka/.opencode/plan/playwright-env-gated-management-api-file-plan.md)"
        )

        assertNotNull(match)
        assertEquals("plan", match!!.groupValues[1])
        assertEquals(
            "/home/jirka/.opencode/plan/playwright-env-gated-management-api-file-plan.md",
            match.groupValues[2]
        )
    }

    @Test
    fun `matches markdown links with urls and file schemes`() {
        val url = FilterPatterns.markdownLinkPattern.find("[docs](https://example.com/a/b.html)")
        val file = FilterPatterns.markdownLinkPattern.find("[local](file:///etc/hosts)")

        assertNotNull(url)
        assertEquals("https://example.com/a/b.html", url!!.groupValues[2])
        assertNotNull(file)
        assertEquals("file:///etc/hosts", file!!.groupValues[2])
    }
}
