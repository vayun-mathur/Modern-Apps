package com.vayunmathur.communicate.intents

import com.vayunmathur.library.intents.communicate.SendMessageRequest
import com.vayunmathur.library.intents.communicate.SendMessageResult
import com.vayunmathur.library.util.AssistantIntent

/**
 * Assistant tool `send_message_v2` (provider side). Executes
 * `CommunicateRepository.sendMessage` for Sim/Rcs/WhatsApp/Signal. Google Voice
 * needs a foreground Activity for its bot-token mint, so it returns the typed
 * [ERROR_GOOGLE_VOICE_REQUIRES_ACTIVITY] result instead of attempting a send.
 */
class SendMessageIntent : AssistantIntent<SendMessageRequest, SendMessageResult>(
    SendMessageRequest.serializer(),
    SendMessageResult.serializer(),
) {
    override suspend fun performCalculation(input: SendMessageRequest): SendMessageResult =
        sendAssistantMessage(this, input, communicateAssistantBackend())
}
