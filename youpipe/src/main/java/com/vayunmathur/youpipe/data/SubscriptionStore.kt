package com.vayunmathur.youpipe.data

import kotlinx.coroutines.flow.Flow

/** Subscription and subscription-video persistence, split out of [SubscriptionRepository]. */
class SubscriptionStore(
    private val subscriptionDao: SubscriptionDao,
    private val subscriptionVideoDao: SubscriptionVideoDao,
) {
    /** The subscription with [id] as a flow. */
    fun subscriptionById(id: Long): Flow<Subscription?> = subscriptionDao.getByIdFlow(id)

    /** The subscription video with [id] as a flow. */
    fun subscriptionVideoById(id: Long): Flow<SubscriptionVideo?> = subscriptionVideoDao.getByIdFlow(id)

    /** All subscriptions. */
    suspend fun getAllSubscriptions(): List<Subscription> = subscriptionDao.getAll()

    /** Inserts or replaces one subscription. */
    suspend fun upsertSubscription(value: Subscription): Long = subscriptionDao.upsert(value)

    /** Inserts or replaces subscriptions. */
    suspend fun upsertSubscriptions(values: List<Subscription>) = subscriptionDao.upsertAll(values)

    /** Deletes one subscription. */
    suspend fun deleteSubscription(value: Subscription): Int = subscriptionDao.delete(value)

    /** Deletes every subscription. */
    suspend fun clearAllSubscriptions() = subscriptionDao.clearAll()

    /** All subscription videos. */
    suspend fun getAllSubscriptionVideos(): List<SubscriptionVideo> = subscriptionVideoDao.getAll()

    /** Inserts or replaces subscription videos. */
    suspend fun upsertSubscriptionVideos(values: List<SubscriptionVideo>) =
        subscriptionVideoDao.upsertAll(values)
}
