package com.vayunmathur.auto.protocol

/**
 * GAL message types. Every channel payload is `[2-byte big-endian type][protobuf]`.
 *
 * The control channel uses small integers; every service channel uses 0x8000-based ones,
 * with 0x0000/0x0001 reserved for bulk media data.
 *
 * These are **wire** values. Reading them out of gearhead is error-prone in one specific
 * way: `jdk` and `jdi` dispatch on `wub.o(id)`, which maps a wire id to a 1-based enum
 * index and so is `id + 1` over this range, while the `k(id, …)` send calls are already
 * raw. Taking those comparisons at face value yields every received media type one too
 * high. The values below are the raw wire ids, and they agree with the public
 * aasdk/openauto tables.
 */
object GalMessage {

    /** Control channel, channel id 0. */
    object Control {
        const val VERSION_REQUEST = 1
        const val VERSION_RESPONSE = 2

        /** Raw TLS handshake bytes, wrapped and unwrapped by the SSL engine. */
        const val SSL_HANDSHAKE = 3
        const val AUTH_COMPLETE = 4

        /** The phone asks; the head unit answers with its service list. */
        const val SERVICE_DISCOVERY_REQUEST = 5
        const val SERVICE_DISCOVERY_RESPONSE = 6

        const val CHANNEL_OPEN_REQUEST = 7
        const val CHANNEL_OPEN_RESPONSE = 8

        const val PING_REQUEST = 11
        const val PING_RESPONSE = 12

        const val NAVIGATION_FOCUS_NOTIFICATION = 14

        const val BYEBYE_REQUEST = 15
        const val BYEBYE_RESPONSE = 16

        const val AUDIO_FOCUS_REQUEST = 18
        const val AUDIO_FOCUS_NOTIFICATION = 19

        const val CALL_AVAILABILITY_STATUS = 24

        /** Hot-add or replace a single service after the initial discovery. */
        const val SERVICE_DISCOVERY_UPDATE = 26

        const val MESSAGE_ERROR = 255
        const val FRAMING_ERROR = 0xFFFF
    }

    /** Shared by the video sink, the three audio sinks and the microphone source. */
    object Media {
        /** Bulk payload prefixed with an 8-byte timestamp. */
        const val DATA_WITH_TIMESTAMP = 0x0000

        /** Bulk payload with no timestamp. */
        const val DATA = 0x0001

        const val SETUP_REQUEST = 0x8000
        const val START_REQUEST = 0x8001
        const val STOP_REQUEST = 0x8002
        const val CONFIG = 0x8003
        const val ACK = 0x8004
    }

    /** Video sink, on top of [Media]. */
    object Video {
        const val FOCUS_REQUEST = 0x8007
        const val FOCUS_INDICATION = 0x8008
        const val UPDATE_UI_CONFIG_REQUEST = 0x800A
    }

    /** Microphone source. The car owns the microphone, so the phone receives and acks. */
    object Microphone {
        const val REQUEST = 0x8006
    }

    /** Input source: touch, keys and rotary, all reported by the head unit. */
    object Input {
        const val REPORT = 0x8001
        const val KEY_BINDING_REQUEST = 0x8002
        const val KEY_BINDING_RESPONSE = 0x8003

        /**
         * Phone -> HU: one input-stream feedback event (`xjs`, `InputFeedback`
         * proto) from the discovery-advertised set. Aliased like the rest of
         * the 0x8004 range (MediaAck, SensorError, messaging ACTION), so
         * owners route by channel id first.
         */
        const val FEEDBACK = 0x8004
    }

    /** Sensor source. [SENSOR_REQUEST] is synchronous with a 2 s timeout. */
    object Sensor {
        const val SENSOR_REQUEST = 0x8001
        const val SENSOR_RESPONSE = 0x8002
        const val SENSOR_BATCH = 0x8003
        const val SENSOR_ERROR = 0x8004

        /** A [SENSOR_REQUEST] with this update period unsubscribes instead. */
        const val UNSUBSCRIBE_PERIOD = -1L
    }

    /**
     * Navigation-status channel, service 10.
     *
     * MA sender-defined types: the teardown recovered the service descriptor
     * slot but never the channel message IDs, so STATUS frames the Phase 5
     * stub (one well-formed "no guidance" post on the grant, live turn
     * updates with Phase 6 maps-dev). A head unit that does not speak it
     * answers with a bare MessageError (0xff) on ch10, which is observed and
     * non-fatal -- the bring-up and video carry on. Payloads are the
     * `gal/navigation.proto` DTOs.
     *
     * Like [Sensor], this channel aliases the 0x8000 range (STATUS shares its
     * value with the sensor request and others), so owners must scope every
     * parse by channel id -- the 0x04 CONTROL frame flag never enters routing
     * (HANDOFF.md section 9).
     */
    object NavigationStatus {
        /** Phone -> HU: turn-guidance state (gal.NavigationStatus proto). */
        const val STATUS = 0x8001
    }

