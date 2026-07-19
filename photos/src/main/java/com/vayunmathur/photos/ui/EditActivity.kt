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
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.photos.data.PhotoDao
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.util.PhotoEditViewModel
import com.vayunmathur.photos.util.PhotoEditViewModelFactory

class EditActivity : ComponentActivity() {
    private lateinit var photoDao: PhotoDao
    private val photoEditViewModel: PhotoEditViewModel by viewModels {
        PhotoEditViewModelFactory(application, photoDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = buildDatabase<PhotoDatabase>()
        photoDao = db.photoDao()

        setContent {
            DynamicTheme {
                var photoId by remember { mutableLongStateOf(intent.getLongExtra("photo_id", -1L)) }
                var photoUri by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    if (photoId == -1L && (intent.action == Intent.ACTION_EDIT || intent.action == Intent.ACTION_VIEW)) {
                        intent.data?.let { uri ->
                            val uriString = uri.toString()
                            val existing = photoDao.getByUri(uriString)
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
                    EditNavigation(photoEditViewModel, photoId, photoUri)
                }
            }
        }
    }
}

@Composable
fun EditNavigation(
    photoEditViewModel: PhotoEditViewModel,
    photoId: Long,
    photoUri: String?,
) {
    val backStack = rememberNavBackStack<EditRoute>(EditRoute.EditPhoto(photoId, photoUri))
    MainNavigation(backStack) {
        entry<EditRoute.EditPhoto> {
            EditPhotoPage(backStack, photoEditViewModel, it.id, it.uri)
        }

        entry<EditRoute.DrawingSettings>(DialogPage()) {
            DrawingSettingsPage(backStack, it.tool, it.currentColor, it.currentThickness, it.currentOpacity)
        }
    }
}
