package com.vayunmathur.findfamily.service
import com.vayunmathur.findfamily.domain.FamilyLocationProtocol

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.network.Networking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Bound service that streams live family-member locations to co-signed clients
 * (the maps app) over a [Messenger] channel — see [FamilyLocationProtocol].
 *
 * Behaviour:
 *  - A client binds, then sends [FamilyLocationProtocol.MSG_REGISTER] with its
 *    own `replyTo` Messenger. The service registers it, immediately pushes the
 *    last known snapshot, and — if this is the first client — starts collecting
 *    the existing family-location state.
 *  - On each change the service pushes a [FamilyLocationProtocol.MSG_LOCATIONS]
 *    snapshot (parallel arrays) to every registered client.
 *  - The collection runs ONLY while at least one client is registered: when the
 *    last client unregisters (or unbinds, or dies) it is torn down so there is
 *    no ongoing cost otherwise.
 *
 * The data source is the process-wide [FindFamilyRepository] (the same Room-
 * backed state the UI and the tracking service already read), so this exposes
 * the live present-location of everyone without any new storage.
 *
 * Runs entirely on the main looper: the incoming [Handler], the client set and
 * the flow collection all touch state on the main thread, so no locking is
 * needed. Building a handful of parallel arrays per emission is trivial; the
 * Room flows do their IO upstream on their own dispatcher.
 */
class FamilyLocationService : Service() {

    private val repository: FindFamilyRepository by lazy {
        FindFamilyRepository.get(applicationContext)
    }

    private val clients = mutableSetOf<Messenger>()
    private var scope: CoroutineScope? = null
    private var streamJob: Job? = null

    /** Last snapshot pushed — replayed to a client the instant it registers so a
     *  freshly opened map draws pins without waiting for the next change. */
    private var lastSnapshot: Bundle? = null

    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                FamilyLocationProtocol.MSG_REGISTER -> {
                    msg.replyTo?.let { register(it) }
                    true
                }
                FamilyLocationProtocol.MSG_UNREGISTER -> {
                    msg.replyTo?.let { unregister(it) }
                    true
                }
                else -> false
            }
        }
    )

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // A client process disconnected without unregistering (or all did). Drop
        // everything and stop streaming; default (false) means no onRebind.
        clients.clear()
        stopStreaming()
        return false
    }

    override fun onDestroy() {
        clients.clear()
        stopStreaming()
        super.onDestroy()
    }

    private fun register(client: Messenger) {
        clients.add(client)
        lastSnapshot?.let { push(client, it) }
        ensureStreaming()
    }

    private fun unregister(client: Messenger) {
        clients.remove(client)
        if (clients.isEmpty()) stopStreaming()
    }

    /** Start the collection lazily on the first registered client. */
    private fun ensureStreaming() {
        if (streamJob != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        streamJob = s.launch {
            combine(
                repository.latestLocationByUser,
                repository.users,
            ) { locations, users ->
                val namesById = users.associate { it.id to it.name }
                buildSnapshot(locations, namesById)
            }.collect { snapshot ->
                lastSnapshot = snapshot
                broadcast(snapshot)
            }
        }
    }

    private fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        scope?.cancel()
        scope = null
        lastSnapshot = null
    }

    private fun broadcast(snapshot: Bundle) {
        // Copy so a dead-client removal doesn't mutate the set mid-iteration.
        val dead = mutableListOf<Messenger>()
        for (client in clients.toList()) {
            if (!push(client, snapshot)) dead.add(client)
        }
        if (dead.isNotEmpty()) {
            clients.removeAll(dead.toSet())
            if (clients.isEmpty()) stopStreaming()
        }
    }

    /** Send one snapshot to a client; returns false if the client is gone. */
    private fun push(client: Messenger, snapshot: Bundle): Boolean {
        return try {
            val msg = Message.obtain(null, FamilyLocationProtocol.MSG_LOCATIONS)
            msg.data = Bundle(snapshot)
            client.send(msg)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun buildSnapshot(
        locations: Map<Long, com.vayunmathur.findfamily.data.LocationValue>,
        namesById: Map<Long, String>,
    ): Bundle {
        val ids = ArrayList<Long>(locations.size)
        val names = ArrayList<String>(locations.size)
        val lats = ArrayList<Double>(locations.size)
        val lngs = ArrayList<Double>(locations.size)
        val timestamps = ArrayList<Long>(locations.size)
        val batteries = ArrayList<Float>(locations.size)

        // This device's own row is deliberately not sent. Maps already draws the user's own
        // position as the location puck, from its own fix rather than a round trip through here,
        // so including it would put a second, staler marker on top of the puck.
        //
        // Filtered at the source rather than in the client: the wire format has no self marker,
        // and adding one would mean every consumer had to remember to honour it. `userid` is 0
        // until the identity bootstrap has run, and no real id is 0, so the guard also stops a
        // pre-init snapshot dropping an innocent row.
        val self = Networking.userid
        for ((id, location) in locations) {
            appendLocationRow(ids, names, lats, lngs, timestamps, batteries, self, id, location, namesById)
        }

        return Bundle().apply {
            putLongArray(FamilyLocationProtocol.KEY_IDS, ids.toLongArray())
            putStringArray(FamilyLocationProtocol.KEY_NAMES, names.toTypedArray())
            putDoubleArray(FamilyLocationProtocol.KEY_LATS, lats.toDoubleArray())
            putDoubleArray(FamilyLocationProtocol.KEY_LNGS, lngs.toDoubleArray())
            putLongArray(FamilyLocationProtocol.KEY_TIMESTAMPS, timestamps.toLongArray())
            putFloatArray(FamilyLocationProtocol.KEY_BATTERIES, batteries.toFloatArray())
        }
    }

    private fun appendLocationRow(
        ids: ArrayList<Long>,
        names: ArrayList<String>,
        lats: ArrayList<Double>,
        lngs: ArrayList<Double>,
        timestamps: ArrayList<Long>,
        batteries: ArrayList<Float>,
        self: Long,
        id: Long,
        location: com.vayunmathur.findfamily.data.LocationValue,
        namesById: Map<Long, String>,
    ) {
        if (self != 0L && id == self) return
        // Skip anyone we can't label — a nameless pin isn't actionable.
        val name = namesById[id] ?: return
        ids.add(id)
        names.add(name)
        lats.add(location.coord.lat)
        lngs.add(location.coord.lon)
        timestamps.add(location.timestamp.toEpochMilliseconds())
        batteries.add(location.battery)
    }
}
