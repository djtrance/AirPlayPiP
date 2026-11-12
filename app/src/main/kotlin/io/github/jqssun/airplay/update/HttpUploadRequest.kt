package io.github.jqssun.airplay.update

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

data class HttpUploadRequest(val method: String, val path: String, val headers: Map<String, String>)

/** Small bounded HTTP/1.x parser for one request per LAN connection. */
object HttpUploadRequestParser {
    private const val MAX_LINE = 4_096
    private const val MAX_HEADERS = 16_384
    private const val MAX_HEADER_COUNT = 40

    @Throws(IOException::class)
    fun parse(input: BufferedInputStream): HttpUploadRequest {
        val line = readLine(input, MAX_LINE) ?: throw IOException("empty request")
        val parts = line.split(' ')
        if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) throw IOException("malformed request line")
        if (parts[0] !in setOf("GET", "PUT")) throw IOException("unsupported method")
        if (!parts[1].startsWith('/') || parts[1].startsWith("//") || parts[1].contains('#')) throw IOException("invalid path")

        val headers = linkedMapOf<String, String>()
        var bytes = line.length + 2
        var count = 0
        while (true) {
            val header = readLine(input, MAX_LINE) ?: throw IOException("incomplete headers")
            bytes += header.length + 2
            if (bytes > MAX_HEADERS) throw IOException("headers too large")
            if (header.isEmpty()) break
            if (++count > MAX_HEADER_COUNT) throw IOException("too many headers")
            if (header.firstOrNull()?.isWhitespace() == true) throw IOException("folded headers not supported")
            val colon = header.indexOf(':')
            if (colon <= 0) throw IOException("malformed header")
            val name = header.substring(0, colon).trim().lowercase()
            if (!name.all { it.isLetterOrDigit() || it == '-' }) throw IOException("invalid header name")
            val value = header.substring(colon + 1).trim()
            if (headers.putIfAbsent(name, value) != null) throw IOException("duplicate header")
        }
        if (headers.containsKey("transfer-encoding")) throw IOException("chunked uploads are not supported")
        if (headers.containsKey("expect")) throw IOException("expect header is not supported")
        return HttpUploadRequest(parts[0], parts[1], headers)
    }

    private fun readLine(input: BufferedInputStream, max: Int): String? {
        val output = ByteArrayOutputStream()
        while (output.size() <= max) {
            val next = input.read()
            if (next < 0) return if (output.size() == 0) null else throw IOException("truncated line")
            if (next == '\n'.code) {
                val value = output.toByteArray()
                val size = if (value.lastOrNull() == '\r'.code.toByte()) value.size - 1 else value.size
                if (size > max) throw IOException("line too large")
                return String(value, 0, size, StandardCharsets.US_ASCII)
            }
            output.write(next)
        }
        throw IOException("line too large")
    }
}
