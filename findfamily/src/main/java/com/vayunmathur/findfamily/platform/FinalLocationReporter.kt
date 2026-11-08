package com.vayunmathur.findfamily.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.SystemClock
import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.util.Networking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Publishes one last position when the device is about to stop reporting — on shutdown or
 * reboot, and when the battery crosses the system's low threshold.
 *
 * This is the cheap answer to "where did I lose it": a phone that is switched off or flat is
 * indistinguishable from one out of coverage, and the last live heartbeat can be half an hour
 * old. A parting report collapses that to the moment the device actually went away.
 *
 * Three things this deliberately does *not* do:
 *
 * - **It does not wait for a fresh fix.** The shutdown window is a couple of seconds, and a GPS
 *   lock takes tens. Whatever the tracking service last held is what gets sent, however stale.
 * - **It does not claim that position is current.** The fix's own measurement time goes out as
 *   [LocationValue.timestamp] and the moment of the shutdown as [LocationValue.reportedAt], so a
 *   receiver can say "last seen twenty minutes before it switched off" rather than dropping a pin
 *   where the phone demonstrably is not.
 * - **It does not reconnect, look up keys, or touch the database.** Every one of those can block
 *   for seconds. It publishes to peers whose keys are already cached, over a socket that is
 *   already open, or it publishes nothing and says so in the log.
 *
 * Registration has to happen at runtime, from the running tracking service. Both halves of that
 * were checked against the platform source in this tree:
 *
 * - `ShutdownThread.java:548` adds `FLAG_RECEIVER_REGISTERED_ONLY` to the shutdown broadcast, and
 *   `Intent.ACTION_SHUTDOWN`'s own javadoc says it has only gone to `registerReceiver` callers
 *   since P. A manifest entry would never fire.
 * - `frameworks/base/data/etc/framework-sysconfig.xml` is the `allow-implicit-broadcast`
 *   exemption list, and its 23 entries are all telephony, SMS, media and package actions.
 *   `ACTION_BATTERY_LOW` is not among them, so it is caught by the API-26 background limits.
 *   Same conclusion, different mechanism.
 *
 * `ACTION_REBOOT` is in the filter as belt and braces. A reboot on this platform goes through
 * ShutdownThread and arrives as `ACTION_SHUTDOWN`; nothing in `services/core` broadcasts
 * `ACTION_REBOOT`, though Watchdog is built around receiving it, so it is not impossible.
 *
 * The corollary of runtime registration is that this only works while the tracking service is
 * alive — if the user has tracking switched off, there is nothing to report from.
 */
object FinalLocationReporter {

    private const val TAG = "FF-FinalReport"

    /**
     * How long the shutdown broadcast may be held open.
     *
     * [BroadcastReceiver.goAsync] keeps the broadcast in flight until `finish()`, and
     * `ShutdownThread` blocks on exactly that completion callback — so this is a delay to the
     * user's power-off, not free time. Its own ceiling is `MAX_BROADCAST_TIME = 10s`
     * (`ShutdownThread.java:81`), after which it logs "Shutdown broadcast timed out" and carries
     * on regardless. A quarter of that is long enough for a write on an already-open socket and
     * short enough that a wedged network stack cannot make the phone look hung.
     */
    private val BUDGET = 2.5.seconds

    /**
     * Separate budget for arming the powered-off beacon, which runs concurrently with the report
     * rather than after it — they contend for nothing, and serialising them would put the sum of
     * both on the user's power-off. The work is one binder call carrying 256 EIDs, which the
     * framework splits into ~22 vendor HCI commands to the controller. I have not been able to
     * measure how long that takes (no device), so this is a ceiling chosen to be invisible next
     * to [BUDGET], not a measurement.
     */
    private val BEACON_BUDGET = 2.seconds

    private var receiver: BroadcastReceiver? = null

    /** Set once a shutdown report goes out, so ACTION_REBOOT and ACTION_SHUTDOWN don't both fire. */
    private val shutdownReported = AtomicBoolean(false)

