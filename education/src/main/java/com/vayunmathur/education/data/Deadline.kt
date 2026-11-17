package com.vayunmathur.education.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A parent-set target date for a module (course / unit / lesson). Surfaces in
 * the child's shell as a gentle, band-appropriate nudge — never punitive.
 *
 * [moduleType] is a [com.vayunmathur.education.domain.ModuleType] name and
 * [moduleId] the content id; storing the type as a tag keeps deadlines valid
 * across content-pack updates.
 */
@Serializable
@Entity
data class Deadline(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleType: String,
    val moduleId: String,
    val dueEpochDay: Long,
    val note: String = "",
)
