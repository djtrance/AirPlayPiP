package io.github.jqssun.airplay.update

import org.junit.Assert.*
import org.junit.Test

class ApkUpdatePolicyTest {
    private val current = ApkUpdateMetadata("io.github.airplaypip", 6, setOf("current-cert"))

    @Test fun acceptsOnlyNewerSamePackageAndSameSigner() {
        assertNull(ApkUpdatePolicy.rejection(current,
            ApkUpdateMetadata("io.github.airplaypip", 7, setOf("current-cert"))))
    }

    @Test fun rejectsDifferentPackage() {
        assertTrue(ApkUpdatePolicy.rejection(current,
            ApkUpdateMetadata("evil.example.app", 7, setOf("current-cert")))!!.contains("Package"))
    }

    @Test fun rejectsSameVersionAndDowngrade() {
        listOf(6L, 5L).forEach { version ->
            assertNotNull(ApkUpdatePolicy.rejection(current,
                ApkUpdateMetadata("io.github.airplaypip", version, setOf("current-cert"))))
        }
    }

    @Test fun rejectsDifferentOrMissingSigner() {
        assertTrue(ApkUpdatePolicy.rejection(current,
            ApkUpdateMetadata("io.github.airplaypip", 7, setOf("other-cert")))!!.contains("certificate"))
        assertTrue(ApkUpdatePolicy.rejection(current,
            ApkUpdateMetadata("io.github.airplaypip", 7, emptySet()) )!!.contains("unavailable"))
    }

    @Test fun allCurrentSignersMustMatch() {
        val multiSigner = current.copy(signerDigests = setOf("current-cert", "second"))
        assertNotNull(ApkUpdatePolicy.rejection(multiSigner,
            ApkUpdateMetadata("io.github.airplaypip", 7, setOf("current-cert"))))
    }
}
