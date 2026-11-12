package io.github.jqssun.airplay.update

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object UpdateNetwork {
    fun allowRemote(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress

    fun addresses(interfaces: Iterable<NetworkInterface>): List<String> = runCatching {
        interfaces.filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())
}
