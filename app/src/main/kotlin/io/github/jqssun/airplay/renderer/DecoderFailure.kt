package io.github.jqssun.airplay.renderer

/** Preserve the failing operation even when a vendor throws IllegalStateException with no message. */
object DecoderFailure {
    fun summary(error: Exception, operation: String, component: String, details: String = ""): String {
        val location = error.stackTrace.firstOrNull { it.className.endsWith(".VideoRenderer") }
            ?.let { "${it.methodName}:${it.lineNumber}" }.orEmpty()
        return "$operation · ${error.javaClass.simpleName}: ${error.message?.takeIf { it.isNotBlank() } ?: "<no message>"}" +
            "\ncodec=$component" + (if (location.isNotEmpty()) " at $location" else "") +
            (if (details.isNotBlank()) "\n$details" else "")
    }
}
