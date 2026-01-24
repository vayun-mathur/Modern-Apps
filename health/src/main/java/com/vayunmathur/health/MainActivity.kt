package com.vayunmathur.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.vayunmathur.library.ui.DynamicTheme

val PERMISSIONS = setOf(
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getWritePermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getWritePermission(StepsRecord::class),
    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getWritePermission(SleepSessionRecord::class),
)


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val context = LocalContext.current
                val healthConnectClient = remember {
                    HealthConnectClient.getOrCreate(context)
                }
                var hasPermissions by remember { mutableStateOf(false) }

                val requestPermissions = rememberLauncherForActivityResult(
                    contract = PermissionController.createRequestPermissionResultContract(),
                    onResult = { granted ->
                        hasPermissions = granted.containsAll(PERMISSIONS)
                    }
                )

                LaunchedEffect(Unit) {
                    hasPermissions = healthConnectClient.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
                }

                if (hasPermissions) {
                    Navigation()
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(onClick = { requestPermissions.launch(PERMISSIONS) }) {
                            Text("Grant Permissions")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Navigation() {

}