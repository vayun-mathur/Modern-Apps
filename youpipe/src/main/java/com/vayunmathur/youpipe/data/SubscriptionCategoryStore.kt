package com.vayunmathur.youpipe.data

import kotlinx.coroutines.flow.Flow

/**
 * Subscription-category writes, split from [SubscriptionRepository] (TooManyFunctions cap).
 *
 * Same package and module; behavior identical. Access via
 * [SubscriptionRepository.categoryStore].
 */
class SubscriptionCategoryStore(
    private val subscriptionCategoryDao: SubscriptionCategoryDao,
) {
    /** The subscription category with [id] as a flow. */
    fun subscriptionCategoryById(id: Long): Flow<SubscriptionCategory?> =
        subscriptionCategoryDao.getByIdFlow(id)

    suspend fun replaceCategory(originalCategoryName: String?, categoryName: String, ids: List<Long>) =
        subscriptionCategoryDao.replaceCategory(originalCategoryName, categoryName, ids)

    suspend fun deleteCategory(categoryName: String) = subscriptionCategoryDao.deleteCategory(categoryName)

    suspend fun upsertSubscriptionCategories(items: List<SubscriptionCategory>) =
        subscriptionCategoryDao.upsertAll(items)
}
