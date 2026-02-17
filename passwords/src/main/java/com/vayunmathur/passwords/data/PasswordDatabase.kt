package com.vayunmathur.passwords.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.passwords.Password

@Dao
interface PasswordDao: TrueDao<Password>

@Database(entities = [Password::class], version = 2)
@TypeConverters(Converters::class)
abstract class PasswordDatabase : RoomDatabase() {
    abstract fun passwordDao(): PasswordDao
}