    /**
     * Notification (messaging) channel, service 14.
     *
     * MA sender-defined types: the teardown recovered the service descriptor
     * slot but never the xjm channel message IDs, so these frame the Phase 7
     * set (threads, body, reply, mark-read) as a DHU-tolerated stub. A head
     * unit that does not speak them answers with a bare MessageError (0xff)
     * on ch14, which is observed and non-fatal -- the bring-up and video
     * carry on. Payloads are the `gal/notification.proto` DTOs.
     */
    object Notification {
        /** Phone -> HU: thread-list snapshot ([MessagingThreads]). */
        const val THREADS = 0x8001

        /** Phone -> HU: one message body posted ([MessagingMessage]). */
        const val MESSAGE = 0x8002

        /** Phone -> HU: thread dismissed or read elsewhere ([MessagingDismiss]). */
        const val DISMISS = 0x8003

        /** HU -> phone: user action -- reply, mark-read, voice-reply ([MessagingAction]). */
        const val ACTION = 0x8004
    }

    /**
     * Audio sink sync pulse (services 4/5). The head unit answers setup/start
     * with a no-payload 0x800B from the shared `jdk` sink table; presumed
     * stream sync, observed and counted but never answered.
     */
    object Audio {
        const val SYNC = 0x800B
    }
}

/**
 * Service ids as advertised in the head unit's discovery response, which double as the
 * channel ids. Mirrors gearhead's internal `rro` enum.
 *
 * The ids live at file level (not in the companion): enum entries are initialised
 * before the companion object, so entries cannot read companion constants.
 */
private const val AUDIO_SINK_GUIDANCE_ID = 3
private const val AUDIO_SINK_SYSTEM_ID = 4
private const val AUDIO_SINK_MEDIA_ID = 5
private const val AUDIO_SOURCE_ID = 6
private const val SENSOR_SOURCE_ID = 7
private const val INPUT_SOURCE_ID = 8
private const val BLUETOOTH_ID = 9
private const val NAVIGATION_STATUS_ID = 10
private const val MEDIA_PLAYBACK_STATUS_ID = 11
private const val MEDIA_BROWSER_ID = 12
private const val PHONE_STATUS_ID = 13
private const val NOTIFICATION_ID = 14
private const val RADIO_ID = 15
private const val VENDOR_EXTENSION_ID = 16
private const val WIFI_PROJECTION_ID = 17
private const val WIFI_DISCOVERY_ID = 18
private const val CAR_CONTROL_ID = 19
private const val CAR_LOCAL_MEDIA_ID = 20
private const val BUFFERED_MEDIA_SINK_ID = 21
private const val CAR_INTENT_ID = 22

/** A GAL service as advertised in discovery; its [id] doubles as the channel id for its traffic. */
enum class GalService(val id: Int) {
    CONTROL(1),
    VIDEO_SINK(2),
    AUDIO_SINK_GUIDANCE(AUDIO_SINK_GUIDANCE_ID),
    AUDIO_SINK_SYSTEM(AUDIO_SINK_SYSTEM_ID),
    AUDIO_SINK_MEDIA(AUDIO_SINK_MEDIA_ID),
    AUDIO_SOURCE(AUDIO_SOURCE_ID),
    SENSOR_SOURCE(SENSOR_SOURCE_ID),
    INPUT_SOURCE(INPUT_SOURCE_ID),
    BLUETOOTH(BLUETOOTH_ID),
    NAVIGATION_STATUS(NAVIGATION_STATUS_ID),
    MEDIA_PLAYBACK_STATUS(MEDIA_PLAYBACK_STATUS_ID),
    MEDIA_BROWSER(MEDIA_BROWSER_ID),
    PHONE_STATUS(PHONE_STATUS_ID),
    NOTIFICATION(NOTIFICATION_ID),
    RADIO(RADIO_ID),
    VENDOR_EXTENSION(VENDOR_EXTENSION_ID),
    WIFI_PROJECTION(WIFI_PROJECTION_ID),
    WIFI_DISCOVERY(WIFI_DISCOVERY_ID),
    CAR_CONTROL(CAR_CONTROL_ID),
    CAR_LOCAL_MEDIA(CAR_LOCAL_MEDIA_ID),
    BUFFERED_MEDIA_SINK(BUFFERED_MEDIA_SINK_ID),
    CAR_INTENT(CAR_INTENT_ID),
    ;

    /** Lookup of a service by its wire (and channel) id. */
    companion object {
        private val byId = entries.associateBy(GalService::id)

        fun fromId(id: Int): GalService? = byId[id]
    }
}

/**
 * GAL 11/12 gap (Phase 4, MA Auto sender): the teardown recovered the
 * `MEDIA_PLAYBACK_STATUS` (`xkn`, control.proto field 9) and `MEDIA_BROWSER`
 * (`xki`, field 11) descriptor fields but never their channel message IDs, so
 * MA Auto neither opens these channels' semantics nor answers them. Now-playing
 * is served from the on-device media session and rendered into the ch2 video
 * stream instead; inbound 11/12 traffic is NOT_SUPPORTED: observed and ignored.
 */
fun isMediaBrowserChannel(channelId: Int): Boolean =
    channelId == GalService.MEDIA_PLAYBACK_STATUS.id || channelId == GalService.MEDIA_BROWSER.id
