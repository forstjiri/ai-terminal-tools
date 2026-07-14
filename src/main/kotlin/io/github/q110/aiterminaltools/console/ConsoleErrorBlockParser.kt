// Console error-block parser — extracts contiguous error fragments from Run/Debug output for sending to AI
package io.github.q110.aiterminaltools.console

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange

internal data class ConsoleErrorBlock(
    val startLine: Int,
    val startOffset: Int,
    val iconOffset: Int,
    val endOffset: Int
)

internal object ConsoleErrorBlockParser {
    /** Error-block start patterns cover common JVM, Python, Node, TypeScript, Go, Rust, Ruby, and C/C++ output */
    private val pythonTracebackHeaderPattern = Regex("""^\s*Traceback\s*\(\s*most\s+recent\s+call\s+last\s*\)\s*:$""")
    private val pythonExceptionChainPattern = Regex("""^\s*(?:During handling of the above exception, another exception occurred:|The above exception was the direct cause of the following exception:)\s*$""")
    private val pythonExceptionSummaryPattern = Regex("""^\s*[A-Za-z_]\w*(?:Error|Exception|Warning|Interrupt)(?:\s*:\s*.*)?$""")
    private val exceptionStartPatterns = listOf(
        Regex("""^\s*(?:Exception in thread\s+"[^"]+"\s+)?(?:Caused by:\s+|Suppressed:\s+)?(?:[\w$]+(?:\.[\w$]+)*(?:Exception|Error|Throwable|Failure)|[\w$]+(?:Exception|Error|Throwable|Failure))(?:[:\s].*)?$"""),
        pythonTracebackHeaderPattern,
        pythonExceptionChainPattern,
        pythonExceptionSummaryPattern,
        Regex("""^\s*(?:Uncaught\s+)?(?:Error|TypeError|ReferenceError|SyntaxError|RangeError|URIError|EvalError|AggregateError)(?:\s*:\s*.*)?$"""),
        Regex("""^\s*(?:\S+\.(?:ts|tsx|mts|cts)\(\d+,\d+\)|\S+\.(?:ts|tsx|mts|cts):\d+:\d+)\s*[-:]?\s*error\s+TS\d{4}:\s+.+$"""),
        Regex("""^\s*error\s+TS\d{4}:\s+.+$"""),
        Regex("""^\s*panic:\s+.+$"""),
        Regex("""^\s*thread\s+'[^']+'\s+panicked\s+at\s+.+:\d+:\d+:\s*$"""),
        Regex("""^\s*error(?:\[E\d{4}\])?:\s+.+$"""),
        Regex("""^\s*[^:\s]+\.rb:\d+:in\s+'[^']+':\s+.+\([A-Za-z_]\w*(?:Error|Exception)\)\s*$"""),
        Regex("""^\s*\S+\.(?:c|cc|cpp|cxx|h|hh|hpp|hxx):\d+:\d+:\s*(?:fatal\s+)?error:\s+.+$""")
    )
    private val blockLinePatterns = listOf(
        Regex("""^\s+at\s+.+\(.+\)\s*$"""),
        Regex("""^\s+\.\.\.\s+\d+\s+more\s*$"""),
        Regex("""^\s+\.\.\.\s+\d+\s+common\s+frames\s+omitted\s*$"""),
        Regex("""^\s+Suppressed:\s+.+$"""),
        Regex("""^\s+File\s+"[^"]+",\s+line\s+\d+,\s+in\s+.+$"""),
        Regex("""^\s*\^+\s*$"""),
        Regex("""^\s*~+\s*$"""),
        Regex("""^\s+at\s+.+:\d+:\d+\)?\s*$"""),
        Regex("""^\s*goroutine\s+\d+\s+\[[^\]]+]:\s*$"""),
        Regex("""^\s*(?:[\w./\-]+/)*[\w.\-]+\.go:\d+\s+\+0x[0-9a-fA-F]+\s*$"""),
        Regex("""^\s*(?:[\w./\-]+\.)?[\w./\-]+\(.*\)\s*$"""),
        Regex("""^\s*stack\s+backtrace:\s*$"""),
        Regex("""^\s*\d+:\s+.+$"""),
        Regex("""^\s+at\s+.+\.rs:\d+:\d+\s*$"""),
        Regex("""^\s*-->\s+\S+\.rs:\d+:\d+\s*$"""),
        Regex("""^\s*=\s*(?:note|help|warning):\s+.+$"""),
        Regex("""^\s*(?:note|help|warning):\s+.+$"""),
        Regex("""^\s*\|\s*.*$"""),
        Regex("""^\s+\d+\s+\|\s+.*$"""),
        Regex("""^\s+from\s+[^:\s]+\.rb:\d+:in\s+'[^']+'\s*$"""),
        Regex("""^\s*\S+\.(?:c|cc|cpp|cxx|h|hh|hpp|hxx):\d+:\d+:\s*(?:note|warning):\s+.+$""")
    )
    private val blockTerminators = listOf(
        Regex("""^\s*<\d+\s+folded\s+frames>\s*$"""),
        Regex("""^\s*(?:Disconnected|Process\s+finished|Abort\s+trap|zsh:\s+|bash:\s+).*$"""),
        Regex("""^\s*\[(?:INFO|WARN|Process\s+exited).*$""")
    )

