package io.github.jqssun.airplay

/** Platform PiP only accepts aspect ratios in [1/2.39, 2.39]. Portrait senders need letterboxing. */
object PresentationPolicy {
    fun pipAspect(aspect: Float): Float =
        (if (aspect.isFinite() && aspect > 0) aspect else 16f / 9f).coerceIn(1f / 2.39f, 2.39f)

    fun canAutoPip(enabled: Boolean, running: Boolean, visualSession: Boolean, floating: Boolean): Boolean =
        enabled && running && visualSession && !floating
}
