package com.vayunmathur.music.data
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.DatabaseViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Album(
    @PrimaryKey(autoGenerate = true) override val id: Long,
    val name: String,
    val uri: String
): DatabaseItem {
    @Composable
    fun artistString(viewModel: DatabaseViewModel): String {
        return remember {
            val artistIDs = runBlocking {
                viewModel.getMatches<Album, Artist>(id)
            }
            if(artistIDs.size > 2) {
                return@remember "Various Artists"
            }
            val artists = viewModel.data<Artist>().value
            artistIDs.joinToString { id -> artists.find { it.id == id }!!.name }
        }
    }
}