package com.vayunmathur.auto.service

import android.app.Service
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.network.HeadUnitServer
import com.vayunmathur.auto.network.TransportIntake
import com.vayunmathur.auto.platform.AudioSinkChannel
import com.vayunmathur.auto.platform.AutoSessionState
import com.vayunmathur.auto.platform.CarTts
import com.vayunmathur.auto.platform.GuidanceChannel
import com.vayunmathur.auto.platform.InputChannel
import com.vayunmathur.auto.platform.InputEvent
import com.vayunmathur.auto.platform.MediaEvent
import com.vayunmathur.auto.platform.MediaPlaybackMonitor
import com.vayunmathur.auto.platform.MessagingEvent
import com.vayunmathur.auto.platform.MicPermission
import com.vayunmathur.auto.platform.MicSourceChannel
import com.vayunmathur.auto.platform.CallCardPush
import com.vayunmathur.auto.platform.MusicCaptureSinkHolder
import com.vayunmathur.auto.platform.NavGuidanceMonitor
import com.vayunmathur.auto.platform.NavStatusChannel
import com.vayunmathur.auto.platform.NightSource
import com.vayunmathur.auto.platform.PhoneStatusMonitor
import com.vayunmathur.auto.platform.SensorChannel
import com.vayunmathur.auto.platform.VideoSinkChannel
import com.vayunmathur.auto.telephony.CarProjectionInCallService
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.ChannelMessage
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalCredential
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.MapsGuidance
import com.vayunmathur.auto.protocol.NavSnapshot
import com.vayunmathur.auto.protocol.ReconnectBackoff
import com.vayunmathur.auto.protocol.SessionState
import com.vayunmathur.auto.protocol.StreamTransport
import com.vayunmathur.auto.protocol.gal.Service as GalServiceProto
import com.vayunmathur.auto.protocol.isMediaBrowserChannel
import kotlin.concurrent.thread

/**
 * Runs a projection session for as long as a head unit is attached.
 *
 * Concurrency, by deliberate split: one [javax.net.ssl.SSLEngine] backs the
 * connection and an engine is not thread-safe, so every wrap/unwrap and every
 * socket write runs on the connection's single I/O thread -- one thread, many
 * senders. The `ma-auto-projection` thread owns reads (the [GalConnection.pump]
 * loop below); the main thread owns media (the [VideoSinkChannel] vsync drain);
 * audio sinks, mic, TTS and input injection only ever enqueue sends, which the
 * I/O thread flushes without blocking the caller -- so no socket write ever runs
 * on the main thread. See `ProjectionService` + `CarDisplay` KDoc for the
 * matching discipline on the UI side.
 */
class ProjectionService : Service() {

    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        startForeground(ProjectionNotification.NOTIFICATION_ID, ProjectionNotification.build(this))
        publishCredentialExpiry()
        // The now-playing feed outlives any one session: the phone card shows it
        // between connections, and the car card picks the latest up on bring-up.
        mediaMonitor = MediaPlaybackMonitor(this) { info ->
            AutoSessionState.onMediaEvent(MediaEvent.NowPlayingChanged(info))
            video?.wiring?.setNowPlaying(info)
        }.also { it.start() }
        worker = thread(name = "ma-auto-projection") { serve() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        // Unblocks the worker if it is sitting in accept(): closing the
        // ServerSocket raises there, which runSession reports as a failure the
        // loop then drops because running is false. Interrupt alone cannot do
        // this -- accept() ignores thread interruption.
        server?.close()
        worker?.interrupt()
        mediaMonitor?.stop()
        mediaMonitor = null
        super.onDestroy()
    }

    /** The listening socket; kept so [onDestroy] can unblock [serve]. */
    @Volatile
    private var server: HeadUnitServer? = null

    /** Backoff between sessions; owns no threads, just the delay policy. */
    private val reconnectBackoff = ReconnectBackoff()

