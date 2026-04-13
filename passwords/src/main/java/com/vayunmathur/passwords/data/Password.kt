package com.vayunmathur.passwords.data
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Password(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String = "",
    val userId: String = "",
    val password: String = "",
    val totpSecret: String? = null,
    val websites: List<String> = emptyList()
): DatabaseItem
