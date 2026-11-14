package com.vayunmathur.weather.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations

const val DB_NAME = "weather-db"

/** Backup config shared by [com.vayunmathur.weather.platform.AppBackupAgent]. */
fun weatherDbConfigs(context: Context): List<Pair<String, String>> =
    listOf(DB_NAME to DatabaseHelper(context).getPassphrase())

/** v2 adds [WeatherCache.airQualityJson] so air quality is cached alongside
 *  the forecast under one timestamp. The column is nullable; existing rows
 *  get NULL until their next refresh. */
val MIGRATION_1_2 = Migration(1, 2) {
    it.execSQL("ALTER TABLE WeatherCache ADD COLUMN airQualityJson TEXT")
}

@Database(
    entities = [SavedLocation::class, WeatherCache::class],
    version = 2,
    exportSchema = false,
)
/** Weather Database: a room database implementation for weather database. */
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun weatherDao(): WeatherDao

    /** Shared constants and helpers for WeatherDatabase. */
    companion object : DatabaseMigrations {
        override val migrations: List<Migration> = listOf(MIGRATION_1_2)
    }
}
