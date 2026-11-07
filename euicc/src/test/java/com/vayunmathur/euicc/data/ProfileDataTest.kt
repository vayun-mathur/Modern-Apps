package com.vayunmathur.euicc.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `displayName prefers nickname then name then provider then iccid tail`() {
        assertEquals("Work", Profile(nickname = "Work", name = "Plan", serviceProvider = "Telco", iccidDisplay = "89010001234").displayName)
        assertEquals("Plan", Profile(name = "Plan", serviceProvider = "Telco", iccidDisplay = "89010001234").displayName)
        assertEquals("Telco", Profile(serviceProvider = "Telco", iccidDisplay = "89010001234").displayName)
        assertEquals("•••• 1234", Profile(iccidDisplay = "89010001234").displayName)
        assertEquals("", Profile().displayName)
        assertEquals("•••• abc", Profile(iccidDisplay = "abc").displayName)
    }

    @Test fun `displayName falls through blank strings`() {
        assertEquals("Plan", Profile(nickname = " ", name = "Plan").displayName)
        assertEquals("Telco", Profile(nickname = "", name = "  ", serviceProvider = "Telco").displayName)
    }

    @Test fun `isEnabled only for enabled state`() {
        assertTrue(Profile(state = "enabled").isEnabled)
        assertFalse(Profile(state = "disabled").isEnabled)
        assertFalse(Profile(state = "unknown").isEnabled)
        assertFalse(Profile().isEnabled)
    }

    @Test fun `profile decodes native json including class serial name`() {
        val p = json.decodeFromString<Profile>(
            """{"iccid":"98AB","iccidDisplay":"8901 2","isdpAid":"A000","state":"enabled","class":"operational","nickname":"Work","serviceProvider":"Telco","name":"Prepaid","extra":1}""",
        )
        assertEquals("98AB", p.iccid)
        assertEquals("8901 2", p.iccidDisplay)
        assertEquals("A000", p.isdpAid)
        assertEquals("operational", p.profileClass)
        assertTrue(p.isEnabled)
        assertEquals("Work", p.displayName)
    }

    @Test fun `profile defaults`() {
        val p = json.decodeFromString<Profile>("{}")
        assertEquals(Profile(), p)
        assertEquals("unknown", p.state)
        assertEquals("unknown", p.profileClass)
    }

    @Test fun `euicc info decodes and defaults`() {
        val info = json.decodeFromString<EuiccInfo>(
            """{"svn":"2.2.0","ciPkIdListForVerification":["aa","bb"],"ciPkIdListForSigning":["cc"]}""",
        )
        assertEquals("2.2.0", info.svn)
        assertEquals(listOf("aa", "bb"), info.ciPkIdListForVerification)
        assertEquals(listOf("cc"), info.ciPkIdListForSigning)
        assertEquals(EuiccInfo(), json.decodeFromString<EuiccInfo>("{}"))
    }

    @Test fun `notification decodes and defaults`() {
        val n = json.decodeFromString<Notification>(
            """{"seqNumber":7,"operation":"install","address":"smdp.example.com","iccidDisplay":"8901"}""",
        )
        assertEquals(7, n.seqNumber)
        assertEquals("install", n.operation)
        assertEquals("smdp.example.com", n.address)
        val d = json.decodeFromString<Notification>("{}")
        assertEquals(-1, d.seqNumber)
        assertEquals("unknown", d.operation)
        assertEquals("", d.address)
    }

    @Test fun `profile list decodes`() {
        val list = json.decodeFromString<List<Profile>>("""[{"iccid":"1"},{"iccid":"2","state":"enabled"}]""")
        assertEquals(2, list.size)
        assertEquals("2", list[1].iccid)
        assertTrue(list[1].isEnabled)
    }
}
