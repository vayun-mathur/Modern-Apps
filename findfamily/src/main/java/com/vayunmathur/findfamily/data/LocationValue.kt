package com.vayunmathur.findfamily.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
@Entity
data class LocationValue(
    val userid: Long,
    @Embedded val coord: Coord,
    val speed: Float,
    val acc: Float,
    val timestamp: Instant,
    val battery: Float,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem