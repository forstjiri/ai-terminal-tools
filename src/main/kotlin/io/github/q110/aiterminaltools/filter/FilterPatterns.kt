// Regex constants for console text parsing
package io.github.q110.aiterminaltools.filter

internal object FilterPatterns {
    // Match file references, including line numbers and ranges: Main.java:10-20.
    // Character classes are Unicode-aware (\p{L}) so names containing letters such as
    // ě š č ř ž ý á í é ů ť ď ň are recognized in both file refs and click-to-copy text.
    fun fileRefPattern(extensions: Set<String>): Regex {
        val extensionPattern = extensions.joinToString("|") { Regex.escape(it) }
        val fileNamePattern = """(?:[\p{L}_$][\p{L}0-9_.$-]*\.(?:$extensionPattern)|\.(?:$extensionPattern))"""
        val pathPrefixPattern = """(?:(?:[A-Za-z]:)?[\\/]|\.{1,2}[\\/])?(?:[\p{L}0-9_.$-]+[\\/])+"""
        val pathPattern = """$pathPrefixPattern$fileNamePattern"""
        return Regex(
            """(?<![\\\p{L}0-9_.$/-])($pathPattern|$fileNamePattern)(?::(\d+)(?:-(\d+))?)?(?![\p{L}0-9_.$-])""",
            RegexOption.IGNORE_CASE
        )
    }

    // Match AI terminal @path references: @src/main/java/A.java:10
    val atPathRefPattern = Regex("""(?<![\p{L}0-9_$.-])@([\p{L}0-9_.$-]+(?:[\\/][\p{L}0-9_.$-]+)*)(?::(\d+)(?:-(\d+))?)?(?![\p{L}0-9_.$-])""")
    // Click-to-copy patterns, ordered from highest to lowest priority
    val copyPatterns = listOf(
        Regex("""\{\{[^{}\r\n]*[\p{L}_$][^{}\r\n]*}}"""),
        Regex("""\[\[[^\r\n|]+]]"""),
        Regex("""\[[^\r\n|]*"[^"\r\n]+"[^\r\n|]*]"""),
        Regex("""(?<![\p{L}0-9_$])\$?[\p{L}_$][\p{L}0-9_$]*\([^()\r\n]*\)"""),
        Regex("""(?<![\p{L}0-9_$])/?[\p{L}0-9_$.-]+(?:/[\p{L}0-9_$?=&.-]+)+(?![\p{L}0-9_$])"""),
        Regex("""(?<![\p{L}0-9_$])\$?[\p{L}_$][\p{L}0-9_$]*(?:\.[\p{L}_$][\p{L}0-9_$]*|\[[\p{L}_$][\p{L}0-9_$.]*])+(?![\p{L}0-9_$])"""),
        Regex("""(?<![\p{L}0-9_$])(?:null|NaN|true|false)(?![\p{L}0-9_$])"""),
        Regex("""(?<![\p{L}0-9_$])\$?[\p{L}_$][\p{L}0-9_$-]*(?![\p{L}0-9_$])"""),
        Regex("""(?<![\p{L}_0-9.])\d+(?![\p{L}_0-9.])""")
    )
}
