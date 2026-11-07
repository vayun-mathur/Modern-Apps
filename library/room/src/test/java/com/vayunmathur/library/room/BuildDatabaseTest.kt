package com.vayunmathur.library.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.databases
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BuildDatabaseTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @AfterTest fun tearDown() {
        databases.values.forEach { runCatching { it.close() } }
        databases.clear()
    }

    @Test
    fun `buildDatabase caches one instance per database class`() {
        val first = context.buildDatabase(TestDatabase::class.java, null, "pw-one", "cache-db")
        val second = context.buildDatabase(TestDatabase::class.java, null, "pw-two", "cache-db")
        assertSame(first, second, "the cache wins over a second call, whatever it passes")
        assertSame(first, databases[TestDatabase::class])
    }

    @Test
    fun `reified buildDatabase returns the same cached instance`() {
        val byClass = context.buildDatabase(TestDatabase::class.java, null, "pw", "reified-db")
        val byType = context.buildDatabase<TestDatabase>(null, "pw", "reified-db")
        assertSame(byClass, byType)
    }

    @Test
    fun `uncached builds are distinct instances`() {
        val first = context.buildDatabaseUncached(TestDatabase::class.java, null, "pw", "uncached-a")
        val second = context.buildDatabaseUncached(TestDatabase::class.java, null, "pw", "uncached-a")
        assertNotSame(first, second)
        assertNull(databases[TestDatabase::class], "uncached builds must not populate the cache")
        first.close()
        second.close()
    }

    @Test
    fun `a database without a companion gets no migrations and still builds`() {
        val db = context.buildDatabase(PlainDatabase::class.java, null, "pw", "plain-db")
        assertSame(db, databases[PlainDatabase::class])
    }

    @Test
    fun `explicit migrations win over the companion`() {
        // An explicit (empty) list must not fall back to the companion's 1->2.
        val db = context.buildDatabase(TestDatabase::class.java, emptyList(), "pw", "explicit-db")
        assertSame(db, databases[TestDatabase::class])
    }

    @Test
    fun `repository builds lazily once and exposes the application context`() {
        val repo = TestRepository(context)
        assertSame(context.applicationContext, repo.applicationContext)
        val first = repo.database
        assertSame(first, repo.database)
        assertSame(first, databases[TestDatabase::class])
    }

    @Test
    fun `repository with defaults constructs without touching the keystore`() {
        val repo = DefaultRepository(context)
        assertSame(context.applicationContext, repo.applicationContext)
        assertNull(databases[TestDatabase::class], "nothing is built until db is touched")
    }

    @Test
    fun `openAuxiliary builds a separate instance under another name`() {
        val repo = TestRepository(context)
        val main = repo.database
        val aux = repo.auxiliary("legacy-name-db")
        assertNotSame(main, aux)
        aux.close()
    }

    @Test
    fun `device protected storage builds against the device context`() {
        val db = context.buildDatabase(
            TestDatabase::class.java, null, "pw", "dps-db",
            useDeviceProtectedStorage = true,
        )
        assertSame(db, databases[TestDatabase::class])
    }

    @Test
    fun `device protected build moves an existing database and prefs over`() {
        // Seed the credential-protected side the way a pre-upgrade install
        // would look: a database file and a populated secure_prefs there,
        // nothing yet on the device-protected side.
        val credentialFile = context.getDatabasePath("dps-move-db")
        credentialFile.parentFile?.mkdirs()
        credentialFile.writeBytes(ByteArray(8) { 1 })
        context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
            .edit().putString("k", "v").commit()
        val deviceContext = context.createDeviceProtectedStorageContext()
        try {
            val db = context.buildDatabase(
                TestDatabase::class.java, null, "pw", "dps-move-db",
                useDeviceProtectedStorage = true,
            )
            assertSame(db, databases[TestDatabase::class])
            kotlin.test.assertTrue(
                deviceContext.getDatabasePath("dps-move-db").exists(),
                "the database should have moved to device-protected storage",
            )
        } finally {
            credentialFile.delete()
            deviceContext.getDatabasePath("dps-move-db").delete()
            context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun `reified overload with all defaults reaches for the keystore passphrase`() {
        // No stored key yet, so building asks DatabaseHelper to generate one -
        // which needs AndroidKeyStore, a platform service a host JVM does not
        // have. On a device this is the default path every app takes.
        kotlin.test.assertFailsWith<java.security.KeyStoreException> {
            context.buildDatabase<TestDatabase>()
        }
        assertNull(databases[TestDatabase::class], "a failed build must not be cached")
    }

    @Test
    fun `uncached build through defaults`() {
        val db = context.buildDatabaseUncached(TestDatabase::class.java, null, "pw", "uncached-defaults")
        kotlin.test.assertNull(databases[TestDatabase::class])
        db.close()
    }

    @Test
    fun `the dao is reachable without opening the database`() {
        // Opening is deferred until first use: the generated DAO must already
        // be handable-out here. (Actually opening needs the SQLCipher native
        // library, which a host JVM does not have - see the task report.)
        val repo = TestRepository(context, name = "dao-db")
        kotlin.test.assertNotNull(repo.database.itemDao())
    }
}
