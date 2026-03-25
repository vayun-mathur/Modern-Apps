package com.vayunmathur.library.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun NoPermissionsScreen(permissions: Array<String>, text: String, setHasPermissions: (Boolean) -> Unit) {
    val permissionRequestor = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsResult ->
        setHasPermissions(permissionsResult.values.all { it })
    }
    LaunchedEffect(Unit) {
        permissionRequestor.launch(permissions)
    }
    Scaffold {
        Box(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
        ) {
            androidx.compose.material3.Button(
                {
                    permissionRequestor.launch(permissions)
                }, Modifier.align(Alignment.Center)
            ) {
                Text(text)
            }
        }
    }
}

@Composable
fun PermissionsChecker(permissions: Array<String>, text: String, content: @Composable ()->Unit) {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(permissions.all {
            ContextCompat.checkSelfPermission(
                context,
                it
            ) == PackageManager.PERMISSION_GRANTED
        })
    }
    if (!hasPermissions) {
        NoPermissionsScreen(permissions, text) { hasPermissions = it }
    } else {
        content()
    }
}