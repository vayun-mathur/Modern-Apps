package com.vayunmathur.findfamily.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The v1 → v14 upgrade path, driven through Room's real migration machinery:
 * a v1 file is created with the exported v1 schema, seeded, then opened by the
 * current [FFDatabase]. Room validates the migrated schema against the entities,
 * so a migration whose SQL drifts from the entity definitions fails here.
 */
@RunWith(AndroidJUnit4::class)
class FFDatabaseMigrationTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "ff-migration-test.db"

    private fun createV1File() {
        ctx.deleteDatabase(dbName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        // Exported v1 schema (findfamily/schemas/.../1.json), verbatim.
        db.execSQL("CREATE TABLE IF NOT EXISTS `User` (`name` TEXT NOT NULL, `photo` TEXT, `locationName` TEXT NOT NULL, `sendingEnabled` INTEGER NOT NULL, `requestStatus` TEXT NOT NULL, `lastLocationChangeTime` INTEGER NOT NULL, `encryptionKey` TEXT, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `Waypoint` (`name` TEXT NOT NULL, `range` REAL NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `LocationValue` (`userid` INTEGER NOT NULL, `speed` REAL NOT NULL, `acc` REAL NOT NULL, `timestamp` INTEGER NOT NULL, `battery` REAL NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `TemporaryLink` (`name` TEXT NOT NULL, `key` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `deleteAt` INTEGER NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '421e8c59e4e6cace65161ff1cff99ebb')")
        db.execSQL(
            "INSERT INTO `User` (name, locationName, sendingEnabled, requestStatus, lastLocationChangeTime, id) " +
                "VALUES ('Ann', 'Home', 1, 'MUTUAL_CONNECTION', 1700000000, 7)"
        )
        db.execSQL("INSERT INTO `Waypoint` (name, `range`, id, lat, lon) VALUES ('Home', 150.0, 3, 10.0, 20.0)")
        db.execSQL(
            "INSERT INTO `LocationValue` (userid, speed, acc, timestamp, battery, id, lat, lon) " +
                "VALUES (7, 1.0, 5.0, 1700000000, 80.0, 11, 10.5, 20.5)"
        )
        // A pre-PQC RSA link: the 7→8 rebuild deliberately drops rows with no PQC bundle.
        db.execSQL(
            "INSERT INTO `TemporaryLink` (name, `key`, publicKey, deleteAt, id) " +
                "VALUES ('Old link', 'rsa-key', 'rsa-pub', 1899999999, 5)"
        )
        db.version = 1
        helper.close()
    }

    @Test fun v1UpgradesToCurrentWithDataIntact() = runBlocking {
        createV1File()
        val db = Room.databaseBuilder(ctx, FFDatabase::class.java, dbName)
            .addMigrations(*FFDatabase.migrations.toTypedArray())
            .allowMainThreadQueries()
            .build()
        try {
            val user = db.userDao().getById(7L)
            assertNotNull(user)
            assertEquals("Ann", user.name)
            assertEquals(UserKind.PERSON, user.kind)
            assertEquals(3L, db.waypointDao().getAll().single().id)
            // 11→12 backfill: reportedAt inherits timestamp, source defaults LIVE.
            val fix = db.locationValueDao().getByUseridFlow(7L).first().single()
            assertEquals(fix.timestamp, fix.reportedAt)
            assertEquals(LocationSource.LIVE, fix.source)
            // The RSA-only link was dropped by the PQC-only rebuild, not carried.
            assertTrue(db.temporaryLinkDao().getAll().isEmpty())
            // 12→13 created the no-show table; it starts empty and accepts rows.
            assertTrue(db.noShowAlertDao().getAll().isEmpty())
            val alertId = db.noShowAlertDao().upsert(
                NoShowAlert(
                    watchedUserId = 7L,
                    waypointId = 3L,
                    expectedAt = Instant.fromEpochSeconds(1_700_000_000L),
                    grace = 15.minutes,
                )
            )
            assertTrue(alertId > 0)
            assertEquals(1, db.noShowAlertDao().getDue(1_700_001_000L).size)
            assertEquals(1, db.noShowAlertDao().markFired(alertId))
            assertEquals(0, db.noShowAlertDao().markFired(alertId))
            assertEquals(1, db.noShowAlertDao().rearm(alertId))
        } finally {
            db.close()
            ctx.deleteDatabase(dbName)
        }
    }
}
