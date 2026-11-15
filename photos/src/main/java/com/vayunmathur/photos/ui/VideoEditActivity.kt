package com.vayunmathur.photos.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.util.VideoEditViewModel
import com.vayunmathur.photos.util.VideoEditViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Video Edit Activity: a component activity implementation for video edit activity. */
class VideoEditActivity : ComponentActivity() {
    private val videoEditViewModel: VideoEditViewModel by viewModels {
        VideoEditViewModelFactory(application, PhotosRepository.get(application))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DynamicTheme {
                var photoId by remember { mutableLongStateOf(intent.getLongExtra("photo_id", -1L)) }
                var photoUri by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    if (photoId == -1L && (intent.action == Intent.ACTION_EDIT || intent.action == Intent.ACTION_VIEW)) {
                        intent.data?.let { uri ->
                            val uriString = uri.toString()
                            val existing = withContext(Dispatchers.IO) { PhotosRepository.get(applicationContext).getByUri(uriString) }
                            if (existing.isNotEmpty()) {
                                photoId = existing.first().id
                            } else {
                                photoId = 0L
                                photoUri = uriString
                            }
                        } ?: finish()
                    } else if (photoId == -1L) {
                        finish()
                    }
                }

                if (photoId != -1L || photoUri != null) {
                    VideoEditNavigation(videoEditViewModel, photoId, photoUri)
                }
            }
        }
    }
}

@Composable
fun VideoEditNavigation(
    videoEditViewModel: VideoEditViewModel,
    photoId: Long,
    photoUri: String?,
) {
    val backStack = rememberNavBackStack<VideoEditRoute>(VideoEditRoute.EditVideo(photoId, photoUri))
    MainNavigation(backStack) {
        entry<VideoEditRoute.EditVideo>(metadata = FullscreenPage()) {
            VideoEditPage(videoEditViewModel, it.id, it.uri)
        }
    }
}
