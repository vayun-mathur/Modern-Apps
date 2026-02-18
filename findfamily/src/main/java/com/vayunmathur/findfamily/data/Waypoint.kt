package com.vayunmathur.findfamily.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Waypoint(
    val name: String,
    val range: Double,
    @Embedded val coord: Coord,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem {
    companion object {
        val NEW_WAYPOINT = Waypoint("", 100.0, Coord(0.0, 0.0))
    }
}