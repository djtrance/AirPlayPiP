package io.github.jqssun.airplay

import org.junit.Assert.*
import org.junit.Test

class VideoHealthTest {
    @Test fun activeCountersReportBufferProgressNotPhysicalPanelPlayback() {
        val monitor = VideoHealthMonitor()
        assertEquals(VideoHealth.CHECKING, monitor.sample(0, VideoCounters(0, 0, 0, 0)).state)
        val sample = monitor.sample(1_000, VideoCounters(30, 30, 30, 10))
        assertEquals(VideoHealth.OUTPUT_ADVANCING, sample.state)
        assertEquals(30, sample.rxFps)
        assertEquals(30, sample.decodeFps)
        assertEquals(10, sample.outputFps)
    }

    @Test fun receptionWithoutDecoderProgressIsNotMisclassifiedAsAnOverlayFault() {
        val monitor = VideoHealthMonitor()
        monitor.sample(0, VideoCounters(1, 0, 0, 0))
        assertEquals(VideoHealth.DECODER_STALLED, monitor.sample(4_000, VideoCounters(100, 0, 0, 0)).state)
    }

    @Test fun decodedFramesWithoutOffscreenConsumptionIndicateGpuStall() {
        val monitor = VideoHealthMonitor()
        monitor.sample(0, VideoCounters(1, 1, 0, 0))
        assertEquals(VideoHealth.GPU_STALLED, monitor.sample(4_000, VideoCounters(100, 100, 0, 0)).state)
    }

    @Test fun decodedAndConsumedFramesWithoutOutputIndicateOutputStall() {
        val monitor = VideoHealthMonitor()
        monitor.sample(0, VideoCounters(1, 1, 1, 1))
        assertEquals(VideoHealth.OUTPUT_STALLED, monitor.sample(4_000, VideoCounters(100, 100, 100, 1)).state)
    }

    @Test fun anIdleSenderDoesNotTriggerAFalseDecoderAlarm() {
        val monitor = VideoHealthMonitor()
        monitor.sample(0, VideoCounters(10, 10, 10, 10))
        assertEquals(VideoHealth.SOURCE_IDLE, monitor.sample(4_000, VideoCounters(10, 10, 10, 10)).state)
        assertEquals(VideoHealth.SOURCE_IDLE, monitor.sample(6_000, VideoCounters(10, 10, 10, 10)).state)
    }

    @Test fun counterResetNeverProducesNegativeFps() {
        val monitor = VideoHealthMonitor()
        monitor.sample(0, VideoCounters(100, 100, 100, 100))
        val result = monitor.sample(1_000, VideoCounters(0, 0, 0, 0))
        assertEquals(0, result.rxFps)
        assertEquals(0, result.decodeFps)
        assertEquals(0, result.outputFps)
    }
}
