package com.vayunmathur.communicate.intents

import com.vayunmathur.library.intents.communicate.ConversationData
import com.vayunmathur.library.intents.communicate.FindConversationsQuery
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.builtins.ListSerializer

/** Assistant tool `find_conversations`: merged threads across all lines, newest first. */
class FindConversationsIntent : AssistantIntent<FindConversationsQuery, List<ConversationData>>(
    FindConversationsQuery.serializer(),
    ListSerializer(ConversationData.serializer()),
) {
    override suspend fun performCalculation(input: FindConversationsQuery): List<ConversationData> =
        findConversations(this, input, communicateAssistantBackend())
}
