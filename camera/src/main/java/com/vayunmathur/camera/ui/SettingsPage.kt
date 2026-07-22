package com.vayunmathur.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vayunmathur.camera.R
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.CodecSupport
import com.vayunmathur.camera.util.VideoCodec
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExposedDropdownMenuBox
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : NavKey> SettingsPage(backStack: NavBackStack<T>, viewModel: CameraViewModel) {
    val locationEnabled by viewModel.locationEnabled.collectAsState()
    val videoCodec by viewModel.videoCodec.collectAsState()
    val context = LocalContext.current

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setLocationEnabled(granted)
        if (granted) viewModel.updateLocation()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconNavigation(backStack) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
            .padding(16.dp)
        ) {
            val availableCodecs = remember {
                buildList {
                    add(VideoCodec.AVC)
                    if (CodecSupport.isHevcEncoderAvailable) add(VideoCodec.HEVC)
                    if (CodecSupport.isHardwareAv1EncoderAvailable) add(VideoCodec.AV1)
                }
            }
            if (availableCodecs.size > 1) {
                SettingsSection(stringResource(R.string.settings_video_codec)) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = "${videoCodec.label} — ${videoCodec.description}",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .menuAnchor(),
                            label = { Text(stringResource(R.string.settings_video_codec_label)) }
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            availableCodecs.forEach { codec ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(codec.label)
                                            Text(
                                                codec.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setVideoCodec(codec)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_location)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_location_description),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = locationEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    viewModel.setLocationEnabled(true)
                                    viewModel.updateLocation()
                                } else {
                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            } else {
                                viewModel.setLocationEnabled(false)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(4.dp))
    content()
    Spacer(modifier = Modifier.height(16.dp))
}
