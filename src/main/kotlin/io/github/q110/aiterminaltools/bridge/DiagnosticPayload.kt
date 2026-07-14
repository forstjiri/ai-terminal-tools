package io.github.q110.aiterminaltools.bridge

import com.intellij.openapi.util.text.StringUtil

internal object DiagnosticPayload {
    fun message(description: String?, toolTip: String?): String? {
        return description?.takeIf { it.isNotBlank() }
            ?: toolTip?.let { StringUtil.stripHtml(it, true).trim() }?.takeIf { it.isNotBlank() }
    }

    fun format(filePath: String?, line: Int, message: String): String {
        val header = filePath?.let { "@$it:$line\n" }.orEmpty()
        return "$header-------\n$message\n-------\n"
    }
}
