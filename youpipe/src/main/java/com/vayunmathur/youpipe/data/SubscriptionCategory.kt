package com.vayunmathur.youpipe.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(foreignKeys = [
    ForeignKey(entity = Subscription::class, parentColumns = ["id"], childColumns = ["subscriptionID"], onDelete = ForeignKey.CASCADE)
    ])
data class SubscriptionCategory(
    @ColumnInfo(index = true)
    val subscriptionID: Long,
    val category: String,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem

@Dao
interface SubscriptionCategoryDao: TrueDao<SubscriptionCategory> {
    @Query("SELECT * FROM SubscriptionCategory")
    override fun getAll(): Flow<List<SubscriptionCategory>>

    @Query("DELETE FROM SubscriptionCategory")
    override suspend fun deleteAll()

    @Query("DELETE FROM SubscriptionCategory WHERE category = :categoryName")
    suspend fun deleteCategory(categoryName: String)

    @Transaction
    suspend fun replaceCategory(originalCategoryName: String?, categoryName: String, map: List<Long>) {
        if(originalCategoryName != null) deleteCategory(originalCategoryName)
        upsertAll(map.mapIndexed { index, id -> SubscriptionCategory(id, categoryName) })
    }
}