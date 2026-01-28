package com.vayunmathur.youpipe.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Subscription::class], version = 1)
abstract class SubscriptionDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
}
