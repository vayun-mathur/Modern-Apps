package com.vayunmathur.youpipe.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubscriptionBackupTest {

    @Test
    fun `plain subscription array decodes`() {
        val json = """[{"id":3,"name":"Some Channel","channelID":"UC123","avatarURL":"https://x/y.jpg"}]"""
        val subs = decodeSubscriptionBackup(json)
        assertEquals(1, subs!!.size)
        assertEquals("Some Channel", subs[0].name)
        assertEquals("UC123", subs[0].channelID)
    }

    @Test
    fun `salted envelope returns null instead of throwing`() {
        // #565: encrypted/salted envelope written by another client.
        val json = """{"version":1,"salt":"fOK43kW1Xabc==","data":"..."}"""
        assertNull(decodeSubscriptionBackup(json))
    }

    @Test
    fun `empty object returns null instead of throwing`() {
        assertNull(decodeSubscriptionBackup("""{}"""))
    }
}
