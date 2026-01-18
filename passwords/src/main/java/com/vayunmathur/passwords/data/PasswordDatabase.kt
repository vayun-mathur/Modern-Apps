package com.vayunmathur.passwords.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.passwords.Password

@Database(entities = [Password::class], version = 2)
@TypeConverters(Converters::class)
abstract class PasswordDatabase : RoomDatabase() {
    abstract fun passwordDao(): PasswordDao
}
