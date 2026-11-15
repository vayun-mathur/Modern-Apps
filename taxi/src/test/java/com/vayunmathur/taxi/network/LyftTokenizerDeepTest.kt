package com.vayunmathur.taxi.network

import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.network.ProtoWire as W
import com.vayunmathur.taxi.network.lyft.LyftCardTokenizer
import com.vayunmathur.taxi.network.lyft.TokenizeResult
import com.vayunmathur.taxi.network.lyft.TokenizerConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyftTokenizerDeepTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = TaxiPass3FakeHttp()
    private val factory = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }
    private val tokenizer = LyftCardTokenizer(json, factory) { tok -> mapOf("Authorization" to "Bearer $tok") }
    private val card = NewCard("4111111111111111", 12, 2030, "123", "94101")
    private val cardNoZip = NewCard("4111111111111111", 12, 2030, "123", "")

    @BeforeTest fun setUp() = http.install()
    @AfterTest fun tearDown() = http.uninstall()

    @Test fun parseConfigJsonSetupIntentWinsOverLegacy() {
        val raw = """{"strategies":[
          {"stripe_card_data":{"api_key":"pk_legacy"}},
          {"stripe_card_setup_intent_data":{"api_key":"pk_setup","client_secret":"cs","setup_intent_id":"seti_1","stripe_api_version":"2024-01"}},
          {"braintree_card_data":{"api_key":"bt_tok"}}]}"""
        val c = tokenizer.parseConfigJson(raw)!!
        assertEquals("stripe_setup_intent", c.provider); assertEquals("pk_setup", c.key)
        assertEquals("cs", c.clientSecret); assertEquals("seti_1", c.setupIntentId); assertEquals("2024-01", c.stripeApiVersion)
    }

    @Test fun parseConfigJsonLegacyStripeThenBraintree() {
        assertEquals("stripe", tokenizer.parseConfigJson("""{"strategies":[{"stripe_card_data":{"api_key":"pk"}}]}""")!!.provider)
        val bt = tokenizer.parseConfigJson("""{"strategies":[{"braintree_card_data":{"api_key":"bt"}}]}""")!!
        assertEquals("braintree", bt.provider); assertEquals("bt", bt.key)
        // unknown provider only -> null
        assertNull(tokenizer.parseConfigJson("""{"strategies":[{"adyen":{"api_key":"x"}}]}"""))
        assertNull(tokenizer.parseConfigJson("""{"strategies":[]}"""))
        assertNull(tokenizer.parseConfigJson("""{}"""))
        assertNull(tokenizer.parseConfigJson("not json"))
        assertNull(tokenizer.parseConfigJson("""{"strategies":[{"stripe_card_data":{}}]}"""))
    }

    @Test fun parseConfigProtoAllProviders() {
        // strategy msg: field22 setup, 10 stripe, 11 braintree; each api_key field1
        fun strategy(field: Int, vararg inner: ByteArray): ByteArray = W.msg(field, *inner)
        val setupBytes = W.msg(1, strategy(22, W.stringField(1, "pk_s"), W.stringField(2, "cs2"), W.stringField(3, "seti_2"), W.stringField(4, "v2")))
        val setup = tokenizer.parseConfigProto(setupBytes)!!
        assertEquals("stripe_setup_intent", setup.provider); assertEquals("seti_2", setup.setupIntentId)
        val stripeBytes = W.msg(1, strategy(10, W.stringField(1, "pk_l")))
        assertEquals("stripe", tokenizer.parseConfigProto(stripeBytes)!!.provider)
        val btBytes = W.msg(1, strategy(11, W.stringField(1, "bt_k")))
        assertEquals("braintree", tokenizer.parseConfigProto(btBytes)!!.provider)
        assertNull(tokenizer.parseConfigProto(byteArrayOf()))
        assertNull(tokenizer.parseConfigProto(W.msg(1, strategy(99, W.stringField(1, "x")))))
    }

    @Test fun parseConfigDispatchByContentType() {
        val jsonResp = http.raw(200, """{"strategies":[{"stripe_card_data":{"api_key":"pkj"}}]}""")
        assertEquals("pkj", tokenizer.parseConfig(jsonResp)?.key)
        val protoBytes = W.msg(1, W.msg(10, W.stringField(1, "pkp")))
        val protoResp = com.vayunmathur.library.network.RawResponse(200, "OK", protoBytes, mapOf("Content-Type" to listOf("application/x-protobuf")), "")
        assertEquals("pkp", tokenizer.parseConfig(protoResp)?.key)
    }

    @Test fun braintreeClientTokenRawBase64AndFailures() {
        val rawJson = """{"authorizationFingerprint":"fp1","graphQL":{"url":"https://gql.example/graphql"}}"""
        assertEquals("https://gql.example/graphql" to "fp1", tokenizer.parseBraintreeClientToken(rawJson))
        val noUrl = """{"authorizationFingerprint":"fp2"}"""
        assertEquals("https://payments.braintree-api.com/graphql" to "fp2", tokenizer.parseBraintreeClientToken(noUrl))
        val b64 = android.util.Base64.encodeToString(noUrl.toByteArray(), android.util.Base64.DEFAULT)
        assertEquals("fp2", tokenizer.parseBraintreeClientToken(b64)?.second)
        assertNull(tokenizer.parseBraintreeClientToken("""{"nope":1}"""))
        assertNull(tokenizer.parseBraintreeClientToken("!!!not-base64-json!!!"))
        assertNull(tokenizer.parseBraintreeClientToken(""))
    }

    @Test fun tokenizeUnsupportedProvider() = runTest {
        val r = tokenizer.tokenize(TokenizerConfig("adyen", "k"), card)
        assertTrue(r is TokenizeResult.Err); assertTrue((r as TokenizeResult.Err).message.contains("Unsupported"))
    }

    @Test fun tokenizeStripeSuccessErrorAndNoToken() = runTest {
        http.on("api.stripe.com/v1/tokens") { http.raw(200, """{"id":"tok_123"}""", url = it.url) }
        val ok = tokenizer.tokenizeStripe("pk", card) as TokenizeResult.Ok
        assertEquals("tok_123", ok.token); assertEquals("STRIPE_TOKEN", ok.version); assertEquals("stripe", ok.provider)
        // request was form encoded with card fields
        assertTrue(http.callsTo("api.stripe.com").single().body.toString().contains("card%5Bnumber%5D") || http.callsTo("api.stripe.com").single().body.toString().contains("card[number]"))
        http.uninstall(); http.install()
        http.on("api.stripe.com") { http.raw(402, """{"error":{"message":"card declined"}}""", url = it.url) }
        assertEquals("card declined", (tokenizer.tokenizeStripe("pk", card) as TokenizeResult.Err).message)
        http.uninstall(); http.install()
        http.on("api.stripe.com") { http.raw(200, """{"foo":1}""", url = it.url) }
        assertTrue((tokenizer.tokenizeStripe("pk", card) as TokenizeResult.Err).message.contains("no token"))
    }

    @Test fun tokenizeSetupIntentBranches() = runTest {
        // missing ids
        assertTrue((tokenizer.tokenizeStripeSetupIntent(TokenizerConfig("stripe_setup_intent", "pk"), card) as TokenizeResult.Err).message.contains("setup_intent_id"))
        assertTrue((tokenizer.tokenizeStripeSetupIntent(TokenizerConfig("stripe_setup_intent", "pk", setupIntentId = "seti"), card) as TokenizeResult.Err).message.contains("client_secret"))
        http.on("setup_intents") { http.raw(200, """{"payment_method":"pm_123"}""", url = it.url) }
        val cfg = TokenizerConfig("stripe_setup_intent", "pk", clientSecret = "cs", setupIntentId = "seti_9", stripeApiVersion = "2024-06")
        val ok = tokenizer.tokenizeStripeSetupIntent(cfg, card) as TokenizeResult.Ok
        assertEquals("pm_123", ok.token); assertEquals("STRIPE_SETUP_INTENT", ok.version)
        assertTrue(http.callsTo("setup_intents/seti_9/confirm").isNotEmpty())
        // without postal code also works (new fake route still matches)
        val ok2 = tokenizer.tokenizeStripeSetupIntent(cfg, cardNoZip) as TokenizeResult.Ok
        assertEquals("pm_123", ok2.token)
        http.uninstall(); http.install()
        http.on("setup_intents") { http.raw(400, """{"error":{"message":"bad secret"}}""", url = it.url) }
        assertEquals("bad secret", (tokenizer.tokenizeStripeSetupIntent(cfg, card) as TokenizeResult.Err).message)
        http.uninstall(); http.install()
        http.on("setup_intents") { http.raw(200, """{}""", url = it.url) }
        assertTrue((tokenizer.tokenizeStripeSetupIntent(cfg, card) as TokenizeResult.Err).message.contains("no payment_method"))
    }

    @Test fun tokenizeBraintreeBranches() = runTest {
        assertTrue((tokenizer.tokenizeBraintree("garbage-token", card) as TokenizeResult.Err).message.contains("parse Braintree"))
        val clientToken = """{"authorizationFingerprint":"fp","graphQL":{"url":"https://gql.example/graphql"}}"""
        http.on("gql.example") { http.raw(200, """{"data":{"tokenizeCreditCard":{"token":"nonce-abc"}}}""", url = it.url) }
        val ok = tokenizer.tokenizeBraintree(clientToken, card) as TokenizeResult.Ok
        assertEquals("nonce-abc", ok.nonce); assertEquals("braintree", ok.provider); assertNull(ok.token)
        http.uninstall(); http.install()
        http.on("gql.example") { http.raw(200, """{"errors":[{"message":"invalid card"}]}""", url = it.url) }
        assertEquals("invalid card", (tokenizer.tokenizeBraintree(clientToken, card) as TokenizeResult.Err).message)
        http.uninstall(); http.install()
        http.on("gql.example") { http.raw(200, """{"data":{}}""", url = it.url) }
        assertTrue((tokenizer.tokenizeBraintree(clientToken, card) as TokenizeResult.Err).message.contains("no nonce"))
        http.uninstall(); http.install()
        http.on("gql.example") { http.raw(500, "oops", "text/plain", it.url) }
        assertTrue((tokenizer.tokenizeBraintree(clientToken, card) as TokenizeResult.Err).message.contains("Braintree HTTP 500"))
    }

    @Test fun fetchConfigSuccessHttpErrorAndEmpty() = runTest {
        http.on("tokenization_strategies") { http.raw(200, """{"strategies":[{"stripe_card_data":{"api_key":"pk_fetch"}}]}""", url = it.url) }
        assertEquals("pk_fetch", tokenizer.fetchConfig("tok", card)?.key)
        val call = http.callsTo("tokenization_strategies").single()
        assertTrue(call.body.toString().contains("411111") || call.body.toString().contains("1111"))
        http.uninstall(); http.install()
        http.on("tokenization_strategies") { http.raw(500, "down", "text/plain", it.url) }
        assertNull(tokenizer.fetchConfig("tok", card))
        http.uninstall(); http.install()
        http.on("tokenization_strategies") { http.raw(200, """{"strategies":[]}""", url = it.url) }
        assertNull(tokenizer.fetchConfig("tok", card))
    }

    @Test fun tokenizeDispatchStripeAndBraintree() = runTest {
        http.on("api.stripe.com") { http.raw(200, """{"id":"tok_d"}""", url = it.url) }
        val r = tokenizer.tokenize(TokenizerConfig("stripe", "pk"), card) as TokenizeResult.Ok
        assertEquals("tok_d", r.token)
        assertNotNull(r)
    }
}
