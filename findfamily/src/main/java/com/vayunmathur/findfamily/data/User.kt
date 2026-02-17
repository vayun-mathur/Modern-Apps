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
    val encryptionKey: String? = null,

    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem {
    companion object {
        val EMPTY = User(" ", null, "Unnamed Location", true, RequestStatus.MUTUAL_CONNECTION, Clock.System.now(), null)
    }
}

@Dao
interface UserDao: TrueDao<User> {
    @Query("SELECT * FROM user")
    override fun getAll(): Flow<List<User>>

    @Query("DELETE FROM user")
    override suspend fun deleteAll()
}