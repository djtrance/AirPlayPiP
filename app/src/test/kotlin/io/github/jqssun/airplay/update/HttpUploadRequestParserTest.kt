package io.github.jqssun.airplay.update

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class HttpUploadRequestParserTest {
    private fun parse(request: String) = HttpUploadRequestParser.parse(
        BufferedInputStream(ByteArrayInputStream(request.toByteArray(Charsets.US_ASCII))))

    @Test fun parsesUploadHeadersCaseInsensitivelyAndStopsAtBody() {
        val result = parse("PUT /upload HTTP/1.1\r\nContent-Length: 42\r\nx-update-token: AB12\r\n\r\nAPKDATA")
        assertEquals("PUT", result.method)
        assertEquals("/upload", result.path)
        assertEquals("42", result.headers["content-length"])
        assertEquals("AB12", result.headers["x-update-token"])
    }

    @Test fun parsesRootGet() {
        assertEquals("/", parse("GET / HTTP/1.1\r\nHost: tv\r\n\r\n").path)
    }

    @Test fun parserStopsExactlyAtTheBeginningOfTheApkBody() {
        val body = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val headers = "PUT /upload HTTP/1.1\r\nContent-Length: 4\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val input = BufferedInputStream(ByteArrayInputStream(headers + body))
        HttpUploadRequestParser.parse(input)
        assertArrayEquals(body, ByteArray(body.size).also { assertEquals(body.size, input.read(it)) })
    }

    @Test fun rejectsUnsupportedMethodsAndMalformedPaths() {
        listOf("POST /upload HTTP/1.1\r\n\r\n", "GET //evil HTTP/1.1\r\n\r\n", "GET x HTTP/1.1\r\n\r\n")
            .forEach { assertThrows(IOException::class.java) { parse(it) } }
    }

    @Test fun rejectsDuplicateAndChunkedLengthFraming() {
        assertThrows(IOException::class.java) { parse("PUT /upload HTTP/1.1\r\nContent-Length: 1\r\ncontent-length: 1\r\n\r\n") }
        assertThrows(IOException::class.java) { parse("PUT /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n") }
    }

    @Test fun rejectsFoldedAndOversizedHeaders() {
        assertThrows(IOException::class.java) { parse("GET / HTTP/1.1\r\n folded: value\r\n\r\n") }
        val huge = "X-Fill: " + "a".repeat(4_100)
        assertThrows(IOException::class.java) { parse("GET / HTTP/1.1\r\n$huge\r\n\r\n") }
    }
}
