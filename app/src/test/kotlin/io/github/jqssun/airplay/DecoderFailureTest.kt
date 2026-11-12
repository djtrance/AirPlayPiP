package io.github.jqssun.airplay

import io.github.jqssun.airplay.renderer.DecoderFailure
import org.junit.Assert.*
import org.junit.Test

class DecoderFailureTest {
    @Test fun emptyRealtekExceptionStillIdentifiesTheFailingOperation() {
        val message = DecoderFailure.summary(IllegalStateException(), "dequeue_output", "OMX.realtek.video.decoder")
        assertTrue(message.startsWith("dequeue_output"))
        assertTrue(message.contains("IllegalStateException: <no message>"))
        assertTrue(message.contains("OMX.realtek.video.decoder"))
    }

    @Test fun summaryKeepsTheAppCallSiteAndCodecErrorMetadata() {
        val error = IllegalStateException("codec stopped")
        error.stackTrace = arrayOf(StackTraceElement("io.github.jqssun.airplay.renderer.VideoRenderer", "drainOutput", "VideoRenderer.kt", 42))
        val text = DecoderFailure.summary(error, "release_output", "decoder", "code=1101 recoverable=true transient=false")
        assertTrue(text.contains("drainOutput:42"))
        assertTrue(text.contains("recoverable=true"))
        assertTrue(text.contains("code=1101"))
    }

    @Test fun noFakeCallSiteWhenFrameworkOnlyStackIsAvailable() {
        val error = IllegalStateException()
        error.stackTrace = emptyArray()
        val text = DecoderFailure.summary(error, "configure", "decoder")
        assertFalse(text.contains(" at "))
        assertTrue(text.contains("configure"))
    }
}
