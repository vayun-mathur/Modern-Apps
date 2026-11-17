package com.vayunmathur.calendar.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.platform.resolveDateJumpTarget
import kotlinx.datetime.LocalDate

/**
 * Applies a pending date jump (or a time-epoch VIEW intent) to the ViewModel.
 * The provider often sends time URIs with a null type, so URI shape is matched
 * (see [MainActivity.isTimeEpochIntent]), not just type == "time/epoch".
 */
@Composable
fun DateJumpEffect(
    activityIntent: Intent?,
    pendingDate: MutableState<LocalDate?>,
    viewModel: CalendarViewModel,
) {
    val dateJump by pendingDate
    LaunchedEffect(activityIntent, dateJump) {
        val target = dateJump ?: resolveDateJumpTarget(activityIntent)
        if (target != null) {
            viewModel.setSelectedDate(target)
            viewModel.setLastViewedDate(target)
            pendingDate.value = null
        }
    }
}

