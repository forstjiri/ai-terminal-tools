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
}
