package com.vayunmathur.findfamily.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant


@Serializable
@Entity
data class TemporaryLink(
    val name: String,
    val key: String,
    val publicKey: String,
    val deleteAt: Instant,

    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
): DatabaseItem