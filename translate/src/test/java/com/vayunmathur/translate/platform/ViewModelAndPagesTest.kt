package com.vayunmathur.translate.platform

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.translate.data.TranslationRecord
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.ui.HistoryPage
import com.vayunmathur.translate.ui.LanguagePickerPage
import com.vayunmathur.translate.ui.TextTranslatePage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


/** In-memory AndroidKeyStore stand-in: Robolectric has no KeyStore provider,
 *  and TranslateViewModel's repository opens its SQLCipher key from it at
 *  construction. The AES key itself is a plain SunJCE key; only the KeyStore
 *  and KeyGenerator services are faked - Cipher/Base64 stay real. */
class FakeAndroidKeyStoreProvider : java.security.Provider("AndroidKeyStore", 1.0, "test fake") {
    init {
        put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", FakeKeyGeneratorSpi::class.java.name)
    }
}

class FakeKeyStoreSpi : java.security.KeyStoreSpi() {
    companion object { val keys = java.util.concurrent.ConcurrentHashMap<String, java.security.Key>() }
    override fun engineGetKey(alias: String, password: CharArray?): java.security.Key? = keys[alias]
    override fun engineGetCertificateChain(alias: String): Array<java.security.cert.Certificate>? = null
    override fun engineGetCertificate(alias: String): java.security.cert.Certificate? = null
    override fun engineGetCreationDate(alias: String): java.util.Date = java.util.Date()
    override fun engineSetKeyEntry(alias: String, key: java.security.Key, password: CharArray?, chain: Array<out java.security.cert.Certificate>?) { keys[alias] = key }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out java.security.cert.Certificate>?) {}
    override fun engineSetCertificateEntry(alias: String, cert: java.security.cert.Certificate?) {}
    override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
    override fun engineAliases(): java.util.Enumeration<String> = java.util.Collections.enumeration(keys.keys)
    override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
    override fun engineSize(): Int = keys.size
    override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCertificateAlias(cert: java.security.cert.Certificate): String? = null
    override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) {}
    override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) {}
}

class FakeKeyGeneratorSpi : javax.crypto.KeyGeneratorSpi() {
    private var alias: String = "db_no_auth_key"
    override fun engineInit(random: java.security.SecureRandom?) {}
    override fun engineInit(params: java.security.spec.AlgorithmParameterSpec?, random: java.security.SecureRandom?) {
        if (params is android.security.keystore.KeyGenParameterSpec) alias = params.keystoreAlias
    }
    override fun engineInit(keysize: Int, random: java.security.SecureRandom?) {}
    override fun engineGenerateKey(): javax.crypto.SecretKey {
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val key = javax.crypto.spec.SecretKeySpec(raw, "AES")
        FakeKeyStoreSpi.keys[alias] = key
        return key
    }
}

@RunWith(RobolectricTestRunner::class)
class ViewModelAndPagesTest {

    companion object {
        @org.junit.BeforeClass
        @JvmStatic
        fun installFakeKeyStore() {
            if (java.security.Security.getProvider("AndroidKeyStore")?.name != "AndroidKeyStore" ||
                java.security.Security.getProvider("AndroidKeyStore") !is FakeAndroidKeyStoreProvider
            ) {
                runCatching { java.security.Security.removeProvider("AndroidKeyStore") }
                java.security.Security.insertProviderAt(FakeAndroidKeyStoreProvider(), 1)
            }
        }
    }


    @get:Rule
    val rule = createComposeRule()

    private fun vm(): TranslateViewModel =
        TranslateViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // --- ViewModel -------------------------------------------------------------------

    @Test
    fun `viewmodel defaults and language setters track state`() {
        val viewModel = vm()
        idle()
        assertTrue(viewModel.sourceLang.value in Languages.SOURCES.map { it.code })
        assertTrue(viewModel.targetLang.value in Languages.TARGETS.map { it.code })
        assertFalse(viewModel.translationAvailable.value)
        viewModel.setSource("fr")
        assertEquals("fr", viewModel.sourceLang.value)
        viewModel.setTarget("de")
        assertEquals("de", viewModel.targetLang.value)
        idle()
        // Persistence of that write (and the recents list) is asserted
        // deterministically in DataLayerTest; a second reader here raced the
        // DataStore actor under the full-class load and flaked, so this test
        // sticks to the synchronous StateFlow contract above.
    }

    @Test
    fun `viewmodel swap is a no-op for auto and swaps concrete pairs`() {
        val viewModel = vm()
        idle()
        viewModel.setSource(Languages.AUTO.code)
        viewModel.setTarget("es")
        viewModel.swap()
        assertEquals(Languages.AUTO.code, viewModel.sourceLang.value)
        assertEquals("es", viewModel.targetLang.value)
        viewModel.setSource("en")
        viewModel.setTarget("fr")
        viewModel.swap()
        assertEquals("fr", viewModel.sourceLang.value)
        assertEquals("en", viewModel.targetLang.value)
        viewModel.swap()
        assertEquals("en", viewModel.sourceLang.value)
        assertEquals("fr", viewModel.targetLang.value)
    }

    @Test
    fun `viewmodel translate fails closed without the model`() = runBlocking {
        val viewModel = vm()
        idle()
        assertNull(viewModel.translate("hello"))
        viewModel.setSource("en")
        assertNull(viewModel.translate("hello"))
        assertFalse(viewModel.translationAvailable.value)
    }

    @Test
    fun `viewmodel speak and stop delegate to tts safely`() {
        val viewModel = vm()
        idle()
        var missing = 0
        viewModel.speak("hola") { missing++ }
        viewModel.speak("hola", "fr") { missing++ }
        viewModel.stopSpeaking()
        idle()
    }

    // --- Pages ---------------------------------------------------------------------------

    @Test
    fun `language picker page selecting a row sets the language and goes back`() {
        val viewModel = vm()
        idle()
        var back = 0
        rule.setContent {
            LanguagePickerPage(viewModel = viewModel, forSource = false, onBack = { back++ })
        }
        rule.onNodeWithText(Languages.ALL.first().nativeName).performClick()
        assertEquals(Languages.ALL.first().code, viewModel.targetLang.value)
        assertEquals(1, back)
    }

    @Test
    fun `language picker page source mode search filters rows`() {
        val viewModel = vm()
        idle()
        rule.setContent {
            LanguagePickerPage(viewModel = viewModel, forSource = true, onBack = {})
        }
        rule.onNodeWithText("Search languages").performTextInput("German")
        rule.waitForIdle()
        rule.onNodeWithText(Languages.byCode("de").nativeName).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText(Languages.byCode("fr").nativeName).fetchSemanticsNodes().isEmpty())
    }

}
