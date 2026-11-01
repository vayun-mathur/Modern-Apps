package com.vayunmathur.shop.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

class ProfileRepository private constructor(context: Context) :
    RoomRepository<ProfileDatabase>(context, ProfileDatabase::class, PROFILE_DB_NAME) {

    val profile: Flow<ShopProfile?> get() = db.profileDao().observe()

    suspend fun save(profile: ShopProfile) = db.profileDao().upsert(profile.copy(id = 0))

    companion object {
        @Volatile private var instance: ProfileRepository? = null
        fun get(context: Context): ProfileRepository =
            instance ?: synchronized(this) {
                instance ?: ProfileRepository(context).also { instance = it }
            }
    }
}