    /**
     * Deliberately not the tracking service's scope: that is cancelled in `onDestroy`, and the
     * whole point of this class is to still be able to work while everything is being torn down.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Start listening. [lastFix] and [roster] are read at broadcast time, not now, so the caller
     * keeps ownership of both. [roster] must already have the user's sharing switches applied —
     * this class makes no policy decisions of its own.
     */
    fun start(
        context: Context,
        lastFix: () -> Location?,
        roster: () -> List<DirectBootStore.Target>,
    ) {
        if (receiver != null) return
        val appContext = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val source = when (intent.action) {
                    Intent.ACTION_SHUTDOWN, Intent.ACTION_REBOOT -> LocationSource.SHUTDOWN
                    Intent.ACTION_BATTERY_LOW -> LocationSource.BATTERY_LOW
                    else -> return
                }
                if (source == LocationSource.SHUTDOWN && !shutdownReported.compareAndSet(false, true)) return

                val pending = goAsync()
                scope.launch {
                    sendFinalReport(source, appContext, lastFix, roster, beaconScope = this, pending)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_REBOOT)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        appContext.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    fun stop(context: Context) {
        val r = receiver ?: return
        receiver = null
        runCatching { context.applicationContext.unregisterReceiver(r) }
    }

    /**
     * Send the last-chance location report for [source], optionally arming the
     * powered-off beacon first. Broad catch is deliberate: this crosses the
     * network, DataStore, and vendor HAL boundaries, and a parting report that
     * did not make it out must never be the reason the device refuses to
     * power off.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendFinalReport(
        source: LocationSource,
        appContext: Context,
        lastFix: () -> Location?,
        roster: () -> List<DirectBootStore.Target>,
        beaconScope: CoroutineScope,
        pending: BroadcastReceiver.PendingResult,
    ) {
        try {
            // Only on a real shutdown: the controller only advertises once the AP is
            // actually down, so arming on BATTERY_LOW would be pointless work in the
            // one window where the battery is the scarce thing.
            val beacon = if (source == LocationSource.SHUTDOWN) {
                // runCatching is load-bearing. This async is a child of the coroutine
                // sending the location report, so anything thrown out of it cancels
                // that report. armForShutdown catches its own expected failures, but
                // the work before its try block reads DataStore and derives 256 EIDs,
                // and a throw from there would cost us the fix. The report is the
                // proven feature and the beacon is speculative; the beacon never gets
                // to take the report down with it.
                //
                // Note the ordering: withTimeoutOrNull inside, runCatching outside.
                // The reverse would let runCatching swallow the timeout's own
                // CancellationException and defeat the budget.
                beaconScope.async {
                    runCatching {
                        withTimeoutOrNull(BEACON_BUDGET) {
                            PoweredOffBeacon.armForShutdown(appContext)
                        }
                    }.onFailure { Log.status(TAG, "beacon arming threw", it) }.getOrNull()
                }
            } else {
                null
            }
            val done = withTimeoutOrNull(BUDGET) {
                report(appContext, source, lastFix(), roster())
            }
            if (beacon?.await() == null && source == LocationSource.SHUTDOWN) {
                Log.status(TAG, "powered-off beacon not armed")
            }
            // Swallowed on purpose: a parting report that did not make it out must
            // never be the reason the device refuses to power off.
            if (done == null) Log.status(TAG, "$source report gave up after $BUDGET")
        } catch (e: Exception) {
            Log.status(TAG, "$source report failed", e)
        } finally {
            pending.finish()
        }
    }

    private suspend fun report(
        context: Context,
        source: LocationSource,
        location: Location?,
        targets: List<DirectBootStore.Target>,
    ) {
        if (location == null) {
            Log.status(TAG, "$source: no fix has ever been taken, nothing to report")
            return
        }
        if (targets.isEmpty()) {
            Log.status(TAG, "$source: nobody to report to")
            return
        }
        if (Networking.userid == 0L) {
            Log.status(TAG, "$source: identity not loaded, nothing to report as")
            return
        }
        if (!Networking.liveConnected) {
            // Reconnecting costs a TLS handshake we do not have time for, and on shutdown the
            // radios are going down anyway. Saying so beats silently sending nothing.
            Log.status(TAG, "$source: socket is down, dropping the report")
            return
        }

        val now = Clock.System.now()
        val battery = runCatching {
            (context.getSystemService(BatteryManager::class.java))
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toFloat()
        }.getOrNull() ?: 0f

        val value = LocationValue(
            Networking.userid,
            Coord(location.latitude, location.longitude),
            if (location.hasSpeed()) location.speed else 0f,
            location.accuracy,
            measuredAt(location, now),
            battery,
            reportedAt = now,
            source = source,
        )
        Log.status(TAG, "$source: reporting a fix ${now - value.timestamp} old to ${targets.size} peer(s)")
        targets.forEach {
            // Parting reports ride the same seal path, pair ID included — a shutdown report
            // without one would be dropped as unauthenticated once the peer has pinned us.
            runCatching { Networking.publishLocation(value, it.id, it.bundle, it.pairId) }
                .onFailure { e -> Log.status(TAG, "$source publish to ${it.id.toULong()} failed", e) }
        }
    }

    /**
     * When the fix was actually taken, derived from the monotonic clock rather than
     * [Location.time]. Wall-clock time can be stepped by NTP or the user between the fix and
     * now, and the number this feeds is a claim about how stale the position is — an hour of
     * drift there would be an hour of lying about where someone last was.
     */
    private fun measuredAt(location: Location, now: Instant): Instant {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        return now - ageMs.coerceAtLeast(0).milliseconds
    }
}
