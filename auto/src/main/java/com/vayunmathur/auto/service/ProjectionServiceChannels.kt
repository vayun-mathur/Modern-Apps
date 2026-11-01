package com.vayunmathur.auto.service

import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.platform.AudioSinkChannel
import com.vayunmathur.auto.platform.AutoSessionState
import com.vayunmathur.auto.platform.GuidanceChannel
import com.vayunmathur.auto.platform.InputChannel
import com.vayunmathur.auto.platform.MicPermission
import com.vayunmathur.auto.platform.MicSourceChannel
import com.vayunmathur.auto.platform.MusicCaptureSinkHolder
import com.vayunmathur.auto.platform.NavStatusChannel
import com.vayunmathur.auto.platform.NightSource
import com.vayunmathur.auto.platform.SensorChannel
import com.vayunmathur.auto.platform.VideoSinkChannel
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.gal.Service as GalServiceProto
import com.vayunmathur.auto.protocol.gal.SensorType
import com.vayunmathur.auto.service.ProjectionService.Companion.TAG
import com.vayunmathur.auto.telephony.CarProjectionInCallService

// Channel bring-up: openNext + the per-service maybeCreate* factories.
// Split from ProjectionService.kt to keep that file under the 800-line limit.

/**
 * Opens the next not-yet-attempted advertised service, in the HU's wire order.
 *
 * Only one open is ever in flight: while `pendingChannels` is non-empty the
 * head unit still owes us an answer, so wait.
 *
 * Service 1 IS sent, first: gearhead opens channel 0 locally and never sends
 * a 0x7 for it, but DHU 2.0 rejects a first-0x7 for service 2 with an empty
 * 0xff and no 0x8 (Run 4) -- so the HU-side may expect the wire-order
 * sequence to start at 1. If the HU 0xffs/0x8-refuses service 1 as well, it
 * lands in refusedChannels like any other refusal and the bring-up moves on
 * to service 2; the experiment then distinguishes "first-open must be 1"
 * (0x8 arrives for 1) from "video refused regardless" (both refused).
 */
internal fun ProjectionService.openNext(connection: GalConnection) {
    val session = connection.session
    if (session.pendingChannels.isNotEmpty()) return
    val next = session.services.firstOrNull {
        it.id !in session.openChannels &&
            it.id !in session.refusedChannels
    } ?: return
    connection.send(session.openChannel(next))
    Log.status(TAG, "requesting channel open for service ${next.id}")
    maybeCreateVideoSink(next, connection)
    maybeCreateMessaging(next, connection)
    maybeCreateInput(next, connection)
    maybeCreateSensors(next, connection)
    maybeCreateGuidance(next, connection)
    maybeCreateNavStatus(next, connection)
    maybeCreateAudioSinks(next, connection)
    maybeCreateMic(next, connection)
    observeUnownedService(next)
}

/** Creates the video sink when its entry opens; wires every display feed. */
private fun ProjectionService.maybeCreateVideoSink(next: GalServiceProto, connection: GalConnection) {
    if (next.id != GalService.VIDEO_SINK.id || !next.hasMediaSink()) return
    video = VideoSinkChannel(this, next, connection, AutoSessionState::onVideoEvent)
        .also { sink ->
            sink.wiring.setNowPlayingSource(
                get = { AutoSessionState.nowPlaying.value },
                onTap = { mediaMonitor?.toggle() },
            )
            // Prev/next ride the same monitor as the card tap; unset
            // (null monitor) means the buttons show but stay disabled.
            sink.wiring.setTransportCallbacks(
                onPrevious = { mediaMonitor?.seekToPrevious() },
                onNext = { mediaMonitor?.seekToNext() },
            )
            // Night, map surface and map touches ride the session's
            // car-app host (owned by CarAppHostSession, split for
            // the 800-line limit).
            carAppHostSession.wireInto(
                sink,
                snapshots = { guidanceMonitor?.snapshots?.value },
            )
            // Rail cluster + call card feeds: the display caches
            // both for presentations created later.
            sink.wiring.setPhoneStatusSource { phoneStatusMonitor?.snapshot }
            sink.wiring.setCallSource(
                get = { AutoSessionState.activeCall.value },
                onAnswer = { CarProjectionInCallService.answerCall() },
                onEnd = { CarProjectionInCallService.endCall() },
                onHold = { CarProjectionInCallService.toggleHold() },
                onMute = { CarProjectionInCallService.toggleMute() },
            )
        }
}

/**
 * Creates the ch14 messaging owner when it opens.
 *
 * The audio seam resolves channels lazily -- ch14 opens before ch4/6
 * in wire order, and TTS started with the session above, so early
 * utterances wait for no grant. The owner starts mirroring on its grant
 * (see MessagingCarAppService.onChannelOpen).
 */
private fun ProjectionService.maybeCreateMessaging(next: GalServiceProto, connection: GalConnection) {
    if (next.id != GalService.NOTIFICATION.id) return
    messaging = MessagingCarAppService(
        connection,
        audio = CarMessagingAudio(
            tts = { tts },
            mic = { mic },
            onEvent = AutoSessionState::onAudioEvent,
        ),
        onEvent = AutoSessionState::onMessagingEvent,
        onReply = { threadId, text ->
            Log.status(TAG, "head-unit reply for $threadId (${text.length} chars)")
        },
        context = { this },
    )
}

