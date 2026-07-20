package com.vayunmathur.travel.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An offer the user has saved. Keyed by [bookingUrl] because that uniquely
 * identifies a specific offer across all verticals and makes toggling a
 * favorite a simple upsert/delete-by-key.
 */
@Entity
data class Favorite(
    @PrimaryKey val bookingUrl: String,
    val vertical: String,
    val title: String,
    val subtitle: String = "",
    val price: Double = 0.0,
    val currency: String = "usd",
    val createdAt: Long = System.currentTimeMillis(),
)
