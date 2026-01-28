package com.vayunmathur.youpipe.data

import androidx.room.Dao
import androidx.room.Query
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao: TrueDao<Subscription> {
    @Query("SELECT * FROM Subscription")
    override fun getAll(): Flow<List<Subscription>>
    @Query("DELETE FROM Subscription")
    override suspend fun deleteAll()
}