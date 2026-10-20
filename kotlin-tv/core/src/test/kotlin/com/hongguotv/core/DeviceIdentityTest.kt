package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/**
 * A signed request needs a device identity, and a request without one is refused where it can
 * still be named.
 *
 * The identity that used to be hardcoded here is published in the drpys sources, and the
 * server answers it with HTTP 200 and a zero-byte body instead of an error code. A request
 * that went out with an empty identity therefore never failed - it was answered with nothing,
 * and the caller had nothing to report.
 */
class DeviceIdentityTest {
    private val signer=Signer({ 1788076800000L }) { 0.42 }
    private val base="https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/"

    @Test fun `the default identity is refused instead of being signed and sent`() {
        // VendorConstants.device is the published pair with an empty device id.
        assertThrows(IllegalStateException::class.java) { signer.medusa("$base?a=1",null,1) }
        assertThrows(IllegalStateException::class.java) { signer.sign(base,VendorConstants.device) }
    }

    @Test fun `an identity that is missing or blank is refused`() {
        assertThrows(IllegalStateException::class.java) { signer.medusa("$base?a=1",null,1,mapOf("device_id" to "  ")) }
        assertThrows(IllegalStateException::class.java) { signer.medusa("$base?a=1",null,1,mapOf("device_id" to "1905892595378490")) }
        assertThrows(IllegalStateException::class.java) { signer.sign(base,mapOf("query" to "总裁")) }
        assertThrows(IllegalStateException::class.java) { signer.sign(base,VendorConstants.device + mapOf("device_id" to "1905892595378490","iid" to "")) }
    }

    @Test fun `a real identity still signs`() {
        val device=VendorConstants.newDevice()
        val request=signer.sign(base,device,"{}".toByteArray())
        assertTrue(request.headers.getValue("x-medusa").isNotEmpty())
        assertTrue(request.url.contains("device_id=${device.getValue("device_id")}"))
        // The same url signs again with the same identity - medusa only accepts a url whose
        // hash branch is the one it emits, which is what sign hands it.
        assertTrue(signer.medusa(request.url,"{}".toByteArray(),1788076800,device).isNotEmpty())
    }
}
