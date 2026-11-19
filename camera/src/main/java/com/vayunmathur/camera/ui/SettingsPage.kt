package com.vayunmathur.camera.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.platform.AudioInputSource
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.CodecSupport
import com.vayunmathur.camera.platform.MotionPhotoDuration
import com.vayunmathur.camera.data.SafDocuments
import com.vayunmathur.camera.data.SaveTarget
import com.vayunmathur.camera.platform.VideoCodec
import com.vayunmathur.camera.data.clearSaveTreeUri
import com.vayunmathur.camera.data.setAudioInputSource
import com.vayunmathur.camera.data.setEdgeSharpeningEnabled
import com.vayunmathur.camera.data.setLocationEnabled
import com.vayunmathur.camera.data.setMirrorFront
import com.vayunmathur.camera.data.setMotionPhotoDuration
import com.vayunmathur.camera.data.setNoiseReductionEnabled
import com.vayunmathur.camera.data.setSaveTreeUri
import com.vayunmathur.camera.data.setVideoCodec
import com.vayunmathur.camera.data.updateLocation
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

@Composable
fun <T : NavKey> SettingsPage(backStack: NavBackStack<T>, viewModel: CameraViewModel) {
    val locationEnabled by viewModel.locationEnabled.collectAsState()
    val videoCodec by viewModel.videoCodec.collectAsState()
    val audioInputSource by viewModel.audioInputSource.collectAsState()
    val mirrorFront by viewModel.mirrorFront.collectAsState()
    val noiseReductionEnabled by viewModel.noiseReductionEnabled.collectAsState()
    val edgeSharpeningEnabled by viewModel.edgeSharpeningEnabled.collectAsState()
    val motionPhotoDuration by viewModel.motionPhotoDuration.collectAsState()
    val saveTarget by viewModel.saveTarget.collectAsState()
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val saveLocationError = stringResource(R.string.settings_save_location_error)

    val requestLocation = rememberPermissionRequest(
        Manifest.permission.ACCESS_FINE_LOCATION
    ) { granted ->
        viewModel.setLocationEnabled(granted)
        if (granted) viewModel.updateLocation()
    }

    // Publish the foreground route so MainActivity's hardware shutter only fires on camera.
    // onDispose restores "camera" (not null): popping Settings always returns to Camera, and
    // CameraScreen's entry effect won't re-run if NavDisplay kept it composed underneath.
    androidx.compose.runtime.DisposableEffect(Unit) {
        viewModel.foregroundRoute = "settings"
        onDispose { viewModel.foregroundRoute = "camera" }
    }

    // ACTION_OPEN_DOCUMENT_TREE via a hand-built intent: the stock
    // OpenDocumentTree contract doesn't set FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
    // which we need to keep the folder across restarts.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val treeUri: Uri? = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                viewModel.setSaveTreeUri(treeUri)
            } catch (_: Exception) {
                messenger.show(saveLocationError)
            }
        }
    }

    AppScaffold(
        title = stringResource(UiR.string.settings),
        backStack = backStack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SaveLocationSection(
                saveTarget = saveTarget,
                onChooseFolder = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                        )
                    }
                    runCatching { folderPicker.launch(intent) }
                        .onFailure {
                            messenger.show(saveLocationError)
                        }
                },
                onReset = { viewModel.clearSaveTreeUri() },
            )
            val availableCodecs = remember {
                buildList {
                    add(VideoCodec.AVC)
                    if (CodecSupport.isHevcEncoderAvailable) add(VideoCodec.HEVC)
                    if (CodecSupport.isHardwareAv1EncoderAvailable) add(VideoCodec.AV1)
                }
            }
            if (availableCodecs.size > 1) {
                SettingsSection(title = stringResource(R.string.settings_video_codec)) {
                    // Built from [OutlinedButton] + [DropdownMenu] rather than the library's
                    // `ExposedDropdownMenu` wrapper, which currently recurses into itself.
                    var expanded by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${stringResource(videoCodec.labelRes)} — ${stringResource(videoCodec.descriptionRes)}",
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                                maxLines = 1,
                            )
                            IconArrowDropDown()
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            availableCodecs.forEach { codec ->
                                SelectableDropdownMenuItem(
                                    selected = codec == videoCodec,
                                    onClick = {
                                        viewModel.setVideoCodec(codec)
                                        expanded = false
                                    },
                                    text = {
                                        Column {
                                            Text(stringResource(codec.labelRes))
                                            Text(
                                                stringResource(codec.descriptionRes),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    selectedLeadingIcon = { IconCheck() },
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_audio_source)) {
                // Built from [OutlinedButton] + [DropdownMenu] rather than the library's
                // `ExposedDropdownMenu` wrapper, which currently recurses into itself.
                var audioExpanded by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    OutlinedButton(
                        onClick = { audioExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${stringResource(audioInputSource.labelRes)} — ${stringResource(audioInputSource.descriptionRes)}",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                        )
                        IconArrowDropDown()
                    }
                    DropdownMenu(
                        expanded = audioExpanded,
                        onDismissRequest = { audioExpanded = false }
                    ) {
                        AudioInputSource.entries.forEach { source ->
                            SelectableDropdownMenuItem(
                                selected = source == audioInputSource,
                                onClick = {
                                    viewModel.setAudioInputSource(source)
                                    audioExpanded = false
                                },
                                text = {
                                    Column {
                                        Text(stringResource(source.labelRes))
                                        Text(
                                            stringResource(source.descriptionRes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                selectedLeadingIcon = { IconCheck() },
                            )
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_mirror_front)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_mirror_front_description),
                    checked = mirrorFront,
                    onCheckedChange = { viewModel.setMirrorFront(it) },
                )
            }

            CameraPostProcessingSettings(
                noiseReductionEnabled = noiseReductionEnabled,
                edgeSharpeningEnabled = edgeSharpeningEnabled,
                motionPhotoDuration = motionPhotoDuration,
                onNoiseReductionChange = { viewModel.setNoiseReductionEnabled(it) },
                onEdgeSharpeningChange = { viewModel.setEdgeSharpeningEnabled(it) },
                onMotionDurationChange = { viewModel.setMotionPhotoDuration(it) },
            )

            SettingsSection(title = stringResource(R.string.settings_location)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_location_description),
                    checked = locationEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            requestLocation()
                        } else {
                            viewModel.setLocationEnabled(false)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SaveLocationSection(
    saveTarget: SaveTarget,
    onChooseFolder: () -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    var folderName by remember { mutableStateOf<String?>(null) }
    val treeUri = (saveTarget as? SaveTarget.SafTree)?.treeUri
    LaunchedEffect(treeUri) {
        folderName = treeUri?.let {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    SafDocuments.displayName(context.contentResolver, it)
                }
            }.getOrNull()
        }
    }
    SettingsSection(title = stringResource(R.string.settings_save_location)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconFolder()
                Text(
                    folderName ?: stringResource(R.string.settings_save_location_default),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    maxLines = 1,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onChooseFolder,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                ) {
                    Text(stringResource(R.string.settings_save_location_choose))
                }
                TextButton(
                    onClick = onReset,
                    enabled = treeUri != null,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text(stringResource(R.string.settings_save_location_reset))
                }
            }
        }
    }
}
