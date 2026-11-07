@file:OptIn(ExperimentalAtomicApi::class)

package com.vayunmathur.vpn.service

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.data.VpnDatabase
import com.vayunmathur.vpn.data.endpointHost
import com.vayunmathur.vpn.data.endpointPort
import com.vayunmathur.vpn.data.toModel
import com.vayunmathur.vpn.platform.BypassList
import com.vayunmathur.vpn.util.VpnNative
import android.database.sqlite.SQLiteException
import com.vayunmathur.vpn.data.ConnectionLogDao
import com.vayunmathur.vpn.data.ConnectionLogEntity
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.FileChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Foreground VpnService that bridges Android TUN fd <-> UDP socket.
 *
 * Crypto is all in Rust/gotatun via VpnNative (Mullvad's BoringTun fork):
 * X25519 + Noise IK + ChaCha20Poly1305 + BLAKE2s, plus rekey/keepalive timers.
 *
 * Kotlin handles TUN<->UDP plumbing plus per-flow logging (packet inspection,
 * DNS snooping, SNI, per-app attribution via getConnectionOwnerUid).
 */
class VpnTunnelService : VpnService() {

    companion object {
        private const val TAG = "VpnTunnelService"
        const val ACTION_CONNECT = "com.vayunmathur.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.vayunmathur.vpn.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val NOTIFICATION_ID = 42
        const val CHANNEL_ID = "vpn_tunnel"
        private const val TLS_PORT = 443
        private const val DNS_PORT = 53
        private const val UDP_PROTOCOL = "UDP"
        private const val TCP_PROTOCOL = "TCP"
        private const val TAG_SEND_UDP = 1
        private const val TAG_INJECT_TUN = 2
        private const val TAG_KEEPALIVE = 3
        private const val DEFAULT_TUN_ADDRESS = "10.0.0.2/32"
        private const val DEFAULT_ALLOWED_IPS = "0.0.0.0/0"
        private const val DEFAULT_SESSION_NAME = "WireGuard"
        private const val MIN_MTU = 1280
        private const val MAX_MTU = 1500
        private const val IPV4_CIDR_DEFAULT = 32
        private const val IPV6_CIDR_DEFAULT = 128
        private const val DEFAULT_ROUTE_MASK = 0
        private const val IPV6_DEFAULT_ROUTE = "::"
        private const val FLUSH_INTERVAL_MS = 1500L
        private const val PUMP_DELAY_MS = 10L
        private const val TIMER_INTERVAL_MS = 100L
        private const val UDP_BUFFER_SIZE = 65535
        @Volatile var isRunning: Boolean = false
        @Volatile var runningConfigName: String = ""
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var job: Job? = null
    private var flushJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stopFlag = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        try {
            VpnNative.init()
        } catch (expected: UnsatisfiedLinkError) {
            Log.error(TAG, "native lib missing", expected)
        } catch (expected: SecurityException) {
            Log.error(TAG, "native lib blocked", expected)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when {
            intent == null || intent.action == null -> {
                // Null intent/action = system start (Always-On VPN, or sticky restart).
                // Go foreground straight away — the config lookup below is async and the
                // system will not wait for it before enforcing the background-start timeout.
                Log.status(TAG, "onStartCommand system start — attempting Always-On restore")
                goForeground(notification("VPN (WireGuard/gotatun)", "Connecting…"))
                scope.launch {
                    try {
                        val all = VpnDatabase.get(this@VpnTunnelService).vpnConfigDao().getAll()
                        val lastUsed = all.maxByOrNull { it.lastUsed }
                        if (lastUsed != null) {
                            Log.status(TAG, "Always-On restoring ${lastUsed.name}")
                            startVpn(lastUsed.toModel())
                        } else {
                            Log.status(TAG, "Always-On restore: no configs found")
                            stopVpn()
                        }
                    } catch (expected: SQLiteException) {
                        Log.error(TAG, "Always-On restore failed", expected)
                        stopVpn()
                    } catch (expected: IllegalStateException) {
                        Log.error(TAG, "Always-On restore failed", expected)
                        stopVpn()
                    }
                }
                START_STICKY
            }
            intent.action == ACTION_DISCONNECT -> {
                stopVpn()
                START_NOT_STICKY
            }
            intent.action == ACTION_CONNECT -> {
                val j = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: return START_STICKY
                runCatching { Json.decodeFromString<VpnConfig>(j) }.onSuccess { startVpn(it) }
                START_STICKY
            }
            else -> START_STICKY
        }
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
    override fun onRevoke() { Log.status(TAG, "onRevoke"); stopVpn() }

    private fun notification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build()
    }

    private fun goForeground(notif: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else startForeground(NOTIFICATION_ID, notif)
        } catch (expected: SecurityException) {
            Log.error(TAG, "foreground", expected)
        } catch (expected: IllegalStateException) {
            Log.error(TAG, "foreground", expected)
        }
    }

    private fun startVpn(config: VpnConfig) {
        if (isRunning) stopVpn()
        stopFlag.store(false)
        runningConfigName = config.name
        goForeground(notification("VPN (WireGuard/gotatun) — ${config.name}", config.peerEndpoint))

        job = scope.launch { runBlocking { runTunnel(config) } }
    }

    private fun stopVpn() {
        stopFlag.store(true)
        job?.cancel(); job = null
        flushJob?.cancel(); flushJob = null
        isRunning = false
        runningConfigName = ""
        try {
            tunPfd?.close()
        } catch (expected: IOException) {
            Log.status(TAG, "close tun", expected)
        }
        tunPfd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runTunnel(config: VpnConfig) {
        isRunning = true

        val handle = VpnNative.newTunnel(
            config.privateKey, config.peerPublicKey, config.peerPresharedKey, config.peerKeepalive
        )
        if (handle <= 0) {
            Log.error(TAG, "newTunnel failed $handle")
            stopVpn()
            return
        }

        val session = TunnelSession(config, handle)
        try {
            session.run()
        } finally {
            VpnNative.freeTunnel(handle)
        }
    }

    internal inner class TunnelSession(val config: VpnConfig, val handle: Long) {
        val dnsCache = DnsCache()
        val tracker = ConnectionTracker.getOrCreate()
        lateinit var appResolver: AppResolver
        lateinit var logDao: ConnectionLogDao
        var sessionFlushJob: Job? = null

        suspend fun run() {
            val pfd = buildTun()
            if (pfd == null) {
                Log.error(TAG, "establish null")
                stopVpn()
                return
            }
            tunPfd = pfd
            val endpoint = resolveEndpoint() ?: run {
                closeQuietly(pfd)
                stopVpn()
                return
            }
            val channel = openUdpChannel(endpoint) ?: run {
                closeQuietly(pfd)
                stopVpn()
                return
            }
            tracker.setDnsCache(dnsCache)
            appResolver = AppResolver(this@VpnTunnelService)
            logDao = VpnDatabase.get(this@VpnTunnelService).connectionLogDao()
            startFlushJob()
            try {
                pumpLoop(pfd, channel, endpoint)
            } finally {
                stopSession(pfd, channel)
            }
        }

        fun parseCsvCidrs(csv: String): List<Pair<String, Int>> =
            csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { cidr ->
                val parts = cidr.split('/')
                val ip = parts[0].trim()
                val mask = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    ?: if (ip.contains(':')) IPV6_CIDR_DEFAULT else IPV4_CIDR_DEFAULT
                ip to mask
            }

        suspend fun buildTun(): ParcelFileDescriptor? {
            val localAddrs = parseCsvCidrs(config.address.ifBlank { DEFAULT_TUN_ADDRESS })
            val allowed = parseCsvCidrs(config.peerAllowedIPs.ifBlank { DEFAULT_ALLOWED_IPS })

            val b = Builder().setSession(config.name.ifBlank { DEFAULT_SESSION_NAME })
                .setMtu(config.mtu.coerceIn(MIN_MTU, MAX_MTU)).setBlocking(false)

            addAddresses(b, localAddrs)
            addRoutes(b, withLeakGuard(allowed))
            addDnsServers(b)
            try {
                b.setUnderlyingNetworks(null)
            } catch (expected: IllegalArgumentException) {
                Log.status(TAG, "setUnderlyingNetworks", expected)
            }
            applyBypassList(b)
            return try {
                b.establish()
            } catch (expected: IllegalArgumentException) {
                Log.error(TAG, "establish", expected)
                null
            } catch (expected: SecurityException) {
                Log.error(TAG, "establish", expected)
                null
            } catch (expected: IllegalStateException) {
                Log.error(TAG, "establish", expected)
                null
            }
        }

        fun addAddresses(b: Builder, addrs: List<Pair<String, Int>>) {
            for ((ip, mask) in addrs) {
                try {
                    b.addAddress(ip, mask)
                } catch (expected: IllegalArgumentException) {
                    Log.status(TAG, "addr $ip/$mask", expected)
                } catch (expected: SecurityException) {
                    Log.status(TAG, "addr $ip/$mask", expected)
                }
            }
        }

        // IPv6 leak guard. Our own default AllowedIPs is "0.0.0.0/0, ::/0", but plenty of
        // real .conf files from IPv4-only providers say just "0.0.0.0/0". Android only
        // diverts the families it has a route for, so on a dual-stack network every IPv6
        // connection would keep using the physical interface while the user believes they
        // are tunnelled — a silent deanonymisation, not a degraded experience.
        //
        // Claiming ::/0 without an IPv6 address on the tun means IPv6 source-address
        // selection fails and connections fail immediately, so Happy Eyeballs falls back to
        // IPv4 through the tunnel. Traffic is blocked rather than leaked. Only applied when
        // the config routes a v4 default and names no v6 route at all: a config that
        // deliberately splits (say 10.0.0.0/8 only) is left exactly as written.
        fun withLeakGuard(allowed: List<Pair<String, Int>>): List<Pair<String, Int>> {
            val routes = allowed.toMutableList()
            val hasV6Route = allowed.any { (ip, _) -> ip.contains(':') }
            val hasV4Default = allowed.any { (ip, mask) -> mask == DEFAULT_ROUTE_MASK && !ip.contains(':') }
            if (hasV4Default && !hasV6Route) {
                Log.status(TAG, "AllowedIPs has no IPv6 route; adding ::/0 to guard IPv6 leaks")
                routes.add(IPV6_DEFAULT_ROUTE to DEFAULT_ROUTE_MASK)
            }
            return routes
        }

        fun addRoutes(b: Builder, routes: List<Pair<String, Int>>) {
            for ((ip, mask) in routes) {
                try {
                    b.addRoute(ip, mask)
                } catch (expected: IllegalArgumentException) {
                    Log.status(TAG, "route $ip/$mask", expected)
                } catch (expected: SecurityException) {
                    Log.status(TAG, "route $ip/$mask", expected)
                }
            }
        }

        fun addDnsServers(b: Builder) {
            if (config.dns.isBlank()) return
            for (d in config.dns.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                try {
                    b.addDnsServer(d)
                } catch (expected: IllegalArgumentException) {
                    Log.status(TAG, "dns $d", expected)
                }
            }
        }

        suspend fun applyBypassList(b: Builder) {
            // Split tunnelling. addDisallowedApplication throws if the package is gone (user
            // uninstalled it after adding it to the list), so each one is guarded individually
            // rather than losing the whole tunnel to one stale entry.
            for (pkg in BypassList.load(applicationContext)) {
                try {
                    b.addDisallowedApplication(pkg)
                } catch (expected: PackageManager.NameNotFoundException) {
                    Log.status(TAG, "bypass: $pkg not installed, skipping")
                } catch (expected: IllegalArgumentException) {
                    Log.status(TAG, "bypass: $pkg", expected)
                } catch (expected: SecurityException) {
                    Log.status(TAG, "bypass: $pkg", expected)
                }
            }
        }

        fun resolveEndpoint(): InetSocketAddress? {
            val host = config.endpointHost()
            val port = config.endpointPort()
            if (host.isEmpty()) {
                Log.error(TAG, "no endpoint")
                return null
            }
            return InetSocketAddress(host, port)
        }

        fun openUdpChannel(endpoint: InetSocketAddress): DatagramChannel? {
            return try {
                val ch = DatagramChannel.open()
                ch.configureBlocking(false)
                try {
                    protect(ch.socket())
                } catch (expected: IllegalStateException) {
                    Log.status(TAG, "protect socket", expected)
                }
                ch.connect(endpoint)
                ch
            } catch (expected: IOException) {
                Log.error(TAG, "UDP connect $endpoint", expected)
                null
            } catch (expected: SecurityException) {
                Log.error(TAG, "UDP connect $endpoint", expected)
                null
            } catch (expected: IllegalArgumentException) {
                Log.error(TAG, "UDP connect $endpoint", expected)
                null
            }
        }

        fun startFlushJob() {
            // Batched Room upsert job (1.5s) — tracker keeps cumulative TX/RX in memory,
            // so each drain returns full accumulated totals for dirty flows.
            sessionFlushJob = scope.launch(Dispatchers.IO) {
                while (isActive && !stopFlag.load()) {
                    delay(FLUSH_INTERVAL_MS)
                    try {
                        flushOnce()
                    } catch (expected: SQLiteException) {
                        Log.status(TAG, "flush logs", expected)
                    } catch (expected: IllegalStateException) {
                        Log.status(TAG, "flush logs", expected)
                    }
                }
            }
            flushJob = sessionFlushJob
        }

        suspend fun flushOnce() {
            val batch = tracker.drainDirty()
            if (batch.isEmpty()) return
            val toUpsert = batch.map { mergeWithExisting(it) }
            if (toUpsert.isNotEmpty()) {
                logDao.upsertAll(toUpsert)
                tracker.updateIds(toUpsert)
            }
        }

        suspend fun mergeWithExisting(entity: ConnectionLogEntity): ConnectionLogEntity {
            if (entity.id != 0L) return entity
            val existing = logDao.findIdentical(
                remoteIp = entity.remoteIp,
                remotePort = entity.remotePort,
                protocol = entity.protocol,
                localPort = entity.localPort,
            ) ?: return entity
            val attributed = entity.uid >= 0
            return entity.copy(
                id = existing.id,
                timestampStart = minOf(existing.timestampStart, entity.timestampStart),
                txBytes = maxOf(existing.txBytes, entity.txBytes),
                rxBytes = maxOf(existing.rxBytes, entity.rxBytes),
                domain = entity.domain ?: existing.domain,
                uid = if (attributed) entity.uid else existing.uid,
                packageName = if (attributed) entity.packageName else existing.packageName,
                appLabel = if (attributed) entity.appLabel else existing.appLabel,
            )
        }

        fun handleLoggingForPacket(
            ipBytes: ByteArray,
            direction: ConnectionTracker.Direction,
            rawBytesLen: Int,
        ) {
            val parsed = try {
                PacketInspector.parse(ipBytes) ?: return
            } catch (expected: IllegalArgumentException) {
                Log.status(TAG, "logging parse", expected)
                return
            }
            try {
                snoopDns(parsed, ipBytes)
                val domain = resolveDomain(parsed, ipBytes, direction)
                val resolved = appResolver.resolve(parsed, direction)
                tracker.onPacket(
                    parsed = parsed,
                    direction = direction,
                    bytes = rawBytesLen,
                    domainOverride = domain,
                    uid = resolved.uid,
                    packageName = resolved.packageName,
                    appLabel = resolved.appLabel,
                )
            } catch (expected: IllegalArgumentException) {
                Log.status(TAG, "logging parse", expected)
            } catch (expected: IllegalStateException) {
                Log.status(TAG, "logging parse", expected)
            }
        }

        fun snoopDns(parsed: PacketInspector.ParsedPacket, ipBytes: ByteArray) {
            // DNS snooping populates IP->domain LRU
            val isDns = parsed.srcPort == DNS_PORT || parsed.dstPort == DNS_PORT
            if (parsed.protocol != UDP_PROTOCOL || !isDns) return
            try {
                dnsCache.onPacket(parsed, ipBytes)
            } catch (expected: IllegalArgumentException) {
                Log.status(TAG, "dns snoop", expected)
            }
        }

        fun resolveDomain(
            parsed: PacketInspector.ParsedPacket,
            ipBytes: ByteArray,
            direction: ConnectionTracker.Direction,
        ): String? {
            val isTx = direction == ConnectionTracker.Direction.TX
            val cached = dnsCache.get(if (isTx) parsed.dstIp else parsed.srcIp)
            if (cached != null) return cached
            // Domain resolution: DNS cache then SNI for TLS 443
            if (parsed.protocol != TCP_PROTOCOL || parsed.payloadLength <= 0) return null
            val txPort = parsed.dstPort == TLS_PORT
            val rxPort = parsed.srcPort == TLS_PORT
            if (if (isTx) txPort else rxPort) {
                val domain = SniParser.extractSni(ipBytes, parsed.payloadOffset, parsed.payloadLength)
                if (domain != null) {
                    dnsCache.put(if (isTx) parsed.dstIp else parsed.srcIp, domain)
                }
                return domain
            }
            return null
        }

        suspend fun pumpLoop(pfd: ParcelFileDescriptor, channel: DatagramChannel, endpoint: InetSocketAddress) {
            val tunIn = FileInputStream(pfd.fileDescriptor).channel
            val tunOut = FileOutputStream(pfd.fileDescriptor).channel
            try {
                sendHandshakeInit(channel, endpoint)
                val udpBuf = ByteBuffer.allocate(UDP_BUFFER_SIZE)
                val tunBuf = ByteBuffer.allocate(UDP_BUFFER_SIZE)
                var lastTimer = System.currentTimeMillis()
                while (!stopFlag.load() && scope.isActive) {
                    pumpTunToNet(tunIn, tunBuf, channel)
                    pumpNetToTun(channel, udpBuf, tunOut)
                    lastTimer = fireTimers(channel, lastTimer)
                    delay(PUMP_DELAY_MS)
                }
            } finally {
                closeQuietly(tunIn)
                closeQuietly(tunOut)
            }
        }

        fun sendHandshakeInit(channel: DatagramChannel, endpoint: InetSocketAddress) {
            VpnNative.formatHandshakeInit(handle)?.let { hs ->
                writeUdp(channel, hs)
                Log.status(TAG, "Sent HandshakeInit ${hs.size} to $endpoint (gotatun)")
            }
        }

        fun pumpTunToNet(tunIn: FileChannel, tunBuf: ByteBuffer, channel: DatagramChannel) {
            try {
                while (tunIn.read(tunBuf) > 0) {
                    tunBuf.flip()
                    val ip = ByteArray(tunBuf.remaining())
                    tunBuf.get(ip)
                    tunBuf.clear()
                    handleLoggingForPacket(ip, ConnectionTracker.Direction.TX, ip.size)
                    val enc = VpnNative.encapsulate(handle, ip)
                    if (enc != null && enc.isNotEmpty()) {
                        writeUdp(channel, enc)
                    } else {
                        VpnNative.formatHandshakeInit(handle)?.let { h -> writeUdp(channel, h) }
                    }
                }
            } catch (expected: IOException) {
                if (!stopFlag.load()) Log.status(TAG, "tun read", expected)
            } catch (expected: IllegalStateException) {
                if (!stopFlag.load()) Log.status(TAG, "tun read", expected)
            }
        }

        fun pumpNetToTun(channel: DatagramChannel, udpBuf: ByteBuffer, tunOut: FileChannel) {
            try {
                udpBuf.clear()
                while (channel.read(udpBuf) > 0) {
                    udpBuf.flip()
                    val wg = ByteArray(udpBuf.remaining())
                    udpBuf.get(wg)
                    udpBuf.clear()
                    routeIncoming(wg, channel, tunOut)
                }
            } catch (expected: IOException) {
                if (!stopFlag.load()) Log.status(TAG, "udp read", expected)
            } catch (expected: IllegalStateException) {
                if (!stopFlag.load()) Log.status(TAG, "udp read", expected)
            }
        }

        fun routeIncoming(wg: ByteArray, channel: DatagramChannel, tunOut: FileChannel) {
            val tagged = VpnNative.consumeIncomingPacketDetailed(handle, wg) ?: return
            if (tagged.isEmpty()) return
            val tag = tagged[0].toInt()
            val payload = tagged.copyOfRange(1, tagged.size)
            if (payload.isEmpty()) return
            when (tag) {
                TAG_SEND_UDP -> writeUdp(channel, payload)
                TAG_INJECT_TUN -> injectToTun(tunOut, payload)
                TAG_KEEPALIVE -> { /* keepalive absorbed */ }
            }
        }

        fun injectToTun(tunOut: FileChannel, payload: ByteArray) {
            handleLoggingForPacket(payload, ConnectionTracker.Direction.RX, payload.size)
            try {
                tunOut.write(ByteBuffer.wrap(payload))
            } catch (expected: IOException) {
                if (!stopFlag.load()) Log.status(TAG, "tun write", expected)
            }
        }

        fun writeUdp(channel: DatagramChannel, bytes: ByteArray) {
            try {
                channel.write(ByteBuffer.wrap(bytes))
            } catch (expected: IOException) {
                Log.status(TAG, "udp write", expected)
            }
        }

        fun fireTimers(channel: DatagramChannel, lastTimer: Long): Long {
            val now = System.currentTimeMillis()
            if (now - lastTimer < TIMER_INTERVAL_MS) return lastTimer
            try {
                val t = VpnNative.tickTimersDetailed(handle)
                if (t != null && t.isNotEmpty() && t[0].toInt() == TAG_SEND_UDP) {
                    val p = t.copyOfRange(1, t.size)
                    if (p.isNotEmpty()) writeUdp(channel, p)
                }
            } catch (expected: IllegalStateException) {
                Log.status(TAG, "timer", expected)
            }
            return now
        }

        suspend fun stopSession(pfd: ParcelFileDescriptor, channel: DatagramChannel) {
            try {
                val finalBatch = tracker.drainDirty()
                if (finalBatch.isNotEmpty()) logDao.upsertAll(finalBatch)
            } catch (expected: SQLiteException) {
                Log.status(TAG, "final flush", expected)
            } catch (expected: IllegalStateException) {
                Log.status(TAG, "final flush", expected)
            }
            sessionFlushJob?.cancel()
            sessionFlushJob = null
            flushJob = null
            closeQuietly(channel)
            closeQuietly(pfd)
            isRunning = false
            try {
                getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            } catch (expected: SecurityException) {
                Log.status(TAG, "cancel notification", expected)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        fun closeQuietly(c: Closeable) {
            try {
                c.close()
            } catch (expected: IOException) {
                Log.status(TAG, "close", expected)
            }
        }
    }
}
