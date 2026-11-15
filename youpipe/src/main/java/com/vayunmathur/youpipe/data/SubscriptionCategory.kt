package com.vayunmathur.youpipe.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Subscription::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionID"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
/** Immutable value describing subscription category. */
data class SubscriptionCategory(
    @ColumnInfo(index = true)
    val subscriptionID: Long,
    val category: String,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem

/** Contract for subscription category dao. */
@Dao
interface SubscriptionCategoryDao {
    @Query("SELECT * FROM SubscriptionCategory")
    fun getAllFlow(): Flow<List<SubscriptionCategory>>

    @Query("SELECT * FROM SubscriptionCategory WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<SubscriptionCategory?>

    @Query("DELETE FROM SubscriptionCategory WHERE category = :categoryName")
    suspend fun deleteCategory(categoryName: String)

    @Upsert
    suspend fun upsertAll(items: List<SubscriptionCategory>)

    @Transaction
    suspend fun replaceCategory(originalCategoryName: String?, categoryName: String, map: List<Long>) {
        if(originalCategoryName != null) deleteCategory(originalCategoryName)
        upsertAll(map.map { id -> SubscriptionCategory(id, categoryName) })
    }
}
