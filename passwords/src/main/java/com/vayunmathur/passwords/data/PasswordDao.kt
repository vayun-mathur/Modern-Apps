package com.vayunmathur.passwords.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.vayunmathur.passwords.Password

@Dao
interface PasswordDao {
    @Query("SELECT * FROM passwords ORDER BY name")
    fun getAll(): Flow<List<Password>>

    @Query("SELECT * FROM passwords WHERE id = :id")
    fun getById(id: Long): Flow<Password?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(password: Password): Long

    @Update
    suspend fun update(password: Password): Int

    @Delete
    suspend fun delete(password: Password): Int
}
