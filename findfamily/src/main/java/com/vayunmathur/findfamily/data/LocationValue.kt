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
): DatabaseItem {
    fun toCompatible(): LocationValueCompatible {
        return LocationValueCompatible(
            userid = userid.toULong(),
            coord = coord,
            speed = speed,
            acc = acc,
            timestamp = timestamp.toEpochMilliseconds(),
            battery = battery,
            sleep = false,
            id = id.toULong()
        )
    }
}

@Serializable
data class LocationValueCompatible(
    val id: ULong = 0uL,
    val userid: ULong,
    val coord: Coord,
    val speed: Float,
    val acc: Float,
    val timestamp: Long,
    val battery: Float,
    val sleep: Boolean? = null
) {
    fun toLocationValue(): LocationValue {
        return LocationValue(
            userid = userid.toLong(),
            coord = coord,
            speed = speed,
            acc = acc,
            timestamp = Instant.fromEpochMilliseconds(timestamp),
            battery = battery,
            id = id.toLong()
        )
    }
}