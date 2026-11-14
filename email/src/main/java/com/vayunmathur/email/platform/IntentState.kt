package com.vayunmathur.email.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.email.Route

/** Holds a pending navigation route delivered by an external intent. */
object IntentState {
    var navigationRoute by mutableStateOf<Route?>(null)
}
