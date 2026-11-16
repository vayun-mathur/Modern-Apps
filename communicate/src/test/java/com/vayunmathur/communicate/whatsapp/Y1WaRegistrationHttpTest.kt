package com.vayunmathur.communicate.whatsapp

import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationHttpClient
import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationKeys
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Y1: WhatsApp v2 registration client against a scripted localhost
 * server (never the real service), via the client's baseUrlForTest seam.
 * Covers every endpoint's request building and response parsing, including
 * register finalization (new_jid / login fallback / failure) and the
 * unparseable/network error paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y1WaRegistrationHttpTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: HttpServer
    private lateinit var client: RegistrationHttpClient
    private val requests = mutableListOf<String>()
    private val scripted = mutableMapOf<String, String>()

    @BeforeTest fun setUp() {
        scripted.clear(); requests.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.trimStart('/')
            val body = ex.requestBody.readBytes().decodeToString()
            requests += "$path $body"
            val resp = scripted[path] ?: """{"status":"fail","reason":"unscripted"}"""
            val bytes = resp.encodeToByteArray()
            ex.responseHeaders.add("Content-Type", "text/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        client = RegistrationHttpClient(ctx)
        client.baseUrlForTest = "http://127.0.0.1:${server.address.port}"
        // Seed key scaffold so register/security/exist bundle paths run.
        WhatsAppAuthData.save(ctx, RegistrationKeys.generate("15550102030").authScaffold)
    }

    @AfterTest fun tearDown() { server.stop(0) }

    @Test fun requestCodeParsesSentAndBuildsParams(): Unit = runBlocking {
        scripted["code"] = """{"status":"sent","method":"sms","length":6,"retry_after":30}"""
        val r = client.requestCode("1", "5550102030", "sms")
        assertTrue(r.ok); assertEquals("sent", r.status); assertEquals("sms", r.method)
        assertEquals(6, r.length); assertEquals(30L, r.retryAfter)
        val req = requests.single { it.startsWith("code ") }
        assertTrue(req.contains("cc=1"), req); assertTrue(req.contains("method=sms"), req)
        assertTrue(req.contains("token="), req); assertTrue(req.contains("e_ident="), req)
        // requestCode persists a fresh scaffold
        assertNotNull(WhatsAppAuthData.load(ctx))
    }

    @Test fun requestCodeFailCarriesReasonAndParam(): Unit = runBlocking {
        scripted["code"] = """{"status":"fail","reason":"too_recent","param":"retry_after"}"""
        val r = client.requestCode("1", "5550102030", "voice")
        assertFalse(r.ok); assertEquals("too_recent", r.reason); assertEquals("retry_after", r.param)
        assertTrue(requests.single().contains("method=voice"))
    }

    @Test fun registerSuccessWithNewJidFinalizesAuth(): Unit = runBlocking {
        scripted["register"] = """{"status":"ok","new_jid":"15550102030","lid":"999","server_time":12345}"""
        val r = client.register("1", "5550102030", "123-456")
        assertTrue(r.ok); assertEquals("15550102030", r.newJid) // raw server value; auth wid is normalized below
        assertEquals(12345L, r.serverTime)
        assertNotNull(r.auth); assertTrue(r.auth!!.registered)
        assertEquals("999@lid", r.auth!!.lid)
        assertEquals("15550102030@s.whatsapp.net", WhatsAppAuthData.load(ctx)!!.wid)
        assertTrue(requests.single { it.startsWith("register ") }.contains("code=123456"))
    }

    @Test fun registerSuccessWithLoginOnlyDerivesJid(): Unit = runBlocking {
        scripted["register"] = """{"status":"ok","login":"15559998888","lid":"888@lid"}"""
        val r = client.register("1", "5559998888", "000000")
        assertTrue(r.ok); assertEquals("15559998888@s.whatsapp.net", r.newJid)
        assertEquals("15559998888@s.whatsapp.net", r.auth!!.wid)
        assertEquals("888@lid", r.auth!!.lid)
    }

    @Test fun registerFailureDoesNotFinalize(): Unit = runBlocking {
        scripted["register"] = """{"status":"fail","reason":"incorrect","param":"code"}"""
        val r = client.register("1", "5550102030", "111111")
        assertFalse(r.ok); assertEquals("incorrect", r.reason); assertNull(r.auth)
        assertFalse(WhatsAppAuthData.load(ctx)!!.registered)
    }

    @Test fun twoFactorUsesSecurityEndpointAndFinalizes(): Unit = runBlocking {
        scripted["security"] = """{"status":"ok","new_jid":"15550102030@s.whatsapp.net"}"""
        val r = client.submitTwoFactor("1", "5550102030", "123456")
        assertTrue(r.ok)
        assertTrue(requests.any { it.startsWith("security ") && it.contains("code=123456") }, "$requests")
    }

    @Test fun checkExistParsesAndSendsToken(): Unit = runBlocking {
        scripted["exist"] = """{"status":"ok"}"""
        val r = client.checkExist("1", "5550102030")
        assertEquals("ok", r.status)
        assertTrue(requests.single { it.startsWith("exist ") }.contains("token="))
        scripted["exist"] = """{"status":"fail","reason":"bad_param","param":"token"}"""
        val r2 = client.checkExist("44", "2079460958")
        assertEquals("bad_param", r2.reason); assertEquals("token", r2.param)
    }

    @Test fun consentAutoconfAndVerifierParse(): Unit = runBlocking {
        scripted["consent"] = """{"status":"ok","pending":"parent","url":"https://example.test/c"}"""
        val c = client.consent("1", "5550102030", "dob", dob = "2000-01-01", ageLowerBound = 18,
            consentDecision = "accept", consentId = "cid", consentVersion = "2", securityCode = "999")
        assertTrue(c.ok); assertEquals("parent", c.pending); assertEquals("https://example.test/c", c.url)
        assertTrue(requests.last().contains("context=dob"))

        scripted["autoconf"] = """{"status":"sent","register_start_message":"start"}"""
        val a = client.autoconf("1", "5550102030", consent = 1, clientCapabilities = "cap", consentShown = true, createVerifier = true)
        assertTrue(a.ok); assertEquals("start", a.registerStartMessage)
        assertTrue(requests.last().contains("consent_shown=true"))

        scripted["autoconf_verifier"] = """{"status":"ok"}"""
        val v = client.autoconfVerifier("1", "5550102030", "12-34", "encdata", 5)
        assertTrue(v.ok); assertTrue(requests.last().contains("code=1234"))
    }

    @Test fun captchaChallengeAndClientLogParse(): Unit = runBlocking {
        scripted["captcha_verify"] = """{"status":"verified","violation_type":"15","appeal_token":"tok"}"""
        val cap = client.captchaVerify("1", "5550102030", "fraud-code")
        assertTrue(cap.ok); assertEquals("15", cap.violationType); assertEquals("tok", cap.appealToken)
        assertTrue(requests.last().contains("fraud_checkpoint_code=fraud-code"))

        scripted["challenge"] = """{"status":"ok"}"""
        val ch = client.challenge("1", "5550102030", "email_enter", email = "a@b.c", oauthToken = null, code = null)
        assertTrue(ch.ok); assertTrue(requests.last().contains("challenge_type=email_enter"))
        assertTrue(requests.last().contains("email=a%40b.c"))

        scripted["client_log"] = """{"status":"ok"}"""
        val log = client.clientLog("ctx", "type", cc = "1", number = "5550102030")
        assertTrue(log.ok); assertTrue(requests.last().contains("client_error_context=ctx"))
    }

    @Test fun unparseableBodyBecomesErrorStatus(): Unit = runBlocking {
        scripted["exist"] = "not json at all"
        val r = client.checkExist("1", "5550102030")
        assertEquals("error", r.status); assertEquals("unparseable", r.reason)
    }

    @Test fun networkFailureBecomesNetworkError(): Unit = runBlocking {
        client.baseUrlForTest = "http://127.0.0.1:1"
        val r = client.checkExist("1", "5550102030")
        assertEquals("error", r.status)
        assertTrue(r.reason!!.startsWith("network:"), "${r.reason}")
    }

    @Test fun encWrapperClientStillBuildsRequest(): Unit = runBlocking {
        val encClient = RegistrationHttpClient(ctx, useEncWrapper = true)
        encClient.baseUrlForTest = "http://127.0.0.1:${server.address.port}"
        scripted["client_log"] = """{"status":"ok"}"""
        val r = encClient.clientLog("c", "t")
        // ENC may fail on host (attestation) and fall back to plain query; either way server is hit.
        assertTrue(requests.any { it.startsWith("client_log ") }, "$requests")
        assertEquals("ok", r.status)
    }
}
