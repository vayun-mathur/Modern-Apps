package com.vayunmathur.passwords.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.tryOrDefault
import com.vayunmathur.passwords.Password
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.TOTP
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    ListPage<Password, Route, Route.PasswordEditPage>(backStack, viewModel, "Passwords", ::PasswordListItem, { Route.PasswordPage(it) }, { Route.PasswordEditPage(0) }, Route.Settings)
}

@Composable
private fun PasswordListItem(pass: Password, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current

    if (pass.totpSecret.isNullOrBlank()) {
        ListItem(
            { Text(pass.name.ifBlank { "(no name)" }) },
            Modifier.clickable { onClick() },
            supportingContent = { Text(pass.userId) }
        )
        return
    }

    var currentCode by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            val timeBucket = System.currentTimeMillis() / 1000 / 30
            currentCode = tryOrDefault("----") { TOTP.generate(pass.totpSecret, timeBucket * 30) }
            progress = 1f - (System.currentTimeMillis() / 30000f) % 1f
            delay(50)
        }
    }

    ListItem(
        { Text(pass.name.ifBlank { "(no name)" }) },
        modifier.clickable { onClick() },
        supportingContent = { Text(pass.userId) },
        trailingContent = {
            Row(Modifier.clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("totp", currentCode))
            }.wrapContentHeight(), verticalAlignment = Alignment.CenterVertically) {
                Text(currentCode, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator({progress}, Modifier.size(40.dp))
                    Icon(painterResource(R.drawable.content_copy_24px), contentDescription = "Copy TOTP", Modifier.size(16.dp))
                }
            }
        }
    )
}