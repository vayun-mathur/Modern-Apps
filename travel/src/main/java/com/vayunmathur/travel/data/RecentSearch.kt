package com.vayunmathur.travel.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Recent searches are flights-only now, but the column is kept for history. */
enum class Vertical { FLIGHTS }

/**
 * A search the user has run, kept so the Home screen can offer one-tap
 * re-runs. All fields needed to rebuild the query are stored as columns;
 * [label] is a pre-rendered human summary for the chip.
 */
@Entity
data class RecentSearch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vertical: String,
    val label: String,
    val origin: String? = null,
    val destination: String? = null,
    val depart: String? = null,
    val returnDate: String? = null,
    val adults: Int = 1,
    val cabin: String = "economy",
    val createdAt: Long = System.currentTimeMillis(),
)
