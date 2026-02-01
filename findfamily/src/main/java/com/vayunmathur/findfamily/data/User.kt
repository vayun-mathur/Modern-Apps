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
enum class RequestStatus {
    MUTUAL_CONNECTION,
    AWAITING_REQUEST,
    AWAITING_RESPONSE
}

@Serializable
@Entity
data class User(
    val name: String,
    val photo: String?,
    var locationName: String,
    val sendingEnabled: Boolean,
    val requestStatus: RequestStatus,
    val lastLocationChangeTime: Instant = Clock.System.now(),
    val deleteAt: Instant?,
    val encryptionKey: String? = null,

    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    override val position: Double = 0.0
): DatabaseItem<User>() {
    override fun withPosition(position: Double) = copy(position = position)
}

@Dao
interface UserDao: TrueDao<User> {
    @Query("SELECT * FROM user")
    override fun getAll(): Flow<List<User>>

    @Query("DELETE FROM user")
    override suspend fun deleteAll()
}