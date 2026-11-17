package com.vayunmathur.parentalcontrols.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.vayunmathur.library.log.Log
import com.vayunmathur.parentalcontrols.platform.SupervisionApis
import com.vayunmathur.parentalcontrols.platform.SupervisionCaller

private const val TAG = "ParentalControlsIpc"

/** Where requests are handled, so a slow answer never parks the caller's binder thread. */
private const val THREAD_NAME = "ParentalControlsIpc"

/**
 * The service Settings binds to, and the whole reason Android's parental controls are reachable
 * on this device.
 *
 * **This exists because a supervision app must exist, not because it does very much.** Android
 * ships the entire feature - the dashboard, the PIN and its recovery, bedtime schedules, app
 * limits, web content and app store filters - in Settings, and enforces policy in
 * `SupervisionService`. What it will not do is show any of it to a device with no supervision app:
 * `SupervisionHelper.hasNecessarySupervisionComponent` resolves `config_systemSupervision` and
 * queries it for [SUPERVISION_BIND_ACTION], and `TopLevelSupervisionPreferenceController` reports
 * `UNSUPPORTED_ON_DEVICE` when nothing answers. On a Pixel the answer comes from a Google
 * component that MAOS does not ship, which is why parental controls looked as though they had
 * been removed from the OS when in fact nothing was there to claim them.
 *
 * **The protocol is reimplemented rather than inherited, and that is a constraint rather than a
 * preference.** The platform side of this contract is `MessengerService` in
 * `frameworks/base/packages/SettingsLib/Ipc`, which a Gradle-built prebuilt cannot link - MAOS
 * apps compile against the public SDK and ship as APKs, so `SettingsLib` is not on the classpath.
 * The wire format is small and fully determined by that class, and is restated in [handle] so the
 * two can be compared when GrapheneOS rebases. **If Settings ever shows empty supervision rows,
 * compare against that file first**: a protocol drift here fails silently, because a malformed
 * reply is indistinguishable from an app with nothing to say.
 */
class SupervisionMessengerService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread(THREAD_NAME).apply { start() }
        messenger = Messenger(IncomingHandler(thread.looper, SupervisionCaller(this)))
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private class IncomingHandler(
        looper: android.os.Looper,
        private val caller: SupervisionCaller,
    ) : Handler(looper) {

        /**
         * One request, one reply.
         *
         * The shape is `MessengerService.IncomingHandler.handle`'s, restated:
         *  - `what` is the api id, `arg1` the transaction id, and `replyTo` the client's messenger.
         *  - `sendingUid` is the caller, and is the only identity here that cannot be forged - the
         *    pid in `arg2` is whatever the client wrote.
         *  - The reply is `Message(what = apiId, arg1 = txnId, arg2 = status)` with the encoded
         *    response in `data`.
         *
         * **`msg` must not be touched after the fields are read**: the looper may recycle it, and
         * the original carries the same warning.
         */
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo
            if (replyTo == null) {
                Log.status(TAG, "ignoring a request with no replyTo")
                return
            }
            val apiId = msg.what
            val txnId = msg.arg1
            val sendingUid = msg.sendingUid
            val data: Bundle? = msg.data

            val response = Message.obtain(null, apiId, txnId, STATUS_OK)
            if (!caller.isSettings(sendingUid)) {
                // Not merely unhelpful: these answers describe how a child's device is supervised,
                // and the role this app holds is powerful enough that an unchecked bound service
                // would be a way to ask about it from anywhere.
                Log.status(TAG, "refusing api $apiId from uid $sendingUid; only Settings may ask")
                response.arg2 = STATUS_PERMISSION_DENIED
            } else {
                when (apiId) {
                    SupervisionApis.PREFERENCE_DATA ->
                        response.data = SupervisionApis.preferenceData(data)

                    SupervisionApis.SUPPORTED_APPS ->
                        response.data = SupervisionApis.supportedApps(data)

                    SupervisionApis.IS_SUPERVISOR_ACCOUNT ->
                        response.data = SupervisionApis.isSupervisorAccount()

                    else -> {
                        Log.status(TAG, "unknown api id $apiId")
                        response.arg2 = STATUS_UNKNOWN_API
                    }
                }
            }
            // A failure to reply is the client's problem to time out on; there is nothing useful
            // to do with it here, and throwing would take the handler thread down with it.
            runCatching { replyTo.send(response) }
                .onFailure { Log.status(TAG, "could not reply to api $apiId", it) }
        }

        private companion object {
            /**
             * `ApiServiceException.CODE_*`, which are not on the public SDK either. The client
             * maps these back to exception types by number, so the *values* are the contract -
             * getting two of them the wrong way round would report a refusal as an unknown api.
             */
            const val STATUS_OK = 0
            const val STATUS_PERMISSION_DENIED = 1
            const val STATUS_UNKNOWN_API = 2
        }
    }

    /** Bind action exposed to Settings for the supervision Messenger service. */
    companion object {
        const val SUPERVISION_BIND_ACTION =
            "android.app.supervision.action.SUPERVISION_MESSENGER_SERVICE"
    }
}
