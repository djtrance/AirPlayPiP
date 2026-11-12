package io.github.jqssun.airplay

/** These counters describe app buffers, never proof of pixels reaching the TV's physical panel. */
data class VideoCounters(val received: Long, val decoded: Long, val consumed: Long, val output: Long)

enum class VideoHealth { CHECKING, SOURCE_IDLE, DECODER_STALLED, GPU_STALLED, OUTPUT_STALLED, OUTPUT_ADVANCING }

data class VideoHealthSample(val state: VideoHealth, val rxFps: Int, val decodeFps: Int, val outputFps: Int)

class VideoHealthMonitor {
    private var previous: VideoCounters? = null
    private var previousAt = 0L
    private var inputAt = -1L
    private var decodedAt = -1L
    private var consumedAt = -1L
    private var outputAt = -1L

    fun sample(now: Long, counts: VideoCounters): VideoHealthSample {
        val old = previous
        if (old == null) {
            previous = counts
            previousAt = now
            inputAt = now
            decodedAt = now
            consumedAt = now
            outputAt = now
            return VideoHealthSample(VideoHealth.CHECKING, 0, 0, 0)
        }
        val elapsed = (now - previousAt).coerceAtLeast(1)
        if (counts.received != old.received) inputAt = now
        if (counts.decoded != old.decoded) decodedAt = now
        if (counts.consumed != old.consumed) consumedAt = now
        if (counts.output != old.output) outputAt = now
        val state = when {
            now - inputAt >= 4_000 -> VideoHealth.SOURCE_IDLE
            now - decodedAt >= 4_000 -> VideoHealth.DECODER_STALLED
            now - consumedAt >= 4_000 -> VideoHealth.GPU_STALLED
            now - outputAt >= 4_000 -> VideoHealth.OUTPUT_STALLED
            counts.output > old.output -> VideoHealth.OUTPUT_ADVANCING
            else -> VideoHealth.CHECKING
        }
        fun fps(current: Long, before: Long) = ((current - before).coerceAtLeast(0) * 1_000 / elapsed).toInt()
        val result = VideoHealthSample(state, fps(counts.received, old.received),
            fps(counts.decoded, old.decoded), fps(counts.output, old.output))
        previous = counts
        previousAt = now
        return result
    }
}