    private fun serve() {
        HeadUnitServer().use { server ->
            this.server = server
            try {
                while (running) {
                    // A clean parting goes straight back to listening; a failed session
                    // backs off with jitter so a flapping head unit does not spin the
                    // accept loop at 100% CPU. The sleep below is interruptible, so
                    // destroy() never hangs.
                    val failed = runSession(server)
                    if (!running) return
                    if (failed) {
                        val delayMs = reconnectBackoff.onFailure()
                        Log.status(TAG, "session failed; retrying in ${delayMs}ms")
                        sleepInterruptibly(delayMs)
                    } else {
                        reconnectBackoff.onSuccess()
                    }
                }
            } finally {
                this.server = null
            }
        }
    }

    /**
     * Runs one head-unit session. Returns true when it ended abnormally (back the
     * loop off) rather than with a clean parting.
     *
     * Non-TCP transports park already-opened transports in [TransportIntake]
     * (USB accessory fds, wireless sockets); those drain BEFORE blocking in
     * TCP accept(), so a plugged-in cable never waits behind a DHU that never
     * dials. Ownership stays in this serve loop either way: every transport
     * runs the same session body, and TLS/GAL downstream never learn which
     * intake won.
     */
    private fun runSession(server: HeadUnitServer): Boolean {
        TransportIntake.take()?.let { pending ->
            var failed = false
            runCatching { sessionOnTransport(pending.transport, pending.carName) }
                .onFailure {
                    failed = true
                    // The transport may never have reached the session body
                    // (credential parse threw before GalConnection existed):
                    // close it here so a stillborn intake leaks nothing. A
                    // live session's transport closes with its connection.
                    runCatching { pending.transport.close() }
                    if (running) Log.error(TAG, "projection session ended", it)
                }
            return failed
        }
        var failed = false
        runCatching { session(server) }
            .onFailure {
                failed = true
                if (running) Log.error(TAG, "projection session ended", it)
            }
        return failed
    }

