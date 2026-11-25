package com.vayunmathur.camera.wear.sync

import android.content.Context
import com.vayunmathur.camera.wear.data.WearCameraStore
import com.vayunmathur.camera.wear.domain.WearCameraOp
import com.vayunmathur.camera.wear.domain.WearCameraProtocol
import com.vayunmathur.camera.wear.domain.WearOpKind
import com.vayunmathur.camera.wear.domain.decodeWearCameraState
import com.vayunmathur.camera.wear.domain.encodeWearCameraOp
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watch side of WearLink for Camera: mirrored state + thumbnail data items
 * and preview-frame messages land in [WearCameraStore]; every watch control
 * is one op message executed on the phone. App-lifecycle-bound like every
 * WearLink pair — no Play-services listener, no background wakeups.
 */
object WearLinkBridge {
    private const val TAG = "WearLinkBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var node: WearLinkNode? = null

    fun get(context: Context): WearLinkNode {
        val appContext = context.applicationContext
        return node ?: synchronized(this) {
            node ?: create(appContext).also { node = it }
        }
    }

    private fun create(context: Context): WearLinkNode {
        val store = WearCameraStore.get()
        val listener = object : WearLinkListener {
            override fun onData(path: String, body: ByteArray) {
                when (path) {
                    WearCameraProtocol.STATE_PATH -> {
                        val state = decodeWearCameraState(body)
                        if (state == null) {
                            Log.status(TAG, "ignoring malformed camera state")
                            return
                        }
                        store.applyState(state)
                    }
                    WearCameraProtocol.THUMBNAIL_PATH -> store.applyThumbnail(body)
                }
            }

            override fun onMessage(path: String, body: ByteArray) {
                if (path == WearCameraProtocol.PREVIEW_FRAME_PATH) store.applyPreviewFrame(body)
            }
        }
        val created = WearLinkNode(context, WearCameraProtocol.CAPABILITY, listener)
        scope.launch {
            created.state.collect { linkState ->
                store.setLinkConnected(linkState is WearLinkState.Connected)
            }
        }
        created.start()
        return created
    }

    /** Sends one control op; false when no session is live (never queued as "done"). */
    suspend fun sendOp(context: Context, op: WearCameraOp): Boolean =
        get(context).sendMessage(WearCameraProtocol.OP_PATH, encodeWearCameraOp(op))

    fun sendOpAsync(context: Context, op: WearCameraOp) {
        scope.launch { sendOp(context, op) }
    }

    /** Asks the phone to start streaming the live viewfinder to this watch. */
    fun startPreview(context: Context) =
        sendOpAsync(context, WearCameraOp(WearOpKind.PREVIEW_START))

    /** Stops the viewfinder stream (screen left / link going away). */
    fun stopPreview(context: Context) =
        sendOpAsync(context, WearCameraOp(WearOpKind.PREVIEW_STOP))
}
