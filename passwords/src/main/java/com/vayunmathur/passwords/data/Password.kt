package com.vayunmathur.passwords.data
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

/** Immutable value describing password. */
@Serializable
@Entity
data class Password(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val note: String = "",
    val totpSecret: String? = null,
    val websites: List<String> = emptyList(),
    val syncId: String = newSyncId(),
    val updatedAt: Long = System.currentTimeMillis(),
): DatabaseItem
