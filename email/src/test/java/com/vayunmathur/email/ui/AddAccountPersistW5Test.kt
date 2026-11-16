package com.vayunmathur.email.ui

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
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

/** W5: testAndPersistAccount success/auth-failure/unreachable branches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddAccountPersistW5Test {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var server: FakeImapServer

    @BeforeTest
    fun setUp() {
        FakeAndroidKeyStore.install()
        FakeAndroidKeyStore.clear()
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        server = FakeImapServer(); server.start()
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
    }

    @AfterTest
    fun tearDown() {
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

    @Test
    fun persistSuccessStoresEncryptedPasswordAccount() = runBlocking {
        val error = testAndPersistAccount(
            context = context,
            providerId = "custom",
            email = "me@example.org",
            username = "login-user",
            password = "s3cret",
            imap = ServerConfig("127.0.0.1", server.port, false),
            smtp = ServerConfig("127.0.0.1", 2525, false),
        )
        assertNull(error, "persist should succeed against the fake server")
        val account = db.accountDao().getAccountByEmail("me@example.org")
        assertNotNull(account)
        assertEquals("login-user", account.username)
        assertEquals("password", account.authType)
        assertEquals("custom", account.provider)
        assertEquals(
            "s3cret",
            CredentialCrypto.decrypt(account.passwordEncrypted!!, account.passwordIv!!),
        )
    }

    @Test
    fun persistFailuresReturnMessagesAndStoreNothing() = runBlocking {
        server.failLogin = true
        val authError = testAndPersistAccount(
            context = context,
            providerId = "custom",
            email = "bad@example.org",
            username = "",
            password = "wrong",
            imap = ServerConfig("127.0.0.1", server.port, false),
            smtp = ServerConfig("127.0.0.1", 2525, false),
        )
        assertTrue(authError?.contains("Authentication failed") == true, "got $authError")
        assertNull(db.accountDao().getAccountByEmail("bad@example.org"))

        server.failLogin = false
        val unreachable = testAndPersistAccount(
            context = context,
            providerId = "custom",
            email = "down@example.org",
            username = "",
            password = "pw",
            imap = ServerConfig("127.0.0.1", 9, false),
            smtp = ServerConfig("127.0.0.1", 2525, false),
        )
        assertTrue(unreachable?.contains("Couldn't reach") == true, "got $unreachable")
        assertNull(db.accountDao().getAccountByEmail("down@example.org"))
    }
}
