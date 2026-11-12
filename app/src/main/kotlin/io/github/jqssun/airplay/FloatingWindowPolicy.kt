package io.github.jqssun.airplay

/** Remote navigation only changes the requested edge; the other coordinate stays put. */
object FloatingWindowPolicy {
    enum class Direction { UP, DOWN, LEFT, RIGHT }

    fun move(corner: String, direction: Direction): String {
        val top = when (direction) {
            Direction.UP -> true
            Direction.DOWN -> false
            else -> corner.startsWith("top")
        }
        val left = when (direction) {
            Direction.LEFT -> true
            Direction.RIGHT -> false
            else -> corner.endsWith("left")
        }
        return "${if (top) "top" else "bottom"}_${if (left) "left" else "right"}"
    }

    fun nextCorner(corner: String): String = when (corner) {
        "top_left" -> "top_right"
        "top_right" -> "bottom_right"
        "bottom_right" -> "bottom_left"
        else -> "top_left"
    }
}

/** Detect output stalls, not a sender's intentionally still/idle screen. Times are monotonic ms. */
class FloatingRecoveryPolicy {
    private var input = 0L
    private var output = -1L
    private var lastInputAt = Long.MIN_VALUE
    private var lastProgressAt = -1L
    private var attempts = 0

    fun shouldRecreate(now: Long, inputFrames: Long, presentedFrames: Long): Boolean {
        if (inputFrames != input) {
            input = inputFrames
            lastInputAt = now
        }
        if (presentedFrames != output || lastProgressAt < 0) {
            output = presentedFrames
            lastProgressAt = now
            attempts = 0
            return false
        }
        if (attempts >= 3 || lastInputAt == Long.MIN_VALUE || now - lastInputAt > 8_000 ||
            now - lastProgressAt < 4_000) return false
        attempts++
        lastProgressAt = now
        return true
    }
}