    /** Sleeps [delayMs], returning early on interrupt with the flag restored. */
    private fun sleepInterruptibly(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * The phone's own night state, seeding the sensor stub until the first
     * NIGHT_MODE batch (see [SensorChannel.onChannelOpen]).
     *
     * Reads the car UiMode bit first -- a phone docked in a car holder at
     * night reports car mode -- and falls back to the system night flag.
     * Best-effort: any failure answers "day" rather than failing the
     * bring-up, and the head unit's stream overrides it anyway.
     */
    internal fun isNightNow(): Boolean = runCatching {
        val uiMode = getSystemService(UiModeManager::class.java)?.nightMode
            ?: resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        when (uiMode) {
            UiModeManager.MODE_NIGHT_YES -> true
            UiModeManager.MODE_NIGHT_NO -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }.getOrDefault(false)

    private fun session(server: HeadUnitServer) {
        server.accept().use { socket ->
            // The TCP loopback path wraps its socket as an intake pending so
            // it runs the same body as USB/wireless: one session shape, three
            // intakes. The socket `use` still owns the socket itself; the
            // session body owns the streams through the connection.
            sessionOnTransport(
                StreamTransport(socket.getInputStream(), socket.getOutputStream()),
                CAR_NAME,
            )
        }
    }

    /**
     * One head-unit session over an already-opened transport. Transport-
     * agnostic: version negotiation, TLS and the whole GAL bring-up are
     * identical for TCP loopback, USB accessory fds and wireless sockets.
     * The caller owns the raw resource (socket or parked transport); this
     * owns everything GAL.
     */
    private fun sessionOnTransport(transport: GalTransport, carName: String) {
        // A head unit is on the wire; the phone status screen leaves Disconnected.
        AutoSessionState.onSocketAccepted()
        val connection = GalConnection(
            transport = transport,
            sslContext = GalCredential.fromAssets(assets),
            deviceModel = Build.MODEL,
            deviceManufacturer = Build.MANUFACTURER,
            onChannelMessage = { message -> routeChannelMessage(message) },
            trace = { Log.dev(TAG, it) },
        )

        // The session owns focus arbitration; mirror accepted changes to the
        // phone flows so the status screen, input gate and audio sinks all
        // read one source of truth. Sinks recompute their arbitrated gain
        // off the same snapshot -- duck, mute and unmute follow the 0x13.
        connection.session.onFocusChange = { onSessionFocusChanged(connection) }

        startSessionScoped()

        try {
            pumpUntilGone(connection, carName)
        } finally {
            tearDownSession(connection)
        }
    }

    /**
     * Routes one inbound channel message to its owner by channel id.
     *
     * 0x8004 is aliased across services (MediaAck on media channels,
     * SensorError on the sensor channel), so only the owning channel parses;
     * anything for a different channel is ignored rather than misparsed.
     * Unowned services (BT, phone-status, radio, vendor, wifi, car-control
     * and anything future) opened generically in [openNext] with no app owner
     * -- see `observeUnownedService`: observed and ignored, never answered and
     * never crashed on; the bring-up moves on.
     */
    private fun routeChannelMessage(message: ChannelMessage) {
        if (isMediaBrowserChannel(message.channelId)) {
            // GAL 11/12 gap: the channel message IDs are unmapped, so
            // these are observed and ignored, never answered. Now-playing
            // rides the ch2 video stream instead.
            Log.dev(TAG, "ignoring media-browser message on ch${message.channelId}")
        } else if (message.channelId == GalService.NOTIFICATION.id) {
            messaging?.onMessage(message.channelId, message.type, message.payload)
        } else if (message.channelId == inputChannelId) {
            routeInputMessage(message)
        } else if (message.channelId == sensorChannelId) {
            sensors?.onMessage(message.channelId, message.type, message.payload)
        } else if (message.channelId == GalService.AUDIO_SINK_GUIDANCE.id) {
            guidance?.onMessage(message.channelId, message.type, message.payload)
        } else if (message.channelId == GalService.NAVIGATION_STATUS.id) {
            navStatus?.onMessage(message.channelId, message.type)
        } else if (message.channelId == AudioSinkRole.SYSTEM.serviceId) {
            audioSys?.onMessage(message.channelId, message.type, message.payload)
        } else if (message.channelId == AudioSinkRole.MEDIA.serviceId) {
            audioMedia?.onMessage(message.channelId, message.type, message.payload)
        } else if (message.channelId == micChannelId) {
            mic?.onMessage(message.channelId, message.type, message.payload)
        } else if (message.channelId == GalService.VIDEO_SINK.id) {
            video?.onMessage(message.channelId, message.type, message.payload)
        } else {
            Log.dev(
                TAG,
                "ignoring message on unowned service ch${message.channelId} " +
                    "(0x${message.type.toString(HEX_RADIX)}); no owner",
            )
        }
    }

    /** Routes one ch8 message, tolerating traffic that arrives before the owner binds. */
    private fun routeInputMessage(message: ChannelMessage) {
        if (input == null) {
            // Input traffic with no owner: the input entry opened
            // but binding has not run yet. Log, don't crash -- the
            // owner binds on its grant once advertised.
            Log.status(
                TAG,
                "ch$inputChannelId input traffic with no input owner " +
                    "(0x${message.type.toString(HEX_RADIX)}); dropping",
            )
            AutoSessionState.onInputEvent(InputEvent.DroppedNoFocus)
        } else {
            input?.onMessage(message.channelId, message.type, message.payload)
        }
    }

    /** Mirrors one focus verdict into the phone flows, sinks and input binding. */
    private fun onSessionFocusChanged(connection: GalConnection) {
        AutoSessionState.onFocusChanged(connection.session.focus)
        audioSys?.onFocusChanged()
        audioMedia?.onFocusChanged()
        input?.onInputFocusChanged(connection.session.focus.inputAllowed)
    }

    /**
     * Starts everything scoped to the session (not to any channel grant):
     * call-card pushes, phone-side TTS, the car-app host, guidance, music
     * capture and phone status. The first notification may arrive before ch4
     * opens, so TTS resolves its sink lazily and early utterances drop with
     * a count.
     */
    private fun startSessionScoped() {
        // Call snapshots push into the car card like focus mirrors into the
        // phone flows: added/changed replace wholesale, removed clears.
        // The card itself decides incoming vs active rows (see updateCallCard).
        // Installed on the process bus (the InCall binding is platform-owned
        // and cannot reference this session directly); cleared on teardown.
        CallCardPush.push = { info -> video?.wiring?.setActiveCall(info) }

        // Phone-side TTS starts with the session, not with any channel
        // grant. It also owns the ch3 guidance stream lifecycle (arms on
        // start, parks on stop); the owner appears on the ch3 grant, after this.
        tts = CarTts(
            this,
            systemSink = { audioSys },
            guidance = { guidance },
            onEvent = AutoSessionState::onAudioEvent,
        ).also { it.start() }

        // Guidance + the car-app host start with the session too: fixes flow
        // from the first tick, and the host binds MapsCarAppService so the
        // nav card renders whatever Maps publishes (its own surface +
        // NavigationTemplate) instead of MA Auto re-rendering a second map.
        // Owned by CarAppHostSession (split for the 800-line limit).
        carAppHostSession.start()
        guidanceMonitor = NavGuidanceMonitor(this) { snapshot ->
            onGuidanceSnapshot(snapshot)
        }.also { it.start() }

        // Music capture starts with the session too, in its own
        // mediaProjection-typed service (the projection type cannot ride
        // on this service -- see MusicCaptureService). No stored grant
        // means fail-closed silence on ch5.
        MusicCaptureService.startIfGranted(this)

        // Phone status starts with the session too: the rail cluster pulls
        // signal/battery/DND/badge from it on its 1Hz tick, and the call
        // card pushes come from the InCall owners below.
        phoneStatusMonitor = PhoneStatusMonitor(this).also { it.start() }
    }

    /** Folds one guidance fix into the car pixels and the ch7/ch10 live values. */
    private fun onGuidanceSnapshot(snapshot: NavSnapshot) {
        video?.wiring?.setNavSnapshot(snapshot)
        // ch7 live values: the fix folds into the same last-known
        // snapshot the head-unit batches fold into (HANDOFF.md section
        // 10); ch10 turn update as the route advances, deduped inside
        // postUpdate so a stationary fix rate stays quiet.
        sensors?.postLiveEvents(MapsGuidance.toSensorEvents(snapshot))
        navStatus?.postUpdate(MapsGuidance.toNavStatus(snapshot))
        // Car pixels follow the same snapshot: night restyles the rail,
        // cards and drawer plus the map palette; parked clears the
        // driving-restriction gate (drawer lockout down, media row
        // visible). Both are last-known-wins with the ch7 NIGHT_MODE
        // batch (the head unit owns the car truth; the phone only seeds
        // it). Trip-end drawer close stays on setParked.
        video?.wiring?.setNightDark(snapshot.isNight ?: isNightNow())
        video?.wiring?.setParkedBrowsingGate(snapshot.parked)
        if (snapshot.parked) video?.wiring?.closeDrawerOnPark()
    }

    /**
     * Tears the session down: the render pair, phone UI parting, connection
     * I/O park and every channel owner. A pump exception must not leak the
     * session -- the error still propagates to runSession so the backoff loop
     * sees the failure.
     */
    private fun tearDownSession(connection: GalConnection) {
        Log.status(TAG, "head unit disconnected: ${connection.session.failure ?: "cleanly"}")
        AutoSessionState.onSessionEnd(connection.session.failure)
        // Park the connection's I/O thread first: closing the socket
        // unblocks the pump read, and late sends drop rather than racing
        // the shutdown. The socket `use` below closes the streams anyway.
        // (On the intake path the parked transport closes with this
        // connection too; a stillborn intake that never built one closes
        // in runSession instead.)
        runCatching { connection.close() }
        video?.release()
        video = null
        messaging?.release()
        messaging = null
        input = null
        audioSys?.release()
        audioSys = null
        audioMedia?.release()
        audioMedia = null
        MusicCaptureSinkHolder.sink = null
        mic?.release()
        mic = null
        tts?.stop()
        tts = null
        guidanceMonitor?.stop()
        guidanceMonitor = null
        carAppHostSession.stop()
        MusicCaptureService.stop(this)
        MusicCaptureSinkHolder.sink = null
        // Unowned services need no teardown: their channels opened
        // generically with no app owner (see openNext), so closing the
        // connection above already released everything they hold.
        sensors?.release()
        sensors = null
        guidance?.release()
        guidance = null
        navStatus?.release()
        navStatus = null
        phoneStatusMonitor?.stop()
        phoneStatusMonitor = null
        CallCardPush.push = null
    }

    /**
     * The bring-up + streaming loop. Pump owns reads only -- never the encoder and
     * never socket writes: the sink drains itself on the main thread at vsync
     * cadence (see VideoSinkChannel.startVsyncDrain) and the connection's single
     * I/O thread owns every wrap/unwrap/write. Channel opens go out sequentially
     * in HU-discovery wire order, one in flight at a time; see [openNext].
     */
    private fun pumpUntilGone(connection: GalConnection, carName: String) {
        val pending = ChannelSetups()
        var reportedActive = false
        while (running && connection.pump()) {
            // Open every advertised service in HU-discovery wire order, one at a
            // time: the next 0x7 goes out only once the previous channel is
            // granted (openChannels) or refused (refusedChannels, or a bare
            // 0xff with no 0x8 -- DHU 2.0 answers that way). gearhead opens in
            // this same order (`jbp.i[]` follows the `xob.c` wire order).
            // Video setup additionally waits for its own channel grant.
            if (connection.session.state == SessionState.ACTIVE) {
                reportedActive = reportActiveOnce(connection, carName, reportedActive)
                openNext(connection)
            }
            setUpGrantedChannels(connection, pending)
        }
    }

    /**
     * Reports the session active once: phone status leaves Connecting and the
     * control-24 call-availability verdict (parsed protocol-side into
     * session.callAvailable) mirrors into the phone flows with the session.
     */
    private fun reportActiveOnce(connection: GalConnection, carName: String, reported: Boolean): Boolean {
        if (reported) return true
        AutoSessionState.onActive(carName)
        connection.session.callAvailable?.let {
            AutoSessionState.onCallAvailability(it)
        }
        return true
    }

    /** Tracks which channel grants have been acted on; one flag per owner. */
    private class ChannelSetups {
        var video = false
        var messaging = false
        var input = false
        var sensors = false
        var guidance = false
        var navStatus = false
        var audioSys = false
        var audioMedia = false
        var mic = false
    }

    /**
     * Runs each channel's grant-time setup once its channel opens: video
     * setup waits for its grant like ch14 mirroring, ch8 binding, sensor
     * subscribes, ch3 claiming, the ch10 stub, ch4/ch5 sink claims and mic
     * acking wait for theirs. Acting before the HU opens the channel earns a
     * bare 0xff.
     */
    private fun setUpGrantedChannels(connection: GalConnection, pending: ChannelSetups) {
        setUpMediaChannels(connection, pending)
        setUpSensorChannels(connection, pending)
        setUpAudioChannels(connection, pending)
    }

    private fun setUpMediaChannels(connection: GalConnection, pending: ChannelSetups) {
        val open = connection.session.openChannels
        if (!pending.video && GalService.VIDEO_SINK.id in open) {
            pending.video = true
            video?.requestSetup()
        }
        // ch14 starts mirroring on its own grant, like video setup waits
        // for its grant: posting before the HU opens the channel earns a
        // bare 0xff.
        if (!pending.messaging && GalService.NOTIFICATION.id in open) {
            pending.messaging = true
            messaging?.onChannelOpen()
        }
        // The input entry binds on its own grant, like video setup and ch14
        // mirroring wait for theirs: binding before the HU opens the
        // channel earns a bare 0xff.
        val boundInput = inputChannelId
        if (!pending.input && boundInput != null && boundInput in open) {
            pending.input = true
            input?.requestBinding()
        }
    }

    private fun setUpSensorChannels(connection: GalConnection, pending: ChannelSetups) {
        val open = connection.session.openChannels
        // The sensor entry subscribes on its own grant: subscribing
        // before the HU opens the channel earns a bare 0xff.
        val boundSensor = sensorChannelId
        if (!pending.sensors && boundSensor != null && boundSensor in open) {
            pending.sensors = true
            sensors?.onChannelOpen()
        }
        // ch3 claims the sink on its own grant, then stays idle: a
        // started stream the phone never feeds is worse than an idle
        // sink the head unit expects no frames from.
        if (!pending.guidance && GalService.AUDIO_SINK_GUIDANCE.id in open) {
            pending.guidance = true
            guidance?.onChannelOpen()
        }
        // ch10 posts the inactive stub on its own grant, like the rest:
        // posting before the HU opens the channel earns a bare 0xff.
        if (!pending.navStatus && GalService.NAVIGATION_STATUS.id in open) {
            pending.navStatus = true
            navStatus?.onChannelOpen()
        }
    }

    private fun setUpAudioChannels(connection: GalConnection, pending: ChannelSetups) {
        val open = connection.session.openChannels
        // ch4/ch5 claim their sinks on their own grants: setup before the
        // HU opens the channel earns a bare 0xff.
        if (!pending.audioSys && AudioSinkRole.SYSTEM.serviceId in open) {
            pending.audioSys = true
            audioSys?.requestSetup()
        }
        if (!pending.audioMedia && AudioSinkRole.MEDIA.serviceId in open) {
            pending.audioMedia = true
            audioMedia?.requestSetup()
        }
        // The mic entry starts acking on its own grant: the head unit opens
        // it and starts talking, and we ack from the first chunk.
        val boundMic = micChannelId
        if (!pending.mic && boundMic != null && boundMic in open) {
            pending.mic = true
            mic?.onChannelOpen()
        }
    }

    internal var video: VideoSinkChannel? = null

    /**
     * The Phase 2 input owner, created when the entry carrying `input_source`
     * opens in [openNext] like the video sink (DHU 2.0: service 3; gearhead
     * rro: 8 -- binding is payload-driven, see [openNext]). Outlives nothing:
     * released with the session above. Injection gates on the arbitrated
     * input flag and scales into the video sink's display size; the sinks
     * forward into the car UI and count as consumed when the render pair is
     * up.
     */
    internal var input: InputChannel? = null
    /** Discovery-bound input channel id; null until the input entry opens. */
    internal var inputChannelId: Int? = null

    /**
     * The Phase 7 messaging owner, created when ch14 opens in [openNext] like
     * the video sink. Outlives nothing: released with the session above.
     *
     * Audio seam: [CarMessagingAudio] (TTS on the ch4 system sink, voice
     * replies on the ch6 mic source); [MessagingAudio.NoOp] remains the seam's
     * pre-audio holder for tests. Assumptions live in `MessagingAudio.kt`.
     */
    internal var messaging: MessagingCarAppService? = null

    /**
     * The Phase 3 audio owners: system sink (ch4, transient) and media sink
     * (ch5, music), created when their channels open in [openNext] like the
     * video sink. Outlive nothing: released with the session above. Each
     * writes samples on its own thread; the pump thread never touches audio.
     * The sinks gate on the arbitrated focus snapshot, refreshed on every
     * 0x13 via the session's focus listener. Ch3 guidance stays with
     * sensors-dev's [GuidanceChannel] stub -- this owner answers only for 4/5.
     */
    internal var audioSys: AudioSinkChannel? = null
    internal var audioMedia: AudioSinkChannel? = null

    /**
     * The Phase 3 mic owner (upstream), created when the entry carrying
     * `media_source` opens in [openNext] (DHU 2.0: service 7; gearhead rro:
     * 6 -- binding is payload-driven). Outlives nothing: released with the
     * session above. Retention is permission-gated ([MicPermission]) --
     * without the grant chunks are acked and counted only.
     */
    internal var mic: MicSourceChannel? = null
    /** Discovery-bound mic channel id; null until the mic entry opens. */
    internal var micChannelId: Int? = null

    /**
     * Phone-side TTS feeding the system sink. Outlives nothing: started with
     * the session (before any channel grant -- the first notification may
     * arrive before ch4 opens), stopped with it. Feeds through the sink's
     * thread-safe `feedWav`; never blocks the caller.
     */
    internal var tts: CarTts? = null

    /**
     * The Phase 5 sensor owner, created when the entry carrying
     * `sensor_source` opens in [openNext] like the video sink (DHU 2.0:
     * service 1; gearhead rro: 7 -- binding is payload-driven). Outlives
     * nothing: released with the session above. Subscribes to the stub set
     * on its grant (parked, night-follows-phone, last-known location, speed
     * 0); live values land with Phase 6 maps-dev through the [SensorChannel]
     * seams.
     */
    internal var sensors: SensorChannel? = null
    /** Discovery-bound sensor channel id; null until the sensor entry opens. */
    internal var sensorChannelId: Int? = null

    /**
     * The Phase 5 guidance owner, created when ch3 opens in [openNext] like
     * the video sink. Outlives nothing: released with the session above.
     * Claims the sink on its grant through audio-dev's shared AudioCodec sink
     * shape and stays idle; live TTS starts the stream through the
     * [GuidanceChannel] seam.
     */
    internal var guidance: GuidanceChannel? = null

    /**
     * The Phase 5 nav-status owner, created when ch10 opens in [openNext]
     * like the video sink. Outlives nothing: released with the session above.
     * Posts the inactive stub on its grant; live turn updates land with Phase
     * 6 maps-dev through [NavStatusChannel.postStatus].
     */
    internal var navStatus: NavStatusChannel? = null

    /** Outlives sessions; owned by the service, not the pump thread. */
    internal var mediaMonitor: MediaPlaybackMonitor? = null

    /**
     * Session-scoped phone status (signal/battery/DND/badge) for the rail
     * cluster. Started with the session like TTS; the cluster pulls the
     * latest snapshot on its 1Hz tick. Stopped with the session.
     */
    internal var phoneStatusMonitor: PhoneStatusMonitor? = null

    /**
     * Session-scoped guidance, like TTS: started with the session (position
     * live from the first fix), stopped with it. Snapshots push the nav
     * banner fallback; with no location permission the monitor holds
     * last-known. The car-app host rides alongside through
     * [carAppHostSession] (split for the 800-line limit).
     */
    internal var guidanceMonitor: NavGuidanceMonitor? = null
    internal val carAppHostSession = CarAppHostSession(this) { video }

    /**
     * Seeds the credential-expiry flow once per service start, off the pump thread.
     *
     * Reads the shipped leaf through the public asset constant, so a rotation that
     * swaps the PEMs updates the session card with no code change. On parse
     * failure the flow stays null (unknown) instead of failing the service.
     */
    private fun publishCredentialExpiry() {
        runCatching {
            assets.open(GalCredential.CERT_ASSET).use { it.readBytes().decodeToString() }
        }.onSuccess { certPem ->
            AutoSessionState.onCredentialExpiry(GalCredential.daysRemaining(certPem))
        }.onFailure {
            Log.status(TAG, "could not read GAL leaf expiry", it)
        }
    }

    companion object {
        internal const val TAG = "MaAuto.Service"

        /** Radix for hex message-id logging. */
        private const val HEX_RADIX = 16

        /**
         * Interim car name until the head unit reports its own. The GAL services the DHU
         * advertises carry no human-readable name, so the status screen says where the
         * pixels are going rather than guessing a make.
         */
        private const val CAR_NAME = "Desktop Head Unit"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProjectionService::class.java))
        }
    }
}
