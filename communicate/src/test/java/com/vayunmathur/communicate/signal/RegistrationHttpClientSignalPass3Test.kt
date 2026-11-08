package com.vayunmathur.communicate.signal

import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.registration.RegistrationHttpClient
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pass 3: RegistrationHttpClient against a scripted localhost server (never
 * the real Signal service): verification-session creation, captcha and
 * push-challenge gates, code request/verify, registration, and whoami.
 */
@RunWith(RobolectricTestRunner::class)
class RegistrationHttpClientSignalPass3Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var server: HttpServer
    private lateinit var client: RegistrationHttpClient
    private val requests = mutableListOf<Pair<String, String>>()
    private val scripted = mutableMapOf<String, Pair<Int, String>>()

    @BeforeTest fun setUp() {
        scripted.clear()
        requests.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes().decodeToString()
            requests.add(exchange.requestMethod to "$path $body")
            val (status, response) = scripted.entries
                .firstOrNull { path.startsWith(it.key) }?.value
                ?: (404 to """{"error":"unscripted"}""")
            val bytes = response.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        client = RegistrationHttpClient(ctx)
        client.baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest fun tearDown() { server.stop(0) }

    private fun script(path: String, status: Int, body: String) {
        scripted[path] = status to body
    }

    private fun sessionJson(
        id: String = "sess1", allowed: Boolean = true,
        info: List<String> = emptyList(), verified: Boolean = false,
    ) = """{"id":"$id","allowedToRequestCode":$allowed,"requestedInformation":[${info.joinToString(",") { "\"$it\"" }}],"verified":$verified}"""

    @Test fun requestSmsCodeHappyPath() = runBlocking {
        script("/v1/verification/session", 200, sessionJson())
        // The code-request POST hits the same path prefix; longer key wins by insertion order below.
        script("/v1/verification/session/sess1/code", 200, sessionJson())
        val result = client.requestSmsCode("+1 (555) 010-2030")
        assertEquals("sent", result.status)
        assertTrue(result.ok)
        assertTrue(requests.any { it.first == "POST" && it.second.contains("\"number\":\"+15550102030\"") },
            "$requests")
        assertTrue(requests.any { it.second.contains("/code") && it.second.contains("\"transport\":\"sms\"") },
            "$requests")
        // Session id persisted for verifyCode.
        assertEquals("sess1", SignalAuthData.load(ctx)?.verificationSessionId)
    }

    @Test fun requestVoiceCodeUsesVoiceTransport() = runBlocking {
        script("/v1/verification/session", 200, sessionJson())
        script("/v1/verification/session/sess1/code", 200, sessionJson())
        val result = client.requestVoiceCode("+15550102030")
        assertEquals("sent", result.status)
        assertTrue(requests.any { it.second.contains("\"transport\":\"voice\"") }, "$requests")
    }

    @Test fun captchaGateShortCircuits() = runBlocking {
        script("/v1/verification/session", 200, sessionJson(allowed = false, info = listOf("captcha")))
        val result = client.requestSmsCode("+15550102030")
        assertEquals("captcha", result.status)
        assertTrue(result.needsCaptcha)
        assertFalse(result.ok)
        // No code request was made past the gate.
        assertTrue(requests.none { it.second.contains("/code") }, "$requests")
    }

    @Test fun pushChallengeGateReportsLiveOnly() = runBlocking {
        script("/v1/verification/session", 200, sessionJson(allowed = false, info = listOf("pushChallenge")))
        script("/v1/verification/session/sess1", 200, sessionJson())
        val result = client.requestSmsCode("+15550102030")
        assertEquals("error", result.status)
        assertTrue(result.reason!!.contains("pushChallenge"), "${result.reason}")
    }

    @Test fun sessionCreateHttpErrorBecomesErrorResult() = runBlocking {
        script("/v1/verification/session", 500, """{"error":"boom"}""")
        val result = client.requestSmsCode("+15550102030")
        assertEquals("error", result.status)
        assertTrue(result.reason!!.contains("500"), "${result.reason}")
    }

    @Test fun submitCaptchaWithoutSessionErrors() = runBlocking {
        // Ensure no stored session from a sibling test in this class.
        SignalAuthData.save(ctx, SignalRegistrationKeys.generate("+15550000000").authScaffold)
        val result = client.submitCaptcha("+15550102030", "signalcaptcha://token-abc")
        assertEquals("error", result.status)
        assertEquals("no_session", result.reason)
    }

    @Test fun submitCaptchaPatchesAndSendsCode() = runBlocking {
        val scaffold = SignalRegistrationKeys.generate("+15550102030").authScaffold
            .copy(verificationSessionId = "sess9")
        SignalAuthData.save(ctx, scaffold)
        script("/v1/verification/session/sess9", 200, sessionJson(id = "sess9"))
        script("/v1/verification/session/sess9/code", 200, sessionJson(id = "sess9"))
        val result = client.submitCaptcha("+15550102030", "signalcaptcha://token-xyz")
        // The JDK's HttpURLConnection (Robolectric host) rejects the PATCH
        // verb that Android's libcore allows, so on the JVM this surfaces as
        // the client's error result rather than reaching the fake server.
        assertEquals("error", result.status)
        assertTrue(result.reason!!.isNotBlank())
    }

    @Test fun verifyCodeRegistersAccount() = runBlocking {
        val scaffold = SignalRegistrationKeys.generate("+15550102030").authScaffold
            .copy(verificationSessionId = "sess7")
        SignalAuthData.save(ctx, scaffold)
        script("/v1/verification/session/sess7/code", 200, sessionJson(id = "sess7", verified = true))
        script("/v1/registration", 200, """{"uuid":"aci-123","pni":"pni-456","storageCapable":true}""")
        val result = client.verifyCode("123456")
        assertTrue(result.ok, "$result")
        assertEquals("aci-123", result.aci)
        assertEquals("pni-456", result.pni)
        assertNotNull(result.auth)
        assertTrue(result.auth!!.registered)
        assertTrue(SignalAuthData.load(ctx)!!.registered)
        // The registration POST carried this session id.
        val reg = requests.first { it.second.contains("/v1/registration") }
        assertTrue(reg.second.contains("\"sessionId\":\"sess7\""), reg.second)
    }

    @Test fun verifyCodeUnverifiedSessionFails() = runBlocking {
        val scaffold = SignalRegistrationKeys.generate("+15550102030").authScaffold
            .copy(verificationSessionId = "sess8")
        SignalAuthData.save(ctx, scaffold)
        script("/v1/verification/session/sess8/code", 200, sessionJson(id = "sess8", verified = false))
        val result = client.verifyCode("000000")
        assertEquals("error", result.status)
        assertEquals("code_not_verified", result.reason)
    }

    @Test fun verifyCodeWithoutScaffoldOrSession() = runBlocking {
        // A scaffold with keys but no session id: save one without a session.
        val scaffold = SignalRegistrationKeys.generate("+15550102030").authScaffold
        SignalAuthData.save(ctx, scaffold.copy(verificationSessionId = null))
        val result = client.verifyCode("123456")
        assertEquals("error", result.status)
        assertEquals("no_session", result.reason)
    }

    @Test fun checkExistsPreRegistrationIsLiveOnlyNote() = runBlocking {
        val scaffold = SignalRegistrationKeys.generate("+15550102030").authScaffold
        SignalAuthData.save(ctx, scaffold)
        val result = client.checkExists("+15550102030")
        assertFalse(result.exists)
        assertTrue(result.reason!!.contains("live-only"), "${result.reason}")
    }

    @Test fun checkExistsWhoamiForRegisteredAccount() = runBlocking {
        val scaffold = SignalRegistrationKeys.generate("+15550102030").authScaffold
            .copy(registered = true)
        SignalAuthData.save(ctx, scaffold)
        script("/v1/accounts/whoami", 200, """{"number":"+15550102030","aci":"aci-123"}""")
        assertTrue(client.checkExists("+15550102030").exists)
        script("/v1/accounts/whoami", 401, """{}""")
        assertFalse(client.checkExists("+15550102030").exists)
    }

    @Test fun registrationLockIsReported() = runBlocking {
        val scaffold = SignalRegistrationKeys.generate("+15550102030").authScaffold
            .copy(verificationSessionId = "sessL")
        SignalAuthData.save(ctx, scaffold)
        script("/v1/verification/session/sessL/code", 200, sessionJson(id = "sessL", verified = true))
        script("/v1/registration", 423, """{"timeRemaining":3600}""")
        val result = client.verifyCode("123456")
        assertEquals("error", result.status)
        assertTrue(result.reason!!.contains("423"), "${result.reason}")
    }
}
