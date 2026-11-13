package com.omni.aiassistant.core.logging

import java.util.concurrent.ConcurrentLinkedDeque

class SafeLogger(private val capacity: Int = 200) {
    private val lines = ConcurrentLinkedDeque<String>()
    private val maxLines = capacity.coerceAtLeast(1)

    fun info(tag: String, message: String) = append("I/$tag ${sanitize(message)}")
    fun warn(tag: String, message: String) = append("W/$tag ${sanitize(message)}")
    fun error(tag: String, message: String) = append("E/$tag ${sanitize(message)}")

    fun snapshot(): List<String> = lines.toList()

    private fun append(line: String) {
        lines.addLast("${System.currentTimeMillis()} $line")
        while (lines.size > maxLines && lines.isNotEmpty()) lines.pollFirst()
    }

    /**
     * Removes credential values before they reach the log. The value part
     * swallows an optional "Bearer " scheme prefix, so an
     * "Authorization: Bearer <token>" header never leaves the token behind.
     */
    private fun sanitize(value: String): String = value
        .replace(
            Regex(
                "(?i)(authorization|x[-_ ]?api[-_ ]?key|api[-_ ]?key|token|password|secret)" +
                    "\\s*[:=]\\s*((bearer|basic|digest|negotiate)\\s+)?[^,\\s]+",
            ),
            "$1=<redacted>",
        )
        .take(1200)
}
