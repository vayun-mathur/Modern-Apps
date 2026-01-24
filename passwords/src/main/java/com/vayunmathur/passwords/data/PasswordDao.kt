package com.vayunmathur.passwords.data

import androidx.room.Dao
import androidx.room.Query
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.passwords.Password
import kotlinx.coroutines.flow.Flow

@Dao
interface PasswordDao: TrueDao<Password> {
    @Query("SELECT * FROM passwords ORDER BY name")
    override fun getAll(): Flow<List<Password>>
}
