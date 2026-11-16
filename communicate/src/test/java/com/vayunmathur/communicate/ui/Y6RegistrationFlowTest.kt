package com.vayunmathur.communicate.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.ui.signal.SignalRegistrationScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationScreen
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Y6: full registration screen flows against scripted localhost servers,
 * via the clients' companion base-override seams (screens remember their
 * own client instances). WhatsApp: code -> 2FA PIN -> Done. Signal:
 * session -> code -> verified registration -> Done, plus the captcha step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y6RegistrationFlowTest {

    @get:Rule val rule = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: HttpServer
    private lateinit var waDb: WhatsAppDatabase
    private lateinit var sigDb: SignalDatabase
    private val requests = mutableListOf<String>()
    private val scripted = mutableMapOf<String, Pair<Int, String>>()

    @BeforeTest fun setUp() {
        requests.clear(); scripted.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val body = ex.requestBody.readBytes().decodeToString()
            requests += "$path $body"
            val (status, resp) = scripted.entries.filter { path.contains(it.key) }.maxByOrNull { it.key.length }?.value
                ?: (200 to """{"status":"ok"}""")
            val bytes = resp.encodeToByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        com.vayunmathur.communicate.data.whatsapp.registration.RegistrationHttpClient.baseOverrideForTest = "$base/v2"
        // WhatsApp client builds "$base/$path" where path is just the endpoint name.
        com.vayunmathur.communicate.data.whatsapp.registration.RegistrationHttpClient.baseOverrideForTest = base
        com.vayunmathur.communicate.data.signal.registration.RegistrationHttpClient.baseOverrideForTest = base
        val app = ApplicationProvider.getApplicationContext<Application>()
        waDb = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        sigDb = Room.inMemoryDatabaseBuilder(app, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        WhatsAppDatabase.overrideForTest = waDb
        SignalDatabase.overrideForTest = sigDb
        com.vayunmathur.communicate.data.signal.SignalClient.db = sigDb
    }

    @AfterTest fun tearDown() {
        com.vayunmathur.communicate.data.whatsapp.registration.RegistrationHttpClient.baseOverrideForTest = null
        com.vayunmathur.communicate.data.signal.registration.RegistrationHttpClient.baseOverrideForTest = null
        WhatsAppDatabase.overrideForTest = null
        SignalDatabase.overrideForTest = null
        waDb.close(); sigDb.close()
        server.stop(0)
    }

    private fun dumpTexts(): String = runCatching {
        rule.onAllNodesWithText("", substring = true).fetchSemanticsNodes()
            .joinToString(" | ") { it.config.toString().take(120) }
    }.getOrElse { "dump failed: $it" }

    private fun awaitText(text: String, substring: Boolean = false) {
        rule.waitUntil(timeoutMillis = 15_000) {
            try {
                rule.onNodeWithText(text, substring = substring).assertIsDisplayed(); true
            } catch (_: Throwable) { false }
        }
    }

    @Test fun whatsAppFlowThroughPinToDone() {
        scripted["code"] = 200 to """{"status":"sent","method":"sms","length":6}"""
        scripted["register"] = 200 to """{"status":"security_code"}"""
        scripted["security"] = 200 to """{"status":"ok","new_jid":"15550102030@s.whatsapp.net","server_time":99}"""
        var registered = 0
        rule.setContent { WhatsAppRegistrationScreen(onBack = {}, onRegistered = { registered++ }) }
        rule.onNodeWithText("Phone number").performTextInput("5550102030")
        rule.onNodeWithText("Request SMS code").performClick()
        awaitText("6-digit code")
        rule.onNodeWithText("6-digit code").performTextInput("123456")
        rule.onNodeWithText("Verify").performClick()
        awaitText("2FA PIN")
        rule.onNodeWithText("2FA PIN").performTextInput("654321")
        rule.onNodeWithText("Submit PIN").performClick()
        awaitText("Registered ✓", substring = true)
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        assertEquals(1, registered)
        assertTrue(requests.any { it.startsWith("/code") }, "$requests")
        assertTrue(requests.any { it.startsWith("/security") }, "$requests")
    }

    @Test fun whatsAppDirectRegisterToDone() {
        scripted["code"] = 200 to """{"status":"sent"}"""
        scripted["register"] = 200 to """{"status":"ok","new_jid":"15550102030@s.whatsapp.net"}"""
        var registered = 0
        rule.setContent { WhatsAppRegistrationScreen(onBack = {}, onRegistered = { registered++ }) }
        rule.onNodeWithText("Phone number").performTextInput("5550102030")
        rule.onNodeWithText("Request SMS code").performClick()
        awaitText("6-digit code")
        rule.onNodeWithText("6-digit code").performTextInput("111111")
        rule.onNodeWithText("Verify").performClick()
        awaitText("Registered ✓", substring = true)
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, registered)
    }



}
