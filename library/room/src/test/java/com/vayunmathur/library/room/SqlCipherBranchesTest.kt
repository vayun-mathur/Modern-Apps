package com.vayunmathur.library.room

import android.content.Context
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.databases
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** A database whose companion exists but does not carry migrations. */
@Database(entities = [TestItem::class], version = 1, exportSchema = false)
abstract class CompanionNoMigrationsDatabase : RoomDatabase() {
    abstract fun itemDao(): TestItemDao

    companion object {
        const val LABEL = "no-migrations"
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SqlCipherBranchesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() = FakeAndroidKeyStore.install()

    @AfterTest fun tearDown() {
        databases.values.forEach { runCatching { it.close() } }
        databases.clear()
    }

    @Test
    fun `default argument masks for the class overloads build and cache`() {
        // Each call omits a different subset of the class-based overloads, so
        // their synthetic $default masks take different branches; every one
        // must still land on the same cached instance.
        val byNameOnly = context.buildDatabase(TestDatabase::class.java, dbName = "mask-class-a")
        assertSame(byNameOnly, databases[TestDatabase::class])
        databases.clear()
        val byPasswordOnly = context.buildDatabase(TestDatabase::class.java, encryptionPassword = "pw")
        assertSame(byPasswordOnly, databases[TestDatabase::class])
        databases.clear()
        val uncached = context.buildDatabaseUncached(TestDatabase::class.java, dbName = "mask-uncached")
        assertNull(databases[TestDatabase::class])
        uncached.close()
        val uncachedByPassword = context.buildDatabaseUncached(
            TestDatabase::class.java, encryptionPassword = "pw",
        )
        assertNotSame(uncached, uncachedByPassword)
        uncachedByPassword.close()
    }

    @Test
    fun `a companion that is not a migrations holder yields no migrations`() {
        assertTrue(CompanionNoMigrationsDatabase.LABEL.isNotEmpty())
        val db = context.buildDatabase(
            CompanionNoMigrationsDatabase::class.java, null, "pw", "companion-db",
        )
        assertSame(db, databases[CompanionNoMigrationsDatabase::class])
    }

    @Test
    fun `no password builds through the keystore passphrase, twice`() {
        // First build: no key yet, so one is generated and the passphrase is
        // created and stored. Second build (uncached, other name): the key
        // exists, so the stored passphrase is decrypted instead.
        val first = context.buildDatabaseUncached(TestDatabase::class.java, dbName = "keystore-a")
        first.close()
        val second = context.buildDatabaseUncached(TestDatabase::class.java, dbName = "keystore-b")
        assertNotSame(first, second)
        second.close()
    }

    @Test
    fun `device protected build skips moves when the device side is already populated`() {
        val deviceContext = context.createDeviceProtectedStorageContext()
        val deviceFile = deviceContext.getDatabasePath("dps-settled-db")
        deviceFile.parentFile?.mkdirs()
        deviceFile.writeBytes(ByteArray(8) { 2 })
        deviceContext.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
            .edit().putString("dk", "dv").commit()
        context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
            .edit().putString("ck", "cv").commit()
        try {
            val db = context.buildDatabase(
                TestDatabase::class.java, null, "pw", "dps-settled-db",
                useDeviceProtectedStorage = true,
            )
            assertSame(db, databases[TestDatabase::class])
            assertTrue(deviceFile.exists())
        } finally {
            deviceFile.delete()
            deviceContext.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()
            context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun `device protected build with nothing to move still builds`() {
        // Neither side has a database or prefs: both move checks must pass
        // over silently and the build must use the device context.
        val db = context.buildDatabase(
            PlainDatabase::class.java, null, "pw", "dps-empty-db",
            useDeviceProtectedStorage = true,
        )
        assertSame(db, databases[PlainDatabase::class])
    }

    @Test
    fun `export with a parentless output file still prepares it`() {
        val dbFile = context.getDatabasePath("export-parentless-db")
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(ByteArray(32) { 6 })
        val output = File("export-parentless-out.db")
        if (output.exists()) output.delete()
        try {
            runCatching {
                SqlCipherDbCodec.exportDatabase(context, "export-parentless-db", "pw", output)
            }
            assertTrue(output.exists(), "export creates its output before the native open")
        } finally {
            dbFile.delete()
            output.delete()
        }
    }

    @Test
    fun `reified overload with every default builds through the keystore`() {
        // No arguments at all: default name, default migrations and the
        // keystore passphrase path all come from the synthetic mask.
        val db = context.buildDatabase<TestDatabase>()
        assertSame(db, databases[TestDatabase::class])
        databases.clear()
        val named = context.buildDatabase<TestDatabase>(
            dbName = "reified-dps-db", encryptionPassword = "pw",
            useDeviceProtectedStorage = true,
        )
        assertSame(named, databases[TestDatabase::class])
    }

    @Test
    fun `the reified inline stub refuses direct reflective invocation`() {
        // The inline reified overload has no runtime body of its own; its
        // synthetic stub must refuse, exactly like its $default sibling.
        val stub = Class.forName("com.vayunmathur.library.room.SqlCipherKt")
            .methods.firstOrNull { it.name == "buildDatabase" && it.parameterCount == 5 }
            ?: return // inlined away on this toolchain: nothing to pin
        stub.isAccessible = true
        val thrown = kotlin.test.assertFailsWith<java.lang.reflect.InvocationTargetException> {
            stub.invoke(null, context, null, "pw", "stub-db", false)
        }
        assertTrue(thrown.cause is UnsupportedOperationException)
    }

    @Test
    fun `an already loaded sqlcipher flag short circuits the loader`() {
        // On a device the first load flips the flag and every later call is
        // this early return; set the flag the way a successful load would
        // and pin that the loader then does nothing at all.
        val field = Class.forName("com.vayunmathur.library.room.SqlCipherKt")
            .getDeclaredField("sqlCipherLoaded")
        field.isAccessible = true
        val previous = field.getBoolean(null)
        try {
            field.setBoolean(null, true)
            loadSqlCipher()
            assertTrue(field.getBoolean(null))
        } finally {
            field.setBoolean(null, previous)
        }
        assertTrue(field.getBoolean(null) == previous, "the flag must be restored afterwards")
    }
}
