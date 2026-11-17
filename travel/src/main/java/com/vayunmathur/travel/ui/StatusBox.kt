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

/** Centered loading/error/empty state used by results pages. */
@Composable
fun StatusBox(
    loading: Boolean,
    error: String?,
    isEmpty: Boolean,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No flights found. Try different dates or airports.",
) {
    Box(modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator()
            error != null -> Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            isEmpty -> Text(
                emptyMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
