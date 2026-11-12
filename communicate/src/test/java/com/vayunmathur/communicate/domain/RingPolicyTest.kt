package com.vayunmathur.communicate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RingPolicyTest {
    private fun conditions(
        ringerMode: RingerMode = RingerMode.Normal,
        ringVolumeAudible: Boolean = true,
        vibrateWhenRinging: Boolean = true,
        callerAllowedByDnd: Boolean = true,
        otherCallLive: Boolean = false,
    ) = RingConditions(ringerMode, ringVolumeAudible, vibrateWhenRinging, callerAllowedByDnd, otherCallLive)

    @Test
    fun `normal mode rings and vibrates when vibrate for calls is on`() {
        assertEquals(
            RingPlan(ringtone = true, vibrate = true, callWaitingTone = false),
            ringPlan(conditions()),
        )
    }

    @Test
    fun `normal mode rings without vibration when vibrate for calls is off`() {
        assertEquals(
            RingPlan(ringtone = true, vibrate = false, callWaitingTone = false),
            ringPlan(conditions(vibrateWhenRinging = false)),
        )
    }

    @Test
    fun `normal mode at zero ring volume only vibrates per the setting`() {
        assertEquals(RingPlan.VibrateOnly, ringPlan(conditions(ringVolumeAudible = false)))
        assertEquals(RingPlan.Silent, ringPlan(conditions(ringVolumeAudible = false, vibrateWhenRinging = false)))
    }

    @Test
    fun `vibrate mode vibrates only, whatever the vibrate-for-calls setting`() {
        assertEquals(RingPlan.VibrateOnly, ringPlan(conditions(ringerMode = RingerMode.Vibrate)))
        assertEquals(
            RingPlan.VibrateOnly,
            ringPlan(conditions(ringerMode = RingerMode.Vibrate, vibrateWhenRinging = false)),
        )
    }

    @Test
    fun `silent mode plays nothing and does not vibrate`() {
        val plan = ringPlan(conditions(ringerMode = RingerMode.Silent))
        assertEquals(RingPlan.Silent, plan)
        assertTrue(plan.isSilent)
    }

    @Test
    fun `a caller do not disturb filters out gets nothing in any mode`() {
        RingerMode.entries.forEach { mode ->
            assertEquals(RingPlan.Silent, ringPlan(conditions(ringerMode = mode, callerAllowedByDnd = false)))
        }
        assertEquals(RingPlan.Silent, ringPlan(conditions(callerAllowedByDnd = false, otherCallLive = true)))
    }

    @Test
    fun `call waiting plays only the call-waiting tone`() {
        assertEquals(RingPlan.CallWaiting, ringPlan(conditions(otherCallLive = true)))
        assertFalse(RingPlan.CallWaiting.isSilent)
    }

    @Test
    fun `call waiting tone ignores the ringer switch because it is in-call audio`() {
        assertEquals(
            RingPlan.CallWaiting,
            ringPlan(conditions(ringerMode = RingerMode.Silent, otherCallLive = true)),
        )
        assertEquals(
            RingPlan.CallWaiting,
            ringPlan(conditions(ringerMode = RingerMode.Vibrate, otherCallLive = true)),
        )
    }

    private val priorityAnyone = DndPolicy(DndMode.PriorityOnly, callsAllowed = true, callers = DndCallers.Anyone)

    @Test
    fun `dnd off lets everyone through`() {
        val off = DndPolicy(DndMode.Off, callsAllowed = false, callers = DndCallers.None)
        assertTrue(dndAllowsCall(off, isContact = false, isStarred = false))
    }

    @Test
    fun `alarms-only and total silence block even starred contacts`() {
        listOf(DndMode.AlarmsOnly, DndMode.TotalSilence).forEach { mode ->
            val policy = priorityAnyone.copy(mode = mode)
            assertFalse(dndAllowsCall(policy, isContact = true, isStarred = true))
        }
    }

    @Test
    fun `priority mode without calls blocks everyone`() {
        val policy = priorityAnyone.copy(callsAllowed = false)
        assertFalse(dndAllowsCall(policy, isContact = true, isStarred = true))
    }

    @Test
    fun `priority mode applies the allowed callers`() {
        assertTrue(dndAllowsCall(priorityAnyone, isContact = false, isStarred = false))

        val contacts = priorityAnyone.copy(callers = DndCallers.Contacts)
        assertTrue(dndAllowsCall(contacts, isContact = true, isStarred = false))
        assertFalse(dndAllowsCall(contacts, isContact = false, isStarred = false))

        val starred = priorityAnyone.copy(callers = DndCallers.Starred)
        assertTrue(dndAllowsCall(starred, isContact = true, isStarred = true))
        assertFalse(dndAllowsCall(starred, isContact = true, isStarred = false))

        val none = priorityAnyone.copy(callers = DndCallers.None)
        assertFalse(dndAllowsCall(none, isContact = true, isStarred = true))
    }

    @Test
    fun `dialable numbers pass through with formatting stripped`() {
        assertEquals("+15551234567", dialableNumberOrNull("+15551234567"))
        assertEquals("5551234567", dialableNumberOrNull("(555) 123-4567"))
        assertEquals("15551234567", dialableNumberOrNull("15551234567@s.whatsapp.net"))
        assertEquals("15551234567", dialableNumberOrNull("15551234567:12@s.whatsapp.net"))
    }

    @Test
    fun `peers without a phone number have no dialable number`() {
        assertNull(dialableNumberOrNull("123456789012345@lid"))
        assertNull(dialableNumberOrNull("3f2c1d0e-8b7a-4c6d-9e5f-1a2b3c4d5e6f"))
        assertNull(dialableNumberOrNull(""))
        assertNull(dialableNumberOrNull("Anonymous"))
    }
}
