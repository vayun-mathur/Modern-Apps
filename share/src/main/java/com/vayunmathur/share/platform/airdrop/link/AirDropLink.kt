package com.vayunmathur.share.platform.airdrop.link

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.vayunmathur.library.log.Log
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import javax.net.SocketFactory

private const val TAG = "AirDropLink"

/**
 * The network AirDrop runs over: Mosey's AWDL interface when the device has the HAL, else the
 * phone's ordinary Wi-Fi network.
 *
 * Only Mosey reaches Apple devices in practice — iPhones and Macs only offer AirDrop over
 * AWDL — but the Wi-Fi link keeps the whole stack exercisable on hardware without the HAL.
 */
sealed interface AirDropLink {
    val kind: Kind

    /** Network to scope mDNS and sockets to, or null for the system default. */
    val network: Network?

    val socketFactory: SocketFactory get() = network?.socketFactory ?: SocketFactory.getDefault()

    /** Brings the link up; false if it could not be. Idempotent. */
    suspend fun activate(): Boolean

    fun deactivate()

    fun onTransferStarted(peer: InetAddress) = Unit
    fun onTransferEnded(peer: InetAddress) = Unit

    /** Kinds of kind. */
    enum class Kind { Mosey, WifiLan }
}

/** Wifi Lan Link: a context implementation for wifi lan link. */
class WifiLanLink(private val context: Context) : AirDropLink {
    override val kind = AirDropLink.Kind.WifiLan

    override val network: Network?
        get() {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            return cm.activeNetwork?.takeIf {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }

    override suspend fun activate(): Boolean = true

    override fun deactivate() = Unit
}

/**
 * Drives the Mosey HAL and publishes its `mosey0` interface as a local network, as Mosey's
 * `MoseyController` does.
 *
 * Needs, on the OS side: the HAL itself, `NETWORK_FACTORY`, `CONNECTIVITY_USE_RESTRICTED_NETWORKS`
 * and `NEARBY_WIFI_DEVICES` granted to a privileged :share, and hidden-API access for
 * `ServiceManager`. Any of those missing makes [activate] return false, and the selector falls
 * back to [WifiLanLink]. API 34+: Mosey itself requires a far newer OS, and the agent APIs
 * it needs are not all present earlier.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class MoseyLink(private val context: Context, private val hal: MoseyHal) : AirDropLink {
    override val kind = AirDropLink.Kind.Mosey

    private val token = Binder()
    private var agent: MoseyNetworkAgent? = null
    private var countryCode: String? = null

    override val network: Network? get() = agent?.network

    // Broad catch is deliberate: the HAL is a binder/shared-library boundary and the network
    // agent calls throw SecurityException plus undocumented RuntimeExceptions without privilege.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun activate(): Boolean {
        if (agent?.network != null) return true
        val country = countryCode() ?: run {
            Log.status(TAG, "no regulatory country code yet; not starting Mosey")
            return false
        }
        countryCode = country
        return try {
            val hopping = MoseyConfig.isChannelHoppingSupported(runCatching { hal.hardwareCapability() }.getOrNull())
            val channels = channels()
            val sta = staChannel()
            val wifi = context.getSystemService(WifiManager::class.java)
            val dbs = wifi?.isDualBandSimultaneousSupported == true
            Log.status(
                TAG,
                "starting Mosey ${hal.backend.label} ${hal.version}: channels=${channels.toList()} " +
                    "sta=$sta dbs=$dbs hopping=$hopping country=$country",
            )
            hal.update(country, MoseyConfig.staChannelUpdate(sta))
            hal.start(channels, token, MoseyConfig.start(channels, sta, dbs))
            val up = MoseyNetworkAgent.bringUp(context) ?: run {
                Log.status(TAG, "mosey0 never appeared; stopping the HAL")
                runCatching { hal.stop() }
                return false
            }
            agent = up
            true
        } catch (e: Exception) {
            Log.status(TAG, "Mosey start failed", e)
            runCatching { hal.stop() }
            false
        }
    }

    override fun deactivate() {
        agent?.tearDown()
        agent = null
        runCatching { hal.stop() }.onFailure { Log.status(TAG, "Mosey stop failed", it) }
    }

    override fun onTransferStarted(peer: InetAddress) = notifyDaemon(peer, started = true)

    override fun onTransferEnded(peer: InetAddress) = notifyDaemon(peer, started = false)

    /** The daemon keeps the AWDL link awake for a peer while a transfer to it is live. */
    private fun notifyDaemon(peer: InetAddress, started: Boolean) {
        val v6 = peer as? Inet6Address ?: return
        val country = countryCode ?: return
        val config = if (started) MoseyConfig.transferStarted(v6) else MoseyConfig.transferEnded(v6)
        runCatching { hal.update(country, config) }
            .onFailure { Log.status(TAG, "notify daemon (started=$started) failed", it) }
    }

