package com.vayunmathur.findfamily.data

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
/** A saved place whose entry and exit drive notifications and sharing toggles. */
data class Waypoint(
    val name: String,
    val range: Double,
    @Embedded val coord: Coord,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem {
    /** Shared placeholder values for [Waypoint]. */
    companion object {
        val NEW_WAYPOINT = Waypoint("", 100.0, Coord(0.0, 0.0))
    }
}
