package com.vayunmathur.news

import android.content.Context
import androidx.room3.Room
import com.vayunmathur.library.util.databases
import com.vayunmathur.news.data.NewsDatabase

/** Binds the news DB to an in-memory instance (avoids SQLCipher on JVM). */
fun seedNewsDb(context: Context) {
    synchronized(databases) {
        if (databases[NewsDatabase::class] !is NewsDatabase) {
            databases[NewsDatabase::class] = Room.inMemoryDatabaseBuilder(
                context.applicationContext, NewsDatabase::class.java,
            ).allowMainThreadQueries().build()
        }
    }
}
