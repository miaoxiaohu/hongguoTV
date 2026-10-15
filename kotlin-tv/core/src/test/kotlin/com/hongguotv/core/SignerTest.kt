package com.hongguotv.core
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
class SignerTest {
    // signing.json was captured with the device pair that VendorConstants used to hardcode, so the
    // oracle has to be replayed against that exact identity. Production no longer sends it -
    // see [VendorConstants.newDevice] - which is why this is passed in rather than defaulted.
    private val oracleDevice = VendorConstants.device +
        mapOf("iid" to "1905892595382586", "device_id" to "1905892595378490")

    @Test fun matchesPinnedJavaScriptOracleAcrossTimestampVariants() {
        val rows = JSONArray(javaClass.getResource("/signing.json")!!.readText())
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i); val ts=row.getInt("ts"); val url=row.getString("url"); val query=url.substringAfter('?')
            val body=if(row.isNull("body")) null else row.getString("body").toByteArray()
            var seed=17L
            val signer=Signer({ts.toLong()*1000}) { seed=(seed*1664525+1013904223) and 0xffffffffL; seed/4294967296.0 }
            assertEquals("branch $i",0,signer.branch(query,body,ts))
            assertEquals("hash $i",row.getString("hash"),signer.hashF13(sm3(query.toByteArray()),body?.let{digest("MD5",it)}?:ByteArray(16),ts).hex())
            assertEquals("gorgon $i",row.getString("gorgon"),signer.gorgon(query,body,ts,42131))
            assertEquals("helios $i",row.getString("helios"),signer.helios(ts))
            assertEquals("medusa $i",row.getString("medusa"),signer.medusa(url,body,ts,oracleDevice))
        }
    }

    /**
     * The pair that used to be hardcoded in VendorConstants is published in the drpys sources and
     * is now silently blocked (HTTP 200, zero-byte body). Identities have to be generated per
     * request - and reused ones stop working after a single call.
     */
    @Test fun eachRequestGetsItsOwnDeviceIdentity() {
        val ids = (1..8).map { VendorConstants.newDevice() }
        assertEquals(8, ids.map { it.getValue("device_id") }.toSet().size)
        assertEquals(8, ids.map { it.getValue("iid") }.toSet().size)
        for (device in ids) {
            assertTrue(device.getValue("device_id"), device.getValue("device_id").matches(Regex("[0-9]{16}")))
            assertTrue(device.getValue("iid"), device.getValue("iid").matches(Regex("[0-9]{16}")))
        }
        // the blocked pair must not survive anywhere in what ships
        assertFalse(ids.any { it.getValue("device_id") == "1905892595378490" })
        assertFalse(ids.any { it.getValue("iid") == "1905892595382586" })
    }

    /** The identity in the query string and the one inside x-medusa have to be the same. */
    @Test fun medusaEmbedsTheSameIdentityTheQueryCarries() {
        val device = VendorConstants.newDevice()
        val request = Signer({ 1788076800000L }) { 0.42 }.sign(
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/", device, "{}".toByteArray()
        )
        val fromQuery = Regex("[?&]device_id=([0-9]+)").find(request.url)!!.groupValues[1]
        val medusa = device.getValue("device_id").encodeToByteArray()
        assertEquals(16, medusa.size)
        // the query and the signed proto must agree, otherwise the server discards the response
        assertEquals(fromQuery, device.getValue("device_id"))
        assertFalse(request.headers.getValue("x-medusa").isEmpty())
    }
}
