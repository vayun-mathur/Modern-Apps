package com.vayunmathur.code.data

import androidx.test.core.app.ApplicationProvider
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sealing contract for the git token: sealed at rest, legacy migration, fail-closed reads. */
@RunWith(RobolectricTestRunner::class)
class CodeTokenSealingTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Writes values verbatim, so tests can plant legacy plaintext / foreign sealed values. */
    private object PassThroughSealer : TokenSealer {
        override fun seal(plain: String): String = plain
        override fun unseal(stored: String): String = stored
        override fun isSealed(stored: String): Boolean = false
    }

    /** Unseals only [FakeTokenSealer] output; plain values fail — proves a rewrite happened. */
    private object StrictSealer : TokenSealer {
        override fun seal(plain: String): String = "v1:fake:$plain"
        override fun unseal(stored: String): String? =
            if (stored.startsWith("v1:fake:")) stored.removePrefix("v1:fake:") else null
        override fun isSealed(stored: String): Boolean = stored.startsWith("v1:")
    }

    @AfterTest fun removeFakeSealer() {
        EditorPrefs.sealerForTest = null
    }

    @Test fun `git token round trips sealed and clears to empty`() = runBlocking {
        EditorPrefs.sealerForTest = FakeTokenSealer()
        val prefs = EditorPrefs(ctx)
        prefs.setGitToken("ghp_secret")
        assertEquals("ghp_secret", prefs.gitToken.first())
        prefs.setGitToken("")
        assertEquals("", prefs.gitToken.first())
    }

    @Test fun `legacy plaintext git token migrates to sealed on first read`() = runBlocking {
        EditorPrefs.sealerForTest = PassThroughSealer
        val prefs = EditorPrefs(ctx)
        prefs.setGitToken("legacy-pat")
        EditorPrefs.sealerForTest = FakeTokenSealer()
        assertEquals("legacy-pat", prefs.gitToken.first())
        // Migration rewrite is fire-and-forget from the Flow; poll with a sealer that
        // rejects plaintext outright — success proves the stored value is now sealed.
        EditorPrefs.sealerForTest = StrictSealer
        val deadline = System.currentTimeMillis() + 10_000
        var migrated = ""
        while (System.currentTimeMillis() < deadline) {
            migrated = prefs.gitToken.first()
            if (migrated == "legacy-pat") break
            Thread.sleep(50)
        }
        assertEquals("legacy-pat", migrated)
        EditorPrefs.sealerForTest = FakeTokenSealer()
        prefs.setGitToken("")
    }

    @Test fun `unsealable sealed git token reads as signed out`() = runBlocking {
        EditorPrefs.sealerForTest = PassThroughSealer
        val prefs = EditorPrefs(ctx)
        prefs.setGitToken("v1:foreign-sealed-value")
        EditorPrefs.sealerForTest = FakeTokenSealer()
        assertEquals("", prefs.gitToken.first())
        prefs.setGitToken("")
    }

    @Test fun `keystore sealer round trips with a software key and fails closed`() {
        val sealer = KeystoreTokenSealer("test_alias")
        sealer.secretKeyForTest = SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "AES")
        val sealed = sealer.seal("hello-token")!!
        assertTrue(sealer.isSealed(sealed))
        assertFalse(sealed.contains("hello-token"))
        assertEquals("hello-token", sealer.unseal(sealed))
        assertEquals("plain-legacy", sealer.unseal("plain-legacy"))
        assertFalse(sealer.isSealed("plain-legacy"))
        assertNull(sealer.unseal(sealed.dropLast(4) + "AAAA"))
    }
}
