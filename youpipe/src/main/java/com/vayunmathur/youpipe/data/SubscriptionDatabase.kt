package com.vayunmathur.youpipe.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.TrueDao


@Dao
interface HistoryVideoDao: TrueDao<HistoryVideo>

@Dao
interface SubscriptionDao: TrueDao<Subscription>

@Dao
interface SubscriptionVideoDao: TrueDao<SubscriptionVideo>

@TypeConverters(DefaultConverters::class)
@Database(entities = [Subscription::class, SubscriptionVideo::class, HistoryVideo::class, SubscriptionCategory::class], version = 1)
abstract class SubscriptionDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun subscriptionVideoDao(): SubscriptionVideoDao
    abstract fun historyVideoDao(): HistoryVideoDao
    abstract fun subscriptionCategoryDao(): SubscriptionCategoryDao
}
