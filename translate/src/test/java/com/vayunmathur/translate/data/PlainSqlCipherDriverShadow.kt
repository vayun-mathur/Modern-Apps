package com.vayunmathur.translate.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric stand-in for the SQLCipher Room driver.
 *
 * The production driver opens an encrypted database through the Android-only
 * `libsqlcipher.so`, which cannot load in a host JVM. Repository and UI tests
 * still need Room's real query/transaction behaviour, so on the JVM this
 * shadow preserves the Room [androidx.sqlite.SQLiteDriver] contract while
 * delegating storage to Android's plain SQLite implementation (which
 * Robolectric does implement). Encryption itself is an on-device concern and
 * is not what these tests assert.
 */
@Implements(SQLCipherDriver::class)
class PlainSqlCipherDriverShadow {
    private val delegate = AndroidSQLiteDriver()

    @Implementation
    fun open(fileName: String): SQLiteConnection = delegate.open(fileName)
}
