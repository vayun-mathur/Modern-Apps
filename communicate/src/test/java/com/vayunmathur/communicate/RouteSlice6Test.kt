package com.vayunmathur.communicate

import com.vayunmathur.communicate.data.CommunicateLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the [Route] navigation keys owned by slice 6/6 (root package files M-Z).
 *
 * The `Conversation` defaults are load-bearing: older persisted back-stack entries may lack the
 * newer fields, so decoding must fill the documented defaults rather than fail. The JSON
 * round-trips below pin that compatibility, plus the equality semantics the tab re-tap logic
 * (`backStack.last() is Route.Conversation` / `setLast`) relies on.
 */
class RouteSlice6Test {

    @Test
    fun conversationDefaultsMatchSimThread() {
        val route = Route.Conversation(threadId = 7L, address = "+15551234567")
        assertEquals(CommunicateLine.Sim, route.line)
        assertNull(route.remoteId)
        assertNull(route.subscriptionId)
        assertFalse(route.isGroup)
        assertEquals(emptyList(), route.participants)
        assertNull(route.groupTitle)
    }

    @Test
    fun conversationEqualityCoversEveryField() {
        val base = Route.Conversation(threadId = 7L, address = "+15551234567")
        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(threadId = 8L))
        assertNotEquals(base, base.copy(address = "+15559876543"))
        assertNotEquals(base, base.copy(line = CommunicateLine.WhatsApp))
        assertNotEquals(base, base.copy(isGroup = true))
        assertNotEquals(base, base.copy(participants = listOf("+15550001111")))
        assertNotEquals(base, base.copy(groupTitle = "Crew"))
    }

    @Test
    fun conversationCopyPreservesUnchangedFields() {
        val original = Route.Conversation(
            threadId = 7L,
            address = "+15551234567",
            line = CommunicateLine.Signal,
            remoteId = "aci-123",
            subscriptionId = 2,
            isGroup = true,
            participants = listOf("+15550001111"),
            groupTitle = "Crew",
        )
        val retargeted = original.copy(threadId = 9L)
        assertEquals(9L, retargeted.threadId)
        assertEquals(original.address, retargeted.address)
        assertEquals(original.line, retargeted.line)
        assertEquals(original.participants, retargeted.participants)
        assertEquals(original.groupTitle, retargeted.groupTitle)
    }

    @Test
    fun objectRoutesAreSingletons() {
        assertEquals(Route.Main, Route.Main)
        assertEquals(Route.Accounts, Route.Accounts)
        assertTrue(Route.Main != Route.Accounts)
        assertTrue(Route.GoogleVoiceSignIn != Route.WhatsAppRegistration)
        assertTrue(Route.WhatsAppBackupImport != Route.SignalRegistration)
        assertTrue(Route.RcsStatus != Route.Main)
    }

    @Test
    fun verifyAndGroupMembersRoutesCarryTheirPayload() {
        assertEquals("+15551234567", Route.RcsVerify("+15551234567").peerE164)
        assertEquals("conv-1", Route.RcsGroupMembers("conv-1").conversationId)
        assertNotEquals(Route.RcsVerify("a"), Route.RcsVerify("b"))
        assertNotEquals(Route.RcsGroupMembers("a"), Route.RcsGroupMembers("b"))
    }

    @Test
    fun everyRouteIsANavKey() {
        val routes: List<Route> = listOf(
            Route.Main,
            Route.Accounts,
            Route.GoogleVoiceSignIn,
            Route.WhatsAppRegistration,
            Route.WhatsAppBackupImport,
            Route.SignalRegistration,
            Route.RcsStatus,
            Route.RcsVerify("+15551234567"),
            Route.RcsGroupMembers("conv-1"),
            Route.Conversation(threadId = 1L, address = "x"),
        )
        assertEquals(10, routes.size)
        routes.forEach { assertIs<com.vayunmathur.library.util.NavKey>(it) }
    }

    @Test
    fun conversationExposesEightStablePropertiesInOrder() {
        // Guards the "older back-stack entries stay valid" contract structurally: adding,
        // removing or reordering properties here breaks persisted navigation state.
        // Java parameter names are unavailable (no -java-parameters); declared-field
        // order carries the stable declaration order instead.
        assertEquals(
            listOf(
                "threadId",
                "address",
                "line",
                "remoteId",
                "subscriptionId",
                "isGroup",
                "participants",
                "groupTitle",
            ),
            Route.Conversation::class.java.declaredFields
                .map { it.name }
                .filter { it != "Companion" && !it.contains("$") },
        )
    }

    @Test
    fun everyRouteSubtypeIsSerializableAnnotated() {
        // The back stack persists routes by kotlinx serialization; each subtype must keep
        // its @Serializable annotation or restoring navigation state breaks at runtime.
        val subtypes = Route::class.sealedSubclasses
        assertEquals(10, subtypes.size)
        subtypes.forEach { subtype ->
            assertTrue(
                subtype.annotations.any { it.annotationClass.qualifiedName == "kotlinx.serialization.Serializable" },
                "${subtype.simpleName} lost its @Serializable annotation",
            )
        }
    }

    @Test
    fun allTenRouteSubtypesArePresent() {
        val names = Route::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        assertEquals(
            setOf(
                "Main",
                "Accounts",
                "GoogleVoiceSignIn",
                "WhatsAppRegistration",
                "WhatsAppBackupImport",
                "SignalRegistration",
                "RcsStatus",
                "RcsVerify",
                "RcsGroupMembers",
                "Conversation",
            ),
            names,
        )
    }
}
