package io.github.jqssun.airplay

data class DualAudioRoute(val primary: Boolean, val secondary: Boolean)

object DualAudioPolicy {
    fun route(enabled: Boolean, mode: String, selectedScreen: String): DualAudioRoute {
        if (!enabled) return DualAudioRoute(primary = true, secondary = false)
        return when (mode) {
            "mute" -> DualAudioRoute(false, false)
            "primary" -> DualAudioRoute(true, false)
            "secondary" -> DualAudioRoute(false, true)
            else -> when (selectedScreen) {
                "secondary" -> DualAudioRoute(false, true)
                else -> DualAudioRoute(true, false)
            }
        }
    }
}
