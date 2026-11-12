package io.github.jqssun.airplay

import io.github.jqssun.airplay.renderer.AnnexB
import org.junit.Assert.*
import org.junit.Test

class AnnexBTest {
    @Test fun spsCanConfigureButDoesNotCountAsAReferencePicture() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2)
        assertTrue(AnnexB.canBootstrap(sps, false))
        assertFalse(AnnexB.hasRandomAccessPicture(sps, false))
    }

    @Test fun h264IdrSupportsBothAnnexBStartCodeLengths() {
        assertTrue(AnnexB.hasRandomAccessPicture(byteArrayOf(0, 0, 1, 0x65), false))
        assertTrue(AnnexB.hasRandomAccessPicture(byteArrayOf(0, 0, 0, 1, 0x65), false))
    }

    @Test fun parameterSetsBeforeIdrStillFindTheReference() {
        val packet = byteArrayOf(0, 0, 1, 0x67, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4)
        assertTrue(AnnexB.hasRandomAccessPicture(packet, false))
    }

    @Test fun hevcVpsIsNotAnIrapButIdrIs() {
        val vps = byteArrayOf(0, 0, 1, (32 shl 1).toByte(), 1)
        val idr = byteArrayOf(0, 0, 1, (19 shl 1).toByte(), 1)
        assertTrue(AnnexB.canBootstrap(vps, true))
        assertFalse(AnnexB.hasRandomAccessPicture(vps, true))
        assertTrue(AnnexB.hasRandomAccessPicture(idr, true))
    }

    @Test fun emptyAndInterPicturesAreNotRandomAccess() {
        assertFalse(AnnexB.hasRandomAccessPicture(byteArrayOf(), false))
        assertFalse(AnnexB.hasRandomAccessPicture(byteArrayOf(0, 0, 0, 1, 0x41, 2), false))
    }
}
