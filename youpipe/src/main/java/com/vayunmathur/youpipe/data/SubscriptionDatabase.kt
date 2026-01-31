package com.vayunmathur.youpipe.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.DefaultConverters

@TypeConverters(DefaultConverters::class)
@Database(entities = [Subscription::class, SubscriptionVideo::class, HistoryVideo::class], version = 1)
abstract class SubscriptionDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun subscriptionVideoDao(): SubscriptionVideoDao
    abstract fun historyVideoDao(): HistoryVideoDao
}
