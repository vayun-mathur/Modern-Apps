package com.vayunmathur.fooddelivery.api

import android.app.Application
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.CheckoutCartItem
import com.vayunmathur.fooddelivery.data.CheckoutRequest
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.FeedbackRequest
import com.vayunmathur.library.network.SimpleResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

/** Wave-3: remaining Bites* endpoints, failure branches, and refresh-token rules. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodWave3ApiTest {

    data class Recorded(val url: String, val method: String, val headers: Map<String, String>, val body: String?)

    private val recorded = mutableListOf<Recorded>()
    private var handler: (Recorded) -> SimpleResponse = { resp(404, "{}") }
    private val realTransport = BitesCore.transport

    private fun resp(status: Int, body: String, url: String = "") =
        SimpleResponse(status, "", body, emptyMap(), url)

    private fun on(match: String, status: Int, body: String) {
        val previous = handler
        handler = { req -> if (req.url.contains(match)) resp(status, body, req.url) else previous(req) }
    }

    @Before fun installFake() {
        recorded.clear()
        handler = { resp(404, "{}") }
        BitesCore.transport = { url, method, headers, body ->
            val req = Recorded(url, method, headers, body)
            recorded += req
            handler(req)
        }
        BitesAuth.onTokenUpdated = null
        BitesAuth.onTokenCleared = null
        BitesAuth.clearToken()
    }

    @After fun restore() {
        BitesCore.transport = realTransport
        BitesAuth.clearToken()
    }

    private fun lastRequest(match: String): Recorded = recorded.last { it.url.contains(match) }

    @Test fun merchantByNameReportingAndNoParamsUrl() { runBlocking {
        on("/merchants/storefront/", 200, """{"data":{"id":8,"name":"Alias Place"}}""")
        on("/merchants/reporting/", 200, "csv-report")
        assertEquals("Alias Place", BitesMerchants.getMerchantByName("alias-place")!!.name)
        assertEquals("csv-report", BitesMerchants.getMerchantReporting(8))
        BitesCore.transport = { url, _, _, _ -> resp(404, "{}", url) }
        assertNull(BitesMerchants.getMerchantReporting(8))
        BitesCore.transport = { _, _, _, _ -> throw java.io.IOException("down") }
        assertNull(BitesMerchants.getMerchantReporting(8))
        assertNull(BitesMerchants.getMerchantByName("x"))
        // No coordinates: the merchants URL carries no query string at all.
        BitesCore.transport = { url, method, headers, body ->
            recorded += Recorded(url, method, headers, body)
            resp(200, """{"data":{"merchants":[]}}""", url)
        }
        assertTrue(BitesMerchants.getMerchants().isEmpty())
        assertFalse(lastRequest("/merchants/all/stores").url.contains("?"))
        assertTrue(lastRequest("/merchants/all/stores").url.contains("/merchants/all/stores"))
    } }

    @Test fun dealsListingVariantsAndFailures() { runBlocking {
        // Generic registrations first: later `on` handlers are checked first.
        on("/deals", 200, """{"data":[{"id":4,"title":"Every"}]}""")
        on("/deals/merchant/7", 200, """{"data":[{"id":3,"title":"All"}]}""")
        on("/deals/merchant/7/active", 200, """{"data":[{"id":2,"title":"Active"}]}""")
        on("/deals/active", 200, """{"data":[{"id":1,"title":"Near"}]}""")
        val near = BitesDeals.getDeals(lat = 1.5, lng = 2.5)
        assertEquals("Near", near.single().title)
        assertTrue(lastRequest("/deals/active").url.contains("lat=1.5"))
        assertTrue(lastRequest("/deals/active").url.contains("lng=2.5"))
        assertTrue(lastRequest("/deals/active").url.contains("radius=8"))
        assertEquals("Active", BitesDeals.getActiveDealsByMerchant(7).single().title)
        assertEquals("All", BitesDeals.getDealsByMerchant(7).single().title)
        assertEquals("Every", BitesDeals.getAllDeals().single().title)
        BitesCore.transport = { _, _, _, _ -> throw java.io.IOException("down") }
        assertTrue(BitesDeals.getDeals().isEmpty())
        assertNull(BitesDeals.getDealProgress(1))
        BitesCore.transport = { url, _, _, _ -> resp(500, "boom", url) }
        assertTrue(BitesDeals.getAllDeals().isEmpty())
        assertNull(BitesDeals.getDealById(4))
    } }

    @Test fun customerReadAndMutationFailureBranches() { runBlocking {
        on("/customers/getReferrals", 200, """{"data":[{"id":1,"code":"REF1","referredById":5}]}""")
        on("/orders/me/savings", 500, "boom")
        assertEquals("REF1", BitesCustomers.getReferrals().single().code)
        assertNull(BitesCustomers.getCustomerSavings())
        // Every one-shot mutation maps a server failure to false, not an exception.
        BitesCore.transport = { url, _, _, _ -> resp(500, "boom", url) }
        assertNull(BitesCustomers.createOrUpdateCustomer(Customer(id = 1)))
        assertFalse(BitesCustomers.deleteCustomer())
        assertFalse(BitesCustomers.registerPushNotification("t", "u"))
        assertFalse(BitesCustomers.createReferral("u", 1))
        assertFalse(BitesCustomers.sendEmailVerification("a@b.c"))
        assertFalse(BitesCustomers.verifyEmailToken("c", "t"))
        assertFalse(BitesCustomers.createCustomerMerchantLoyalty("CODE", 1))
        assertFalse(BitesCustomers.deleteCustomerMerchantLoyalty(1))
        // And a transport failure takes the same path.
        BitesCore.transport = { _, _, _, _ -> throw java.io.IOException("down") }
        assertFalse(BitesCustomers.deleteCustomer())
        assertFalse(BitesCustomers.registerPushNotification("t", "u"))
        assertFalse(BitesCustomers.createReferral("u", 1))
        assertFalse(BitesCustomers.sendEmailVerification("a@b.c"))
        assertFalse(BitesCustomers.verifyEmailToken("c", "t"))
        assertFalse(BitesCustomers.createCustomerMerchantLoyalty("CODE", 1))
        assertFalse(BitesCustomers.deleteCustomerMerchantLoyalty(1))
        assertNull(BitesCustomers.getCustomer())
        assertNull(BitesCustomers.createOrUpdateCustomer(Customer(id = 1)))
        assertTrue(BitesCustomers.getReferrals().isEmpty())
    } }

    @Test fun orderReadAndMutationFailureBranches() { runBlocking {
        on("/orders/9/feedback", 200, """{"data":{"id":3,"orderId":9,"rating":4,"tips":100}}""")
        on("/merchants/7/checkout", 200, """{"data":{"clientSecret":"secret-1","order":{"id":11,"foodTotal":1000},"serviceable":{"ok":true}}}""")
        val feedback = BitesOrders.getFeedback("9")
        assertEquals(4, feedback!!.rating)
        val checkout = BitesOrders.checkout(
            7,
            CheckoutRequest(
                cartItems = listOf(CheckoutCartItem(itemId = 1, quantity = 1)),
                tips = 300, promoCode = "SAVE",
            ),
        )
        assertEquals("secret-1", checkout!!.clientSecret)
        assertEquals(11, checkout.order!!.id)
        assertTrue(checkout.isServiceable)
        assertTrue(lastRequest("/checkout").body!!.contains("SAVE"))
        // Failures degrade to null/false/empty.
        BitesCore.transport = { url, _, _, _ -> resp(500, "boom", url) }
        assertNull(BitesOrders.getOrderByEmail("tok"))
        assertNull(BitesOrders.getOrderRewards("u9"))
        assertFalse(BitesOrders.pickUpOrder("u9"))
        assertFalse(BitesOrders.submitFeedback("u9", FeedbackRequest(orderId = 9, rating = 5)))
        assertNull(BitesOrders.checkout(7, CheckoutRequest(cartItems = emptyList())))
        assertNull(BitesOrders.getFeedback("9"))
        BitesCore.transport = { _, _, _, _ -> throw java.io.IOException("down") }
        assertNull(BitesOrders.getOrderByEmail("tok"))
        assertNull(BitesOrders.getOrderRewards("u9"))
        assertFalse(BitesOrders.pickUpOrder("u9"))
        assertFalse(BitesOrders.submitFeedback("u9", FeedbackRequest(orderId = 9)))
        assertNull(BitesOrders.checkout(7, CheckoutRequest(cartItems = emptyList())))
        assertTrue(BitesOrders.getOrders().isEmpty())
        BitesCore.transport = { url, _, _, _ -> resp(200, "not json", url) }
        assertTrue(BitesOrders.getOrders().isEmpty())
        assertNull(BitesRewards.getPlatformSavings())
    } }

    @Test fun rewardsFailureBranches() { runBlocking {
        BitesCore.transport = { _, _, _, _ -> throw java.io.IOException("down") }
        assertTrue(BitesRewards.getRewards().isEmpty())
        assertTrue(BitesRewards.getCustomerMerchantRewards().isEmpty())
        assertNull(BitesRewards.getPlatformSavings())
        BitesCore.transport = { url, _, _, _ -> resp(500, "boom", url) }
        assertTrue(BitesRewards.getRewards().isEmpty())
    } }

    @Test fun coreUnwrapHeadersAndTokenStorage() { runBlocking {
        // unwrap falls back to the root object when there is no data envelope.
        val direct = BitesCore.unwrap("""{"id":5,"firstName":"Solo"}""", Customer.serializer())
        assertEquals("Solo", direct!!.firstName)
        val enveloped = BitesCore.unwrap("""{"message":"ok","data":{"id":6}}""", Customer.serializer())
        assertEquals(6, enveloped!!.id)
        // No stored token: headers carry no Authorization entry.
        val anon = BitesCore.authHeaders()
        assertFalse(anon.containsKey("Authorization"))
        assertEquals("application/json", anon["Content-Type"])
        // A fresh (unexpired) token is returned without any refresh traffic.
        BitesAuth.setToken(AuthToken("fresh-acc", "ref", "Bearer", 3600))
        recorded.clear()
        assertEquals("fresh-acc", BitesCore.getAccessToken())
        assertTrue(recorded.isEmpty())
        assertEquals("Bearer fresh-acc", BitesCore.authHeaders()["Authorization"])
        // encodeTokenForStorage embeds the absolute expiry alongside the token fields.
        val stored = BitesCore.encodeTokenForStorage(AuthToken("acc", "ref", "Bearer", 60), 123456L)
        assertTrue(stored.contains("\"access_token\":\"acc\""), stored)
        assertTrue(stored.contains("\"expires_at_ms\":123456"), stored)
        // Restoring a payload without the expiry key leaves the expiry unknown (0).
        BitesAuth.restoreToken("""{"access_token":"zz","refresh_token":"rr"}""")
        assertEquals("zz", BitesCore.storedToken!!.accessToken)
        assertEquals(0L, BitesCore.expiresAtMs)
        // setToken(null) clears the in-memory token without firing the update callback.
        var updates = 0
        BitesAuth.onTokenUpdated = { updates++ }
        BitesAuth.setToken(null)
        assertFalse(BitesAuth.isLoggedIn())
        assertEquals(0, updates)
    } }

    @Test fun refreshRotationAndSignOutRules() { runBlocking {
        // A caller holding a stale token gets the already-rotated current token.
        BitesAuth.setToken(AuthToken("current-acc", "ref-cur", "Bearer", 3600))
        val rotated = BitesCore.refreshToken(AuthToken("stale-acc", "ref-old", "Bearer", 3600))
        assertEquals("current-acc", rotated!!.accessToken)
        // No refresh token at all: refresh signs the user out.
        BitesAuth.setToken(AuthToken("solo-acc", "", "Bearer", 3600))
        BitesCore.expiresAtMs = System.currentTimeMillis() + 3600_000
        assertNull(BitesCore.refreshToken(AuthToken("solo-acc", "", "Bearer", 3600)))
        assertFalse(BitesAuth.isLoggedIn())
        // Timeout (408) and rate-limit (429) rejections are transient: session kept.
        for (status in listOf(408, 429, 503)) {
            BitesAuth.setToken(AuthToken("old-acc", "ref-1", "Bearer", 3600))
            BitesCore.expiresAtMs = 1
            var dataCalls = 0
            BitesCore.transport = { url, _, _, _ ->
                when {
                    url.contains("/auth/token") -> resp(status, "{}", url)
                    else -> { dataCalls++; resp(401, "{}", url) }
                }
            }
            assertNull(BitesMerchants.getMerchantDetail(5))
            assertEquals(1, dataCalls, "status $status must not retry after transient refresh failure")
            assertTrue(BitesAuth.isLoggedIn(), "status $status must keep the session")
        }
        // A malformed refresh success body is a failure, not a sign-out.
        BitesAuth.setToken(AuthToken("old-acc", "ref-1", "Bearer", 3600))
        BitesCore.expiresAtMs = 1
        BitesCore.transport = { url, _, _, _ ->
            if (url.contains("/auth/token")) resp(200, "not json", url) else resp(401, "{}", url)
        }
        assertNull(BitesMerchants.getMerchantDetail(5))
        assertTrue(BitesAuth.isLoggedIn())
    } }

    @Test fun authPhoneStateIdVariantsAndFailures() { runBlocking {
        on("/auth/verify_phone", 200, """{"stateId":"camel-7"}""")
        assertEquals("camel-7", BitesAuth.verifyPhone("555 0100"))
        assertTrue(lastRequest("/auth/verify_phone").body!!.contains("+5550100"))
        // A non-object success body yields no state id.
        BitesCore.transport = { url, _, _, _ -> resp(200, """["x"]""", url) }
        assertNull(BitesAuth.verifyPhone("555"))
        BitesCore.transport = { _, _, _, _ -> throw java.io.IOException("down") }
        assertNull(BitesAuth.verifyPhone("555"))
        assertNull(BitesAuth.exchangeOtpCodeForToken("s", "123456"))
        // expiresIn=0 leaves a previously recorded expiry untouched.
        BitesAuth.setToken(AuthToken("acc", "ref", "Bearer", 3600))
        val expiry = BitesCore.expiresAtMs
        assertTrue(expiry > 0)
        BitesAuth.setToken(AuthToken("acc2", "ref2", "Bearer", 0))
        assertEquals(expiry, BitesCore.expiresAtMs)
        assertEquals("acc2", BitesCore.storedToken!!.accessToken)
    } }
}
