// Regular-expression constants for parsing console text
package io.github.q110.aiterminaltools.filter

internal object FilterPatterns {
    val fileRefPattern: Regex = run {
        val fileName = """[\p{L}_$][\p{L}0-9_.$-]*\.[\p{L}0-9]{2,12}"""
        val prefix = """(?:(?:~[\\/])|(?:(?:[A-Za-z]:)?[\\/])|\.{1,2}[\\/])?(?:[\p{L}0-9_.$-]+[\\/])*"""
        Regex(
            """(?<![\\/\p{L}0-9_.$-~])($prefix$fileName)(?::(\d+)(?:-(\d+))?)?(?![\p{L}0-9_.$-])""",
            RegexOption.IGNORE_CASE
        )
    }

    val atPathRefPattern = run {
        val prefix = """(?:(?:~[\\/])|(?:(?:[A-Za-z]:)?[\\/])|\.{1,2}[\\/])?"""
        val path = """[\p{L}0-9_.$-]+(?:[\\/][\p{L}0-9_.$-]+)*"""
        Regex(
            """(?<![\p{L}0-9_$.-])@($prefix$path)(?::(\d+)(?:-(\d+))?)?(?![\p{L}0-9_.$-])"""
        )
    }
}
