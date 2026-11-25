package com.vayunmathur.communicate.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.communicate.wear.data.WearCommunicateStore
import com.vayunmathur.communicate.wear.sync.WearLinkBridge

/**
 * Watch Communicate entry: mirrors the phone's pages (conversation list,
 * thread, call log, voicemail indicators) from the locally stored
 * snapshot/pages so it works standalone. Watch actions queue as ops the
 * phone executes; nothing here claims a phone write before its verdict.
 * An incoming-call alert overlays any route with Decline only — there is
 * no answer-on-watch because watch audio routing is not guaranteed.
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        val store = WearCommunicateStore.get(this)
        WearLinkBridge.get(this)
        WearLinkBridge.publishWatchState(this)
        setContent {
            MaterialTheme {
                var route by remember { mutableStateOf<WearRoute>(WearRoute.ConversationList) }
                val incoming by store.incomingCall.collectAsState()
                val call = incoming
                if (call != null) {
                    WearIncomingCallScreen(
                        store = store,
                        call = call,
                        onDone = { store.setIncomingCall(null) },
                    )
                } else {
                    when (val current = route) {
                        WearRoute.ConversationList -> WearConversationListScreen(
                            store = store,
                            onOpenThread = { route = WearRoute.Thread(it) },
                            onOpenCalls = { route = WearRoute.CallLog },
                            onOpenVoicemail = { route = WearRoute.Voicemail },
                            onOpenPhoneOnly = { route = WearRoute.PhoneOnly },
                        )
                        is WearRoute.Thread -> WearThreadScreen(
                            store = store,
                            threadKey = current.threadKey,
                            onReply = { route = WearRoute.Reply(it) },
                            onBack = { route = WearRoute.ConversationList },
                        )
                        is WearRoute.Reply -> WearReplyScreen(
                            store = store,
                            threadKey = current.threadKey,
                            onDone = { route = WearRoute.Thread(current.threadKey) },
                        )
                        WearRoute.CallLog -> WearCallLogScreen(
                            store = store,
                            onBack = { route = WearRoute.ConversationList },
                        )
                        WearRoute.Voicemail -> WearVoicemailScreen(
                            store = store,
                            onBack = { route = WearRoute.ConversationList },
                        )
                        WearRoute.PhoneOnly -> WearPhoneOnlyScreen(
                            onBack = { route = WearRoute.ConversationList },
                        )
                    }
                }
            }
        }
    }
}
