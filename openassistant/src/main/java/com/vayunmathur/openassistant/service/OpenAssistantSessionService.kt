package com.vayunmathur.openassistant.service

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Factory the platform binds (via @xml/voice_interaction_service's sessionService
 * attribute) to create one [OpenAssistantSession] per assist invocation.
 */
class OpenAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession {
        return OpenAssistantSession(this)
    }
}
