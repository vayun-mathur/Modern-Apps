package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexVariables
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Asserts a representative set of `xwa2_*` `variables` builders match the w2.md §8 JSON shapes.
 * Pure — the builders use java.util.Base64, so no Android runtime is required.
 */
class WhatsAppMexOpsVariablesTest {

    @Test
    fun groupQueryByIdVariables_usesGroupInputArg() {
        assertEquals(
            """{"group_input":{"group_jid":"123@g.us","query_context":"INTERACTIVE"}}""",
            WhatsAppMexVariables.buildGroupQueryByIdVariables("123@g.us", "INTERACTIVE"),
        )
    }

    @Test
    fun usernameSetVariables_omitsNullPinAndSession() {
        assertEquals(
            """{"username":"johndoe","source":"USER_INPUT","pin":"ABC123"}""",
            WhatsAppMexVariables.buildUsernameSetVariables("johndoe", "ABC123", "USER_INPUT", null),
        )
    }

    @Test
    fun contactDiscoveryVariables_matchesSection83Shape() {
        assertEquals(
            """{"input":{"contacts":[{"phone":{"raw_pn":"+15551234567"}}],"context":"SEARCH"}}""",
            WhatsAppMexVariables.buildContactDiscoveryVariables(listOf("+15551234567"), "SEARCH"),
        )
    }

    @Test
    fun setMessagingKeysVariables_matchesSection82Shape() {
        val identity = ByteArray(32) { 1 }
        val skeyValue = ByteArray(32) { 2 }
        val skeySignature = ByteArray(64) { 3 }
        val prekeyValue = ByteArray(32) { 4 }

        val enc = Base64.getEncoder()
        val expectedType = enc.encodeToString(byteArrayOf(0x05))
        val expectedIdentity = enc.encodeToString(identity)
        val expectedSkeyId = enc.encodeToString(byteArrayOf(0, 0, 1))
        val expectedSkeyValue = enc.encodeToString(skeyValue)
        val expectedSkeySig = enc.encodeToString(skeySignature)
        val expectedPrekeyId = enc.encodeToString(byteArrayOf(0, 0, 7))
        val expectedPrekeyValue = enc.encodeToString(prekeyValue)

        val out = WhatsAppMexVariables.buildSetMessagingKeysVariables(
            identity = identity,
            skeyId = 1,
            skeyValue = skeyValue,
            skeySignature = skeySignature,
            prekeys = listOf(7 to prekeyValue),
        )

        assertEquals(
            """{"input":{"type":"$expectedType","identity":"$expectedIdentity",""" +
                """"skey":{"id":"$expectedSkeyId","value":"$expectedSkeyValue","signature":"$expectedSkeySig"},""" +
                """"prekeys":[{"id":"$expectedPrekeyId","value":"$expectedPrekeyValue"}]}}""",
            out,
        )
    }
}
