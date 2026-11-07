package com.vayunmathur.globalesim.data.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** EsimApi URL building, DTO wire shapes (snake_case), and offline failure behaviour. */
class EsimApiDtoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun urlJoinsBaseAndPath() {
        assertEquals("https://api.maproj.org/esim/v1/rates", EsimApi.url("/esim/v1/rates"))
        assertEquals("https://api.maproj.org", EsimApi.BASE_URL)
    }

    @Test fun rateDtoWireShape() {
        val parsed = json.decodeFromString(
            RateDto.serializer(),
            """{"country_code":"PT","country_name":"Portugal","price_per_gb_usd_cents":450,
                "networks":[{"operator":"MEO","price_per_gb_usd_cents":450},
                            {"operator":"NOS","price_per_gb_usd_cents":500}],"unknown_field":1}""")
        assertEquals("PT", parsed.countryCode)
        assertEquals(450L, parsed.pricePerGbUsdCents)
        assertEquals(listOf("MEO", "NOS"), parsed.networks.map { it.operator })
        val bare = json.decodeFromString(
            RateDto.serializer(),
            """{"country_code":"FR","country_name":"France","price_per_gb_usd_cents":300}""")
        assertEquals(emptyList(), bare.networks)
        val encoded = json.encodeToString(RateDto.serializer(), parsed)
        assertTrue(encoded.contains("\"country_code\":\"PT\""), encoded)
        assertTrue(encoded.contains("\"price_per_gb_usd_cents\":450"), encoded)
    }

    @Test fun statusAndProfileDefaults() {
        val empty = json.decodeFromString(StatusResponse.serializer(), "{}")
        assertEquals(null, empty.profile)
        assertEquals(0L, empty.pendingCreditCents)
        assertEquals(false, empty.balanceStale)
        val full = json.decodeFromString(
            StatusResponse.serializer(),
            """{"profile":{"iccid":"8901","status":"active","wallet_usd_cents":1234},
                "pending_invoice_url":"http://pay.example/i","pending_credit_cents":500,
                "balance_stale":true}""")
        assertEquals("8901", full.profile!!.iccid)
        assertEquals("", full.profile!!.activationCode)
        assertEquals(1234L, full.profile!!.walletUsdCents)
        assertEquals(500L, full.pendingCreditCents)
        assertTrue(full.balanceStale)
    }

    @Test fun requestAndResponseShapes() {
        assertTrue(json.encodeToString(AccountRequest.serializer(), AccountRequest("acc-1"))
            .contains("\"account_id\":\"acc-1\""))
        assertTrue(json.encodeToString(
            TransferInRequest.serializer(), TransferInRequest("old", "new"))
            .contains("\"old_account_id\":\"old\""))
        assertTrue(json.encodeToString(
            StripeIntentRequest.serializer(), StripeIntentRequest("a", 1000L))
            .contains("\"amount_cents\":1000"))
        assertEquals("pk_1", json.decodeFromString(StripeKeyResponse.serializer(), """{"publishable_key":"pk_1"}""").publishableKey)
        assertEquals("", json.decodeFromString(StripeKeyResponse.serializer(), "{}").publishableKey)
        assertEquals("cs_1", json.decodeFromString(StripeIntentResponse.serializer(), """{"client_secret":"cs_1"}""").clientSecret)
        val invoice = json.decodeFromString(
            NowPayInvoiceResponse.serializer(),
            """{"invoice_url":"https://pay.example","invoice_id":"inv1","bonus_usd_cents":25,"amount_cents":500,"existing":true}""")
        assertTrue(invoice.existing)
        assertEquals(25L, invoice.bonusUsdCents)
        assertEquals(false, json.decodeFromString(OkResponse.serializer(), "{}").ok)
        assertEquals(true, json.decodeFromString(OkResponse.serializer(), """{"ok":true}""").ok)
        assertEquals(0, json.decodeFromString(RatesResponse.serializer(), "{}").rates.size)
    }

    @Test fun endpointsFailOffline() = runTest {
        // The devserver has no route to api.maproj.org: every call fails as an
        // IOException (never a successful fake), which ViewModels map to the
        // "No connection" message.
        assertTrue(runCatching { EsimApi.rates() }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { EsimApi.status("acc") }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { EsimApi.stripeKey() }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { EsimApi.stripeIntent("acc", 500L) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { EsimApi.nowPayInvoice("acc", 500L) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { EsimApi.nowPayCancel("acc") }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { EsimApi.claim("sim", "acc") }.exceptionOrNull() is java.io.IOException)
    }
}
