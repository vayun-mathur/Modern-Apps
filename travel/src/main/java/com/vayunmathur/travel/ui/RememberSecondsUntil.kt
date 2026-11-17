package com.vayunmathur.travel.ui

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import com.vayunmathur.library.ui.DateString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * Seconds remaining until [expiresAtIso], ticking once per second **only while
 * the app is in the foreground** (`RESUMED`). Backgrounding pauses the ticker;
 * returning resumes it (and reports 0 immediately if it already lapsed).
 */
@Composable
fun rememberSecondsUntil(expiresAtIso: String): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var remaining by remember(expiresAtIso) { mutableLongStateOf(secondsUntil(expiresAtIso)) }
    LaunchedEffect(expiresAtIso, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                remaining = secondsUntil(expiresAtIso)
                if (remaining <= 0) break
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    return remaining
}