    private fun channels(): IntArray {
        val wifi = context.getSystemService(WifiManager::class.java)
        return if (wifi?.is5GHzBandSupported == false) MoseyConfig.FALLBACK_CHANNELS else MoseyConfig.DEFAULT_CHANNELS
    }

    private fun staChannel(): Int? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return null
        val info = caps.transportInfo as? android.net.wifi.WifiInfo ?: return null
        return frequencyToChannel(info.frequency)
    }

    /**
     * The framework's regulatory country (`WifiManager.getCountryCode`, @SystemApi), falling
     * back to the cellular network's. Never guessed: AWDL channels are regulated.
     */
    @SuppressLint("PrivateApi")
    private fun countryCode(): String? {
        val wifi = context.getSystemService(WifiManager::class.java)
        val fromWifi =
            runCatching { WifiManager::class.java.getMethod("getCountryCode").invoke(wifi) as String? }.getOrNull()
        val fromCell = context.getSystemService(TelephonyManager::class.java)?.networkCountryIso
        return (fromWifi ?: fromCell)?.takeIf { it.length == 2 }?.uppercase(Locale.ROOT)
    }

    private fun frequencyToChannel(mhz: Int): Int? = when (mhz) {
        FREQ_CHANNEL_14_MHZ -> CHANNEL_14
        in BAND_2_4_START_MHZ..BAND_2_4_END_MHZ -> (mhz - BAND_2_4_BASE_MHZ) / CHANNEL_WIDTH_MHZ
        in BAND_5_START_MHZ..BAND_5_END_MHZ -> (mhz - BAND_5_BASE_MHZ) / CHANNEL_WIDTH_MHZ
        in BAND_6_START_MHZ..BAND_6_END_MHZ -> (mhz - BAND_6_BASE_MHZ) / CHANNEL_WIDTH_MHZ
        else -> null
    }

    private companion object {
        // Wi-Fi frequency (MHz) to channel mapping bands; channel = (freq - base) / width.
        const val FREQ_CHANNEL_14_MHZ = 2484
        const val CHANNEL_14 = 14
        const val BAND_2_4_START_MHZ = 2412
        const val BAND_2_4_END_MHZ = 2472
        const val BAND_2_4_BASE_MHZ = 2407
        const val BAND_5_START_MHZ = 5160
        const val BAND_5_END_MHZ = 5885
        const val BAND_5_BASE_MHZ = 5000
        const val BAND_6_START_MHZ = 5955
        const val BAND_6_END_MHZ = 7115
        const val BAND_6_BASE_MHZ = 5950
        const val CHANNEL_WIDTH_MHZ = 5
    }
}

/** Picks the best available link and reports which one, for logs and the send screen. */
object AirDropLinkSelector {

    /** Immutable value describing selection; carries link, mosey. */
    data class Selection(val link: AirDropLink, val mosey: MoseyStatus)

    suspend fun select(context: Context): Selection {
        val app = context.applicationContext
        val status = MoseyDetector.detect(app)
        Log.status(TAG, "Mosey: ${status.detail}")
        if (status.available && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hal = MoseyDetector.open(app)
            if (hal != null) {
                val mosey = MoseyLink(app, hal)
                if (mosey.activate()) return Selection(mosey, status)
                Log.status(TAG, "Mosey present but could not be started; using Wi-Fi")
            }
        }
        return Selection(WifiLanLink(app).also { it.activate() }, status)
    }
}
