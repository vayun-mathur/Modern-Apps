package com.vayunmathur.openassistant.platform

import android.content.Context
import android.content.Intent
import android.icu.text.MessageFormat
import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.engine.processor.A2uiCoreMessageProcessor
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiDeleteSurfaceMessage
import androidx.core.net.toUri
import com.vayunmathur.library.log.Log
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.domain.a2uiSurfaceId
import com.vayunmathur.openassistant.domain.actionTurnText
import com.vayunmathur.openassistant.domain.buildA2uiEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the A2UI engine for the app: feeds it the surfaces stored on assistant messages and
 * turns the events those surfaces fire into user turns via [onAction].
 *
 * Surfaces are keyed by message ([a2uiSurfaceId]), so [sync] is idempotent and reopening a
 * conversation recreates its surfaces from Room. Values typed into a surface are not stored;
 * what matters reaches the model, and so the history, through the action turn.
 */
class A2uiController(
    context: Context,
    scope: CoroutineScope,
    private val onAction: (conversationId: Long, text: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val processor = A2uiCoreMessageProcessor(
        catalogs = listOf(AssistantCatalog(::openUrl, ::formatMessage)),
        dataModelFactory = ::ComposeDataModel,
        componentRegistryFactory = ::ComposeComponentRegistry,
    )
    private val fed = HashMap<Long, String>()
    private val conversationOf = HashMap<String, Long>()

    val surfaces: StateFlow<Map<String, A2uiCoreSurfaceModel>> = processor.activeSurfaces
        .map { list -> list.filterIsInstance<A2uiCoreSurfaceModel>().associateBy { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch { processor.collectMessages() }
        scope.launch {
            processor.outboundEvents.collect { event ->
                when (event) {
                    is A2uiClientEventMessage -> conversationOf[event.surfaceId]?.let {
                        onAction(it, actionTurnText(event.type, event.context))
                    }
                    is A2uiClientErrorMessage ->
                        Log.status(TAG, "${event.code} on ${event.surfaceId}: ${event.message}")
                }
            }
        }
    }

    /** Feeds any surface in [messages] the engine has not seen yet. Main thread only. */
    fun sync(messages: List<Message>) {
        for (message in messages) {
            val json = message.a2ui
            if (json != null && fed[message.id] != json) feed(message, json)
        }
    }

    private fun feed(message: Message, json: String) {
        val surfaceId = a2uiSurfaceId(message.id)
        if (fed.put(message.id, json) != null) processor.processMessage(A2uiDeleteSurfaceMessage(surfaceId))
        conversationOf[surfaceId] = message.conversationId
        val envelope = buildA2uiEnvelope(surfaceId, AssistantCatalog.ID, json)
        if (envelope == null) {
            Log.status(TAG, "unusable a2ui block on message ${message.id}")
            return
        }
        envelope.forEach(processor::processMessage)
    }

    private fun openUrl(url: String) {
        try {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (expected: Exception) {
            Log.status(TAG, "Failed to open $url", expected)
        }
    }

    private fun formatMessage(pattern: String, locale: java.util.Locale, arguments: Map<String, Any>): String =
        MessageFormat(pattern, locale).format(arguments)

    private companion object {
        const val TAG = "A2uiController"
    }
}
