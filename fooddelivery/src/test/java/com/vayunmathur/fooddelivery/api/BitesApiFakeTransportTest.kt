package com.vayunmathur.fooddelivery.api

import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.CheckoutAddress
import com.vayunmathur.fooddelivery.data.CheckoutCartItem
import com.vayunmathur.fooddelivery.data.CheckoutRequest
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.FeedbackRequest
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole Bites* API surface against a fake [BitesCore.transport]:
 * canned envelopes per URL, recorded requests for method/body/header
 * assertions, and the token-refresh retry ladder. The default transport
 * (NetworkClient) is restored after the class, so no test touches the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BitesApiFakeTransportTest {

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
        BitesAuth.onTokenUpdated = null; BitesAuth.onTokenCleared = null
        BitesAuth.clearToken()
    }

    @After fun restore() {
        BitesCore.transport = realTransport
        BitesAuth.clearToken()
    }

    private fun lastRequest(match: String): Recorded = recorded.last { it.url.contains(match) }

    @Test fun merchantsListDetailAndServiceability() { runBlocking {
        on("/merchants/all/stores", 200, """{"message":"ok","data":{"merchants":[{"id":1,"name":"A","latitude":1.0,"longitude":2.0},{"id":2,"name":"B"}]}}""")
        on("/merchants/7", 200, """{"data":{"id":7,"name":"Seven","categories":[{"id":1,"name":"Mains","itemIds":[10]}],"items":[{"id":10,"name":"Burger","price":999}]}}""")
        val merchants = BitesMerchants.getMerchants(37.5, -122.3)
        assertEquals(2, merchants.size); assertEquals("A", merchants[0].name)
        assertTrue(lastRequest("/merchants/all/stores").url.contains("lat=37.5"))
        val detail = BitesMerchants.getMerchantDetail(7)
        assertEquals("Seven", detail!!.name); assertEquals("Burger", detail.items[0].name)
        // serviceability: 200 -> true, 4xx -> false, 5xx -> null
        on("/check-serviceability", 200, "{}")
        assertEquals(true, BitesMerchants.checkServiceability(7, CheckoutAddress(addressStreet = "1 Main")))
        assertTrue(lastRequest("/check-serviceability").method == "POST")
        BitesCore.transport = { url, method, headers, body -> resp(422, "{}", url) }
        assertEquals(false, BitesMerchants.checkServiceability(7, CheckoutAddress()))
        BitesCore.transport = { url, method, headers, body -> resp(503, "{}", url) }
        assertNull(BitesMerchants.checkServiceability(7, CheckoutAddress()))
    } }

    @Test fun merchantsFailuresReturnEmpty() { runBlocking {
        on("/merchants/all/stores", 500, "boom")
        assertTrue(BitesMerchants.getMerchants().isEmpty())
        BitesCore.transport = { _, _, _, _ -> throw java.io.IOException("down") }
        assertTrue(BitesMerchants.getMerchants().isEmpty())
        assertNull(BitesMerchants.getMerchantDetail(1))
        assertTrue(BitesRewards.getRewards().isEmpty())
        assertTrue(BitesOrders.getOrders().isEmpty())
        assertNull(BitesCustomers.getCustomer())
    } }

    @Test fun dealsRewardsOrdersCustomersBasics() { runBlocking {
        on("/deals/active", 200, """{"data":[{"id":3,"title":"2for1","merchantId":7,"discountPercent":50.0}]}""")
        on("/deals/merchant/", 200, """{"data":[{"id":4,"title":"Merchant deal"}]}""")
        on("/deals/3/progress", 200, """{"data":{"ordersCount":2,"isComplete":false}}""")
        on("/rewards", 200, """{"data":[{"id":1,"title":"Free fries","pointsRequired":100}]}""")
        on("/orders/savings/platform", 200, """{"data":{"totalCustomerSavings":12345}}""")
        on("/orders/past/all", 200, """{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","deliveredAt":"2024-01-01T00:00:00Z"}]}""")
        on("/customers/me", 200, """{"data":{"id":5,"firstName":"Ann","lastName":"Lee","email":"a@b.c"}}""")
        on("/customers/me/rewards", 200, """{"data":[{"merchantId":7,"balance":250}]}""")
        on("/orders/me/savings", 200, """{"data":{"customerSavings":500,"orderCount":3}}""")

        assertEquals("2for1", BitesDeals.getDeals().single().title)
        assertTrue(lastRequest("/deals/active").url.contains("radius=8"))
        assertEquals(1, BitesDeals.getDealsByMerchant(7).size)
        assertEquals(2, BitesDeals.getDealProgress(3)!!.ordersCount)
        assertEquals(100, BitesRewards.getRewards().single().pointsRequired)
        assertEquals(2.5, BitesRewards.getCustomerMerchantRewards().single().balanceDollars)
        assertEquals(123.45, BitesRewards.getPlatformSavings()!!.customerSavingsDollars)
        val order = BitesOrders.getOrders().single()
        assertEquals(15.0, order.displayTotal); assertEquals(true, order.isDelivery); assertEquals(true, order.isDone)
        assertEquals("Ann Lee", BitesCustomers.getCustomer()!!.displayName)
        assertEquals(5.0, BitesCustomers.getCustomerSavings()!!.customerSavingsDollars)
    } }

    @Test fun customerMutationsAndOrderActions() { runBlocking {
        on("/customers/", 200, "{}") // catch-all for the one-shot customer actions below
        on("/customers/me", 200, """{"data":{"id":5,"firstName":"Ann"}}""")
        assertEquals("Ann", BitesCustomers.createOrUpdateCustomer(Customer(id = 5, firstName = "Ann"))!!.firstName)
        assertEquals("POST", lastRequest("/customers/me").method)
        assertEquals(true, BitesCustomers.deleteCustomer())
        assertEquals("DELETE", lastRequest("/customers/me").method)
        assertEquals(true, BitesCustomers.registerPushNotification("tok", "uuid-1"))
        assertTrue(lastRequest("/pushNotifications").body!!.contains("tok"))
        assertEquals(true, BitesCustomers.createReferral("uuid-1", 42))
        assertEquals(true, BitesCustomers.sendEmailVerification("a@b.c"))
        assertEquals(true, BitesCustomers.verifyEmailToken("cust", "tok"))
        assertEquals(true, BitesCustomers.createCustomerMerchantLoyalty("CODE", 7))
        assertEquals(true, BitesCustomers.deleteCustomerMerchantLoyalty(7))
        on("/orders/pickUpOrder/", 200, "{}")
        assertEquals(true, BitesOrders.pickUpOrder("u9"))
        on("/orders/email/", 200, """{"data":{"id":9,"uuid":"u9"}}""")
        assertEquals(9, BitesOrders.getOrderByEmail("tok123")!!.id)
        on("/rewards", 200, """{"data":{"rewardsAvailable":250}}""")
        assertEquals(2.5, BitesOrders.getOrderRewards("u9")!!.rewardsAvailableDollars)
        on("/orders/", 200, "{}")
        assertEquals(true, BitesOrders.submitFeedback("u9", FeedbackRequest(orderId = 9, rating = 5)))
        val checkout = BitesOrders.checkout(7, CheckoutRequest(cartItems = listOf(CheckoutCartItem(itemId = 10, quantity = 2)), tips = 100))
        assertEquals(null, checkout) // bare {} body unwraps to a response with defaults; data missing -> order null is valid
    } }

    @Test fun authPhoneOtpAndTokenStorage() { runBlocking {
        on("/auth/verify_phone", 200, """{"state_id":"state-42"}""")
        assertEquals("state-42", BitesAuth.verifyPhone("(555) 123-4567"))
        assertTrue(lastRequest("/auth/verify_phone").body!!.contains("+5551234567"))
        on("/auth/token", 200, """{"access_token":"acc","refresh_token":"ref","token_type":"Bearer","expires_in":3600}""")
        val token = BitesAuth.exchangeOtpCodeForToken("state-42", "123456")
        assertEquals("acc", token!!.accessToken)
        // Storage callbacks fire on set, clear, and restore.
        var persisted: String? = null; var cleared = false
        BitesAuth.onTokenUpdated = { persisted = it }; BitesAuth.onTokenCleared = { cleared = true }
        BitesAuth.setToken(token)
        assertTrue(BitesAuth.isLoggedIn()); assertTrue(persisted!!.contains("acc"))
        BitesAuth.restoreToken(persisted!!)
        assertEquals("acc", BitesCore.storedToken!!.accessToken)
        assertTrue(BitesCore.expiresAtMs > 0)
        BitesAuth.clearToken()
        assertEquals(false, BitesAuth.isLoggedIn()); assertEquals(true, cleared)
        BitesAuth.restoreToken("not json") // tolerated, stays signed out
        assertEquals(false, BitesAuth.isLoggedIn())
        // verifyPhone failure modes
        BitesCore.transport = { url, _, _, _ -> resp(500, "{}", url) }
        assertNull(BitesAuth.verifyPhone("555"))
        assertNull(BitesAuth.exchangeOtpCodeForToken("s", "1"))
    } }

    @Test fun expiredTokenRefreshesAndRetriesOnce() { runBlocking {
        BitesAuth.setToken(AuthToken("old-acc", "ref-1", "Bearer", 3600))
        BitesCore.expiresAtMs = 1 // long expired
        var tokenCalls = 0
        BitesCore.transport = { url, method, headers, body ->
            recorded += Recorded(url, method, headers, body)
            when {
                url.contains("/auth/token") -> { tokenCalls++; resp(200, """{"access_token":"new-acc","refresh_token":"ref-2","token_type":"Bearer","expires_in":3600}""", url) }
                headers["Authorization"] == "Bearer new-acc" -> resp(200, """{"data":{"merchants":[]}}""", url)
                else -> resp(401, "{}", url)
            }
        }
        val merchants = BitesMerchants.getMerchants()
        assertTrue(merchants.isEmpty()) // 200 wrapper with empty list still proves the retry succeeded
        assertEquals(1, tokenCalls)
        assertEquals("new-acc", BitesCore.storedToken!!.accessToken)
        assertTrue(recorded.any { it.headers["Authorization"] == "Bearer new-acc" })
    } }

    @Test fun rejectedRefreshSignsOutAndRetriesAnonymously() { runBlocking {
        BitesAuth.setToken(AuthToken("old-acc", "ref-1", "Bearer", 3600))
        BitesCore.expiresAtMs = 1
        BitesCore.transport = { url, method, headers, body ->
            recorded += Recorded(url, method, headers, body)
            when {
                url.contains("/auth/token") -> resp(401, "{}", url) // 4xx rejection
                headers["Authorization"] == null -> resp(200, """{"data":{"id":5}}""", url)
                else -> resp(401, "{}", url)
            }
        }
        val detail = BitesMerchants.getMerchantDetail(5)
        assertEquals(5, detail!!.id)
        assertEquals(false, BitesAuth.isLoggedIn()) // rejected refresh signed us out
    } }

    @Test fun transientRefreshFailureKeepsSessionAndDoesNotRetry() { runBlocking {
        BitesAuth.setToken(AuthToken("old-acc", "ref-1", "Bearer", 3600))
        BitesCore.expiresAtMs = 1
        var dataCalls = 0
        BitesCore.transport = { url, _, _, _ ->
            when {
                url.contains("/auth/token") -> resp(500, "{}", url)
                else -> { dataCalls++; resp(401, "{}", url) }
            }
        }
        assertNull(BitesMerchants.getMerchantDetail(5))
        assertEquals(1, dataCalls) // no retry after a transient refresh failure
        assertEquals(true, BitesAuth.isLoggedIn()) // session kept
    } }

    @Test fun noRefreshTokenKeepsStaleToken() { runBlocking {
        BitesAuth.setToken(AuthToken("solo-acc", "", "Bearer", 3600))
        BitesCore.expiresAtMs = 1
        // Without a refresh token there is nothing to exchange: the stale access
        // token is returned (refresh is only attempted when a refresh token exists).
        assertEquals("solo-acc", BitesCore.getAccessToken())
        assertEquals(true, BitesAuth.isLoggedIn())
        // A token with no access token at all yields no header value.
        BitesAuth.setToken(AuthToken("", "ref", "Bearer", 3600))
        BitesCore.expiresAtMs = Long.MAX_VALUE
        assertNull(BitesCore.getAccessToken())
    } }

    @Test fun malformedSuccessBodiesDecodeToNull() { runBlocking {
        BitesCore.transport = { url, _, _, _ -> resp(200, "not json at all", url) }
        assertNull(BitesCustomers.getCustomer())
        assertTrue(BitesDeals.getAllDeals().isEmpty())
        assertNull(BitesDeals.getDealById(3))
    } }
}