    /** Scan the document line by line and combine error headers with stack traces/diagnostic context */
    fun parse(document: Document): List<ConsoleErrorBlock> {
        val blocks = mutableListOf<ConsoleErrorBlock>()
        var currentStartLine: Int? = null
        var currentStartOffset = 0
        var currentIconOffset = 0
        var currentEndOffset = 0
        var currentEndLine = 0
        var currentAcceptsPythonSummary = false

        for (lineNumber in 0 until document.lineCount) {
            val lineStart = document.getLineStartOffset(lineNumber)
            val lineEnd = document.getLineEndOffset(lineNumber)
            val line = document.getText(TextRange(lineStart, lineEnd))
            val inBlock = currentStartLine != null

            if (!inBlock) {
                if (isExceptionStart(line)) {
                    currentStartLine = lineNumber
                    currentStartOffset = lineStart
                    currentIconOffset = iconOffset(line, lineStart, lineEnd)
                    currentEndOffset = lineEnd
                    currentEndLine = lineNumber
                    currentAcceptsPythonSummary = startsPythonTracebackBlock(line)
                }
                continue
            }

            when {
                currentAcceptsPythonSummary && isPythonTracebackLine(line) -> {
                    currentEndOffset = lineEnd
                    currentEndLine = lineNumber
                }
                isBlockLine(line) -> {
                    currentEndOffset = lineEnd
                    currentEndLine = lineNumber
                }
                isBlockTerminator(line) -> {
                    finishBlock(blocks, currentStartLine, currentStartOffset, currentIconOffset, currentEndOffset)
                    currentStartLine = null
                    currentAcceptsPythonSummary = false
                }
                isExceptionStart(line) -> {
                    finishBlock(blocks, currentStartLine, currentStartOffset, currentIconOffset, currentEndOffset)
                    currentStartLine = lineNumber
                    currentStartOffset = lineStart
                    currentIconOffset = iconOffset(line, lineStart, lineEnd)
                    currentEndOffset = lineEnd
                    currentEndLine = lineNumber
                    currentAcceptsPythonSummary = startsPythonTracebackBlock(line)
                }
                line.isBlank() && currentEndLine < lineNumber - 1 -> {
                    finishBlock(blocks, currentStartLine, currentStartOffset, currentIconOffset, currentEndOffset)
                    currentStartLine = null
                    currentAcceptsPythonSummary = false
                }
                else -> {
                }
            }
        }

        finishBlock(blocks, currentStartLine, currentStartOffset, currentIconOffset, currentEndOffset)
        return blocks
    }

    private fun finishBlock(
        blocks: MutableList<ConsoleErrorBlock>,
        startLine: Int?,
        startOffset: Int,
        iconOffset: Int,
        endOffset: Int
    ) {
        if (startLine == null || endOffset <= startOffset) return
        blocks += ConsoleErrorBlock(startLine, startOffset, iconOffset.coerceIn(startOffset, endOffset), endOffset)
    }

    private fun isExceptionStart(line: String): Boolean {
        return matchesAny(line, exceptionStartPatterns)
    }

    private fun isPythonTracebackLine(line: String): Boolean {
        return matchesAny(
            line,
            listOf(pythonTracebackHeaderPattern, pythonExceptionChainPattern, pythonExceptionSummaryPattern)
        )
    }

    private fun startsPythonTracebackBlock(line: String): Boolean {
        return matchesAny(line, listOf(pythonTracebackHeaderPattern, pythonExceptionChainPattern))
    }

    private fun isBlockLine(line: String): Boolean {
        return matchesAny(line, blockLinePatterns) || isLikelySourceLine(line)
    }

    private fun isBlockTerminator(line: String): Boolean {
        return matchesAny(line, blockTerminators)
    }

    private fun matchesAny(line: String, patterns: List<Regex>): Boolean {
        val trimmedLine = line.trimEnd()
        return patterns.any { it.matches(trimmedLine) }
    }

    private fun isLikelySourceLine(line: String): Boolean {
        val trimmedLine = line.trimEnd()
        return line.startsWith("    ") && trimmedLine.isNotBlank() && !isBlockTerminator(trimmedLine)
    }

    private fun iconOffset(line: String, lineStart: Int, lineEnd: Int): Int {
        val searchStart = causedByPrefixPattern.find(line)?.range?.last?.plus(1) ?: 0
        val messageSeparatorIndex = line.indexOf(':', startIndex = searchStart)
        return if (messageSeparatorIndex >= 0) {
            lineStart + messageSeparatorIndex
        } else {
            lineEnd
        }
    }

    private val causedByPrefixPattern = Regex("""^\s*Caused by:""")
}
