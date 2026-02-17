package com.vayunmathur.youpipe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
@Entity(foreignKeys = [
    ForeignKey(entity = Subscription::class, parentColumns = ["id"], childColumns = ["channelID"], onDelete = ForeignKey.CASCADE)
])
data class SubscriptionVideo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0, // video id
    val name: String,
    val duration: Long,
    val views: Long,
    val uploadDate: Instant,
    val thumbnailURL: String,
    val author: String,
    @ColumnInfo(index = true)
    val channelID: Long
): DatabaseItem