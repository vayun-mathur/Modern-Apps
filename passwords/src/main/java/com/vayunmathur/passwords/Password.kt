package com.vayunmathur.passwords

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "passwords")
data class Password(
    @PrimaryKey(autoGenerate = true) val id: Long? = null,
    val name: String = "",
    val userId: String = "",
    val password: String = "",
    val totpSecret: String? = null,
    val websites: List<String> = emptyList()
)