/**
 * Creates the ch8 input owner when the entry carrying `input_source`
 * opens. Binding is payload-driven, not id-driven: the DHU 2.0 discovery
 * advertises services 1-7 with ids that do NOT match gearhead's `rro`
 * (sensor on 1, input on 3, mic on 7 -- verified by decoding the 0x6
 * payload), while gearhead numbers them 7/8/6. Gearhead binds by
 * payload (`jlf.a(xpa)` reads the sensor config out of the service
 * entry), and so do we: the entry carrying `input_source` owns input
 * wherever its id lands. The video/audio-sink ids (2/4/5) happen to
 * line up, so those keep their id checks as a second factor.
 */
private fun ProjectionService.maybeCreateInput(next: GalServiceProto, connection: GalConnection) {
    if (!next.hasInputSource()) return
    inputChannelId = next.id
    input = InputChannel(
        service = next,
        connection = connection,
        isInputAllowed = { connection.session.focus.inputAllowed },
        displaySize = { video?.displaySize() },
        onEvent = AutoSessionState::onInputEvent,
        touchSink = { touch -> video?.injectTouch(touch) ?: false },
        keySink = { key -> video?.injectKey(key.keycode, key.down) ?: false },
        scrollSink = { delta -> video?.injectScroll(delta) ?: false },
    )
}

/**
 * Creates the sensor owner when the entry carrying `sensor_source` opens
 * (DHU 2.0: service 1; gearhead rro: 7 -- payload-driven, see above).
 * Night follows the phone until the first NIGHT_MODE batch; the
 * UiModeManager seam stays out of the service -- maps-dev owns the live
 * night source next.
 */
private fun ProjectionService.maybeCreateSensors(next: GalServiceProto, connection: GalConnection) {
    if (!next.hasSensorSource()) return
    sensorChannelId = next.id
    sensors = SensorChannel(
        connection = connection,
        night = NightSource { isNightNow() },
        onEvent = AutoSessionState::onSensorEvent,
        channelId = next.id,
        subscriptions = next.sensorSource.sensorsList.mapNotNull { SensorType.forNumber(it.type) },
    )
}

/**
 * Creates the ch3 guidance owner when it opens. The owner claims the
 * sink on its grant (see GuidanceChannel). The stream itself is
 * TTS-owned: the session predates ch3, so the grant arms it when the
 * TTS session is already running.
 */
private fun ProjectionService.maybeCreateGuidance(next: GalServiceProto, connection: GalConnection) {
    if (next.id != GalService.AUDIO_SINK_GUIDANCE.id || !next.hasMediaSink()) return
    guidance = GuidanceChannel(
        service = next,
        connection = connection,
        ttsActive = { tts != null },
        onEvent = AutoSessionState::onSensorEvent,
    )
}

/**
 * Creates the ch10 nav-status owner when it opens. The owner posts the
 * inactive stub on its grant (see NavStatusChannel.onChannelOpen).
 */
private fun ProjectionService.maybeCreateNavStatus(next: GalServiceProto, connection: GalConnection) {
    if (next.id != GalService.NAVIGATION_STATUS.id) return
    navStatus = NavStatusChannel(
        connection = connection,
        onEvent = AutoSessionState::onSensorEvent,
    )
}

/**
 * Creates the ch4/ch5 audio sink owners when they open.
 *
 * Focus asks ride control 0x18 right after setup -- the head unit
 * answers with 0x13 notifications, which refresh the sink gains.
 */
private fun ProjectionService.maybeCreateAudioSinks(next: GalServiceProto, connection: GalConnection) {
    if (next.id == AudioSinkRole.SYSTEM.serviceId && next.hasMediaSink()) {
        audioSys = AudioSinkChannel(
            role = AudioSinkRole.SYSTEM,
            service = next,
            connection = connection,
            focus = { connection.session.focus },
            onEvent = AutoSessionState::onAudioEvent,
        )
    }
    if (next.id == AudioSinkRole.MEDIA.serviceId && next.hasMediaSink()) {
        audioMedia = AudioSinkChannel(
            role = AudioSinkRole.MEDIA,
            service = next,
            connection = connection,
            focus = { connection.session.focus },
            onEvent = AutoSessionState::onAudioEvent,
        )
        // The music-capture service resolves this lazily per feed; publish
        // it now so capture starts flowing once the sink starts.
        MusicCaptureSinkHolder.sink = audioMedia
    }
}

/**
 * Creates the mic owner when the entry carrying `media_source` opens
 * (DHU 2.0: service 7; gearhead rro: 6 -- payload-driven, see above).
 *
 * Retention needs RECORD_AUDIO; without it chunks are acked and
 * counted only, so the head unit still sees a live endpoint.
 */
private fun ProjectionService.maybeCreateMic(next: GalServiceProto, connection: GalConnection) {
    if (!next.hasMediaSource()) return
    micChannelId = next.id
    mic = MicSourceChannel(
        connection = connection,
        retentionAllowed = { MicPermission.isGranted(this) },
        onEvent = AutoSessionState::onAudioEvent,
        channelId = next.id,
    )
}
