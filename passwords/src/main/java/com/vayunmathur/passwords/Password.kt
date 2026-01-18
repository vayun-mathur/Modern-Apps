package com.vayunmathur.passwords

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "passwords")
data class Password(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String = "",
    val userId: String = "",
    val password: String = "",
    val totpSecret: String? = null,
    val websites: List<String> = emptyList(),
    override val position: Double = 0.0
): DatabaseItem<Password>() {
    override fun withPosition(position: Double) = copy(position = position)
}
