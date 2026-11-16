package com.vayunmathur.communicate.intents

import com.vayunmathur.library.intents.communicate.ConversationMessageData
import com.vayunmathur.library.intents.communicate.GetMessagesQuery
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.builtins.ListSerializer

/** Assistant tool `get_conversation_messages`: recent messages for a `threadKey`. */
class GetMessagesIntent : AssistantIntent<GetMessagesQuery, List<ConversationMessageData>>(
    GetMessagesQuery.serializer(),
    ListSerializer(ConversationMessageData.serializer()),
) {
    override suspend fun performCalculation(
        input: GetMessagesQuery,
    ): List<ConversationMessageData> =
        getConversationMessages(this, input, communicateAssistantBackend())
}
