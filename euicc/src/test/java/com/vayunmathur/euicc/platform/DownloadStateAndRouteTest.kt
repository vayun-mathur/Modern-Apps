package com.vayunmathur.euicc.platform

import com.vayunmathur.euicc.Route
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadStateAndRouteTest {
    @Test fun `download state variants hold data`() {
        assertEquals(DownloadState.Idle, DownloadState.Idle)
        assertEquals(DownloadState.Preparing, DownloadState.Preparing)
        assertEquals(DownloadState.Confirm("Telco", "Plan"), DownloadState.Confirm("Telco", "Plan"))
        assertNotEquals(DownloadState.Confirm("Telco", "Plan"), DownloadState.Confirm("Other", "Plan"))
        assertEquals(DownloadState.Confirm(null, null), DownloadState.Confirm(null, null))
        val awaiting = DownloadState.AwaitingConfirmationCode("Telco", error = true)
        assertEquals("Telco", awaiting.carrier)
        assertTrue(awaiting.error)
        assertEquals(DownloadState.AwaitingConfirmationCode("Telco"), DownloadState.AwaitingConfirmationCode("Telco", false))
        assertEquals(0.5f, DownloadState.Installing(0.5f).progress)
        assertNull(DownloadState.Installing().progress)
        assertEquals("Telco", DownloadState.Complete("Telco").carrier)
        assertNull(DownloadState.Complete(null).carrier)
        assertEquals("boom", DownloadState.Failed("boom").message)
        assertNull(DownloadState.Failed(null).message)
    }

    @Test fun `route download defaults and equality`() {
        val r = Route.Download("LPA:1\$a.b\$X")
        assertEquals("LPA:1\$a.b\$X", r.activationCode)
        assertNull(r.imei)
        assertNull(r.confirmationCode)
        assertEquals(Route.Download("c", "imei1", "cc"), Route.Download("c", "imei1", "cc"))
        assertNotEquals(Route.Download("c"), Route.Download("c", imei = "1"))
        assertEquals(Route.Home, Route.Home)
        assertEquals(Route.DeviceInfo, Route.DeviceInfo)
        assertEquals(Route.ProfileDetail("abc"), Route.ProfileDetail("abc"))
        assertNotEquals(Route.ProfileDetail("abc"), Route.ProfileDetail("def"))
    }

    @Test fun `routes serialize`() {
        val json = Json
        assertTrue(json.encodeToString(Route.serializer(), Route.Home).contains("Home"))
        val decoded = json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.Download("code", "123", null)))
        assertEquals(Route.Download("code", "123", null), decoded)
        val detail = json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.ProfileDetail("iccid1")))
        assertEquals(Route.ProfileDetail("iccid1"), detail)
    }

    @Test fun `screen state defaults`() {
        val s = EuiccScreenState()
        assertTrue(s.loading)
        assertNull(s.error)
        assertNull(s.eid)
        assertNull(s.info)
        assertTrue(s.profiles.isEmpty())
        assertTrue(s.notifications.isEmpty())
        val copy = s.copy(loading = false, error = "x", eid = "890")
        assertEquals("x", copy.error)
        assertEquals("890", copy.eid)
    }
}
