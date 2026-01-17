package com.vayunmathur.passwords.data

import androidx.room.*
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow
import com.vayunmathur.passwords.Password
import kotlin.reflect.KClass

@Dao
interface PasswordDao: TrueDao<Password> {
    @Query("SELECT * FROM passwords ORDER BY name")
    override fun getAll(): Flow<List<Password>>
}
