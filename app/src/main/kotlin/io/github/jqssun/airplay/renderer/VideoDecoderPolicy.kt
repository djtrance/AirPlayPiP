package io.github.jqssun.airplay.renderer

/** Platform decoder metadata, kept independent of Android for focused regression tests. */
data class VideoDecoderCandidate(
    val name: String,
    val softwareOnly: Boolean,
    val hardwareAccelerated: Boolean,
    val supportsSize: Boolean = true
)

object VideoDecoderPolicy {
    fun useSoftwareInPip(configuredMode: String, inPip: Boolean, enabled: Boolean): Boolean =
        configuredMode == "auto" && inPip && enabled

    fun conservativeHardware(mode: String, name: String): Boolean =
        mode == "hardware_compat" || name.startsWith("OMX.realtek.", ignoreCase = true) ||
            name.startsWith("c2.realtek.", ignoreCase = true)

    fun knownSoftware(name: String): Boolean = name.lowercase().let {
        it.startsWith("c2.android.") || it.startsWith("omx.google.")
    }

    fun isSoftware(candidate: VideoDecoderCandidate): Boolean =
        knownSoftware(candidate.name) || (candidate.softwareOnly && !candidate.hardwareAccelerated)

    fun chooseSoftware(candidates: List<VideoDecoderCandidate>): VideoDecoderCandidate? =
        candidates.filter { it.supportsSize && isSoftware(it) }.minByOrNull {
            when {
                it.name.startsWith("c2.android.", ignoreCase = true) -> 0
                it.name.startsWith("omx.google.", ignoreCase = true) -> 1
                else -> 2
            }
        }

    fun chooseHardware(candidates: List<VideoDecoderCandidate>): VideoDecoderCandidate? =
        candidates.firstOrNull { it.supportsSize && it.hardwareAccelerated && !isSoftware(it) }
}
