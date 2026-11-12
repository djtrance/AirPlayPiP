package io.github.jqssun.airplay.update

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class UpdateNetworkTest {
    @Test fun permitsPrivateLanAndLoopbackClients() {
        listOf("192.168.1.10", "10.0.0.2", "172.20.0.3", "127.0.0.1", "::1")
            .forEach { assertTrue("$it should be local", UpdateNetwork.allowRemote(InetAddress.getByName(it))) }
    }

    @Test fun rejectsPublicClientAddresses() {
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")
            .forEach { assertFalse("$it must not use the updater", UpdateNetwork.allowRemote(InetAddress.getByName(it))) }
    }
}
