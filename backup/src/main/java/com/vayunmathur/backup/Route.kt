package com.vayunmathur.backup

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for the backup app. */
sealed interface Route : NavKey {
    @Serializable
    /** First-run setup: recovery code and backup destination. */
    data object Onboarding : Route

    @Serializable
    /** Main backup status and controls screen. */
    data object Dashboard : Route
}
