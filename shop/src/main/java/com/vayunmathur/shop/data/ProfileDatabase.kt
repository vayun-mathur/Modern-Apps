package com.vayunmathur.shop.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

const val PROFILE_DB_NAME = "shop-profile-db"

/** Contract for profile dao. */
@Dao
interface ProfileDao {
    @Query("SELECT * FROM ShopProfile WHERE id = 0")
    fun observe(): Flow<ShopProfile?>

    @Upsert
    suspend fun upsert(profile: ShopProfile)
}

/** Profile Database: a room database implementation for profile database. */
@Database(entities = [ShopProfile::class], version = 1, exportSchema = true)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
}
