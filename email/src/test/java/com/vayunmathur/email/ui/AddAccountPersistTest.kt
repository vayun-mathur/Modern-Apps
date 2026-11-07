package com.vayunmathur.email.ui

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.FakeAndroidKeyStore
import com.vayunmathur.email.data.CredentialCrypto
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.imap.FakeImapServer
import com.vayunmathur.email.platform.ServerConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [testAndPersistAccount]: the add-account submit path must prove the
 * server accepts the credentials before persisting anything, persist
 * the account with the password encrypted at rest, and translate
 * failures into the user-facing messages (auth vs unreachable) instead
 * of saving a dead account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddAccountPersistTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var server: FakeImapServer

    @BeforeTest
    fun open() {
        FakeAndroidKeyStore.install()
        FakeAndroidKeyStore.clear()
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        server = FakeImapServer(); server.start()
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
    }

    @AfterTest
    fun close() {
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
        server.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun imap() = ServerConfig("127.0.0.1", server.port, useSsl = false)
    private fun smtp() = ServerConfig("127.0.0.1", server.port, useSsl = false)

    @Test
    fun `reachable server with good credentials persists the account encrypted`() = runBlocking {
        val error = testAndPersistAccount(
            context, providerId = "custom", email = "me@x.com", username = "",
            password = "app-password-1", imap = imap(), smtp = smtp(),
        )
        assertNull(error)
        assertTrue(server.receivedCommands.any { it.contains("LIST") })
        val account = db.accountDao().getAccountByEmail("me@x.com")
        assertNotNull(account)
        assertEquals("custom", account.provider)
        assertEquals("password", account.authType)
        assertEquals("127.0.0.1", account.imapHost)
        assertEquals(server.port, account.imapPort)
        // Password is encrypted at rest and decrypts back with the stored IV.
        val cipher = account.passwordEncrypted
        val iv = account.passwordIv
        assertNotNull(cipher); assertNotNull(iv)
        assertEquals("app-password-1", CredentialCrypto.decrypt(cipher, iv))
    }

    @Test
    fun `explicit username is used for login and stored`() = runBlocking {
        val error = testAndPersistAccount(
            context, providerId = "custom", email = "me@x.com", username = "login-name",
            password = "pw", imap = imap(), smtp = smtp(),
        )
        assertNull(error)
        assertTrue(server.receivedCommands.any { it.contains("login-name", ignoreCase = true) }, server.receivedCommands.toString())
        assertEquals("login-name", db.accountDao().getAccountByEmail("me@x.com")?.username)
    }

    @Test
    fun `bad credentials report authentication failed and persist nothing`() = runBlocking {
        server.failLogin = true
        val error = testAndPersistAccount(
            context, providerId = "custom", email = "me@x.com", username = "",
            password = "wrong", imap = imap(), smtp = smtp(),
        )
        assertEquals("Authentication failed — check your email and app password.", error)
        assertNull(db.accountDao().getAccountByEmail("me@x.com"))
    }

    @Test
    fun `unreachable server reports could-not-reach and persists nothing`() = runBlocking {
        val error = testAndPersistAccount(
            context, providerId = "custom", email = "me@x.com", username = "",
            password = "pw",
            imap = ServerConfig("127.0.0.1", 1, useSsl = false), smtp = smtp(),
        )
        assertNotNull(error)
        assertTrue(error.startsWith("Couldn't reach 127.0.0.1:1"), error)
        assertNull(db.accountDao().getAccountByEmail("me@x.com"))
    }
}
