// Regular-expression constants for parsing console text
package io.github.q110.aiterminaltools.filter

internal object FilterPatterns {
    // Match file references, including line numbers and ranges: Main.java:10-20
    fun fileRefPattern(extensions: Set<String>): Regex {
        val extensionPattern = extensions.joinToString("|") { Regex.escape(it) }
        val fileNamePattern = """(?:[\p{L}_$][\p{L}0-9_.$-]*\.(?:$extensionPattern)|\.(?:$extensionPattern))"""
        val pathPrefixPattern = """(?:(?:[A-Za-z]:)?[\\/]|\.{1,2}[\\/])?(?:[\p{L}0-9_.$-]+[\\/])+"""
        val pathPattern = """$pathPrefixPattern$fileNamePattern"""
        return Regex(
            """(?<![\\/\p{L}0-9_.$-])($pathPattern|$fileNamePattern)(?::(\d+)(?:-(\d+))?)?(?![\p{L}0-9_.$-])""",
            RegexOption.IGNORE_CASE
        )
    }

    // Match AI terminal @path references: @src/main/java/A.java:10
    val atPathRefPattern = Regex("""(?<![\p{L}0-9_$.-])@([\p{L}0-9_.$-]+(?:[\\/][\p{L}0-9_.$-]+)*)(?::(\d+)(?:-(\d+))?)?(?![\p{L}0-9_.$-])""")
    // Click-to-copy patterns, ordered from highest to lowest priority
    val copyPatterns = listOf(
        Regex("""\{\{[^{}\r\n]*[A-Za-z_$][^{}\r\n]*}}"""),
        Regex("""\[\[[^\r\n|]+]]"""),
        Regex("""\[[^\r\n|]*"[^"\r\n]+"[^\r\n|]*]"""),
        Regex("""(?<![\w$])\$?[A-Za-z_$][A-Za-z0-9_$]*\([^()\r\n]*\)"""),
        Regex("""(?<![\w$])/?[A-Za-z0-9_$.-]+(?:/[A-Za-z0-9_$?=&.-]+)+(?![\w$])"""),
        Regex("""(?<![\w$])\$?[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*|\[[A-Za-z_$][A-Za-z0-9_$.]*])+(?![\w$])"""),
        Regex("""(?<![\w$])(?:null|NaN|true|false)(?![\w$])"""),
        Regex("""(?<![\w$])\$?[A-Za-z_$][A-Za-z0-9_$-]*(?![\w$])"""),
        Regex("""(?<![\w.])\d+(?![\w.])""")
    )
}
