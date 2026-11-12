package io.github.jqssun.airplay.service

/** A second independent RAOP/HTTP/DNS-SD and MediaCodec instance for dual-screen mode. */
class SecondaryAirPlayService : AirPlayService() {
    override val sessionIndex: Int = 1
}
