package com.vayunmathur.communicate.wear.ui

/** Watch navigation destinations: detail routes carry the thread key, never selection state. */
sealed interface WearRoute {
    /** Merged conversation list (phone Messages). */
    data object ConversationList : WearRoute

    /** One thread (phone Conversation), identified by its stable watch key. */
    data class Thread(val threadKey: String) : WearRoute

    /** Quick reply for one thread (canned + dictated/free text). */
    data class Reply(val threadKey: String) : WearRoute

    /** Unified call log (phone CallLogs). */
    data object CallLog : WearRoute

    /** Voicemail indicators (phone voicemail list). */
    data object Voicemail : WearRoute

    /** Features that stay on the phone, with the reason stated. */
    data object PhoneOnly : WearRoute
}
