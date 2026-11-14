package com.vayunmathur.vpn.service

import com.vayunmathur.vpn.data.ConnectionLogEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory flow aggregation for hot-path packet inspection.
 * Thread-safe for single writer (VPN service tunnel thread). `drainDirty()` is called from flush job.
 */
class ConnectionTracker {

    /** Kinds of direction. */
    enum class Direction { TX, RX }

    /**
     * Identifies a flow by its 4-tuple *without* uid: attribution often only succeeds a few packets
     * in, and keying on uid would fork the same connection into an unattributed row plus a real one.
     */
    data class FlowKey(
        val protocol: String,
        val remoteIp: String,
        val remotePort: Int,
        val localPort: Int,
    )

    private data class MutableAgg(
        var timestampStart: Long,
        var timestampLast: Long,
        var uid: Int,
        var packageName: String?,
        var appLabel: String,
        var localIp: String,
        var remoteIp: String,
        var remotePort: Int,
        var localPort: Int,
        var protocol: String,
        var domain: String?,
        var txBytes: Long,
        var rxBytes: Long,
        var requestCount: Long,
        @Volatile var dirty: Boolean = true,
        @Volatile var id: Long = 0,
    )

    private val flows = ConcurrentHashMap<FlowKey, MutableAgg>()

    @Volatile var dnsCacheRef: DnsCache? = null

    fun setDnsCache(cache: DnsCache) {
        dnsCacheRef = cache
    }

    fun onPacket(
        parsed: PacketInspector.ParsedPacket,
        direction: Direction,
        bytes: Int,
        domainOverride: String? = null,
        uid: Int,
        packageName: String?,
        appLabel: String,
    ) {
        val now = System.currentTimeMillis()
        val key = flowKey(parsed, direction)
        val domain = domainOverride ?: dnsCacheRef?.get(key.remoteIp) ?: flows[key]?.domain

        flows.compute(key) { _, existing ->
            if (existing == null) {
                newAggregate(now, parsed, direction, bytes, key, domain, uid, packageName, appLabel)
            } else {
                mergeInto(existing, now, direction, bytes, domain, uid, packageName, appLabel)
                existing
            }
        }
    }

    private fun flowKey(parsed: PacketInspector.ParsedPacket, direction: Direction): FlowKey {
        val tx = direction == Direction.TX
        return FlowKey(
            protocol = parsed.protocol,
            remoteIp = if (tx) parsed.dstIp else parsed.srcIp,
            remotePort = if (tx) parsed.dstPort else parsed.srcPort,
            localPort = if (tx) parsed.srcPort else parsed.dstPort,
        )
    }

    private fun newAggregate(
        now: Long,
        parsed: PacketInspector.ParsedPacket,
        direction: Direction,
        bytes: Int,
        key: FlowKey,
        domain: String?,
        uid: Int,
        packageName: String?,
        appLabel: String,
    ): MutableAgg {
        val tx = direction == Direction.TX
        return MutableAgg(
            timestampStart = now,
            timestampLast = now,
            uid = uid,
            packageName = packageName,
            appLabel = appLabel,
            localIp = if (tx) parsed.srcIp else parsed.dstIp,
            remoteIp = key.remoteIp,
            remotePort = key.remotePort,
            localPort = key.localPort,
            protocol = parsed.protocol,
            domain = domain,
            txBytes = if (tx) bytes.toLong() else 0L,
            rxBytes = if (tx) 0L else bytes.toLong(),
            requestCount = 1L,
            dirty = true,
        )
    }

    private fun mergeInto(
        existing: MutableAgg,
        now: Long,
        direction: Direction,
        bytes: Int,
        domain: String?,
        uid: Int,
        packageName: String?,
        appLabel: String,
    ) {
        existing.timestampLast = now
        if (direction == Direction.TX) {
            existing.txBytes += bytes
        } else {
            existing.rxBytes += bytes
        }
        if (domain != null && existing.domain == null) existing.domain = domain
        upgradeAttribution(existing, uid, packageName, appLabel)
        existing.dirty = true
    }

    private fun upgradeAttribution(
        existing: MutableAgg,
        uid: Int,
        packageName: String?,
        appLabel: String,
    ) {
        // Late attribution wins: uid -1 means nothing has identified this flow yet.
        if (existing.uid < 0 && uid >= 0) {
            existing.uid = uid
            existing.packageName = packageName
            existing.appLabel = appLabel
        } else if (existing.packageName == null && packageName != null) {
            existing.packageName = packageName
            existing.appLabel = appLabel
        }
    }

    fun recordDnsMapping(ip: String, domain: String) {
        dnsCacheRef?.put(ip, domain)
    }

    fun drainDirty(): List<ConnectionLogEntity> {
        if (flows.isEmpty()) return emptyList()
        val batch = mutableListOf<ConnectionLogEntity>()
        for ((_, agg) in flows) {
            if (agg.dirty) {
                batch.add(
                    ConnectionLogEntity(
                        id = agg.id,
                        timestampStart = agg.timestampStart,
                        timestampLast = agg.timestampLast,
                        uid = agg.uid,
                        packageName = agg.packageName,
                        appLabel = agg.appLabel,
                        protocol = agg.protocol,
                        localIp = agg.localIp,
                        localPort = agg.localPort,
                        remoteIp = agg.remoteIp,
                        remotePort = agg.remotePort,
                        domain = agg.domain,
                        txBytes = agg.txBytes,
                        rxBytes = agg.rxBytes,
                        requestCount = agg.requestCount,
                    )
                )
                agg.dirty = false
            }
        }
        return batch
    }

    fun updateIds(entities: List<ConnectionLogEntity>) {
        for (e in entities) {
            val key = FlowKey(
                protocol = e.protocol,
                remoteIp = e.remoteIp,
                remotePort = e.remotePort,
                localPort = e.localPort,
            )
            flows[key]?.id = e.id
        }
    }

    fun clear() {
        flows.clear()
    }

    fun snapshot(): List<ConnectionLogEntity> {
        return flows.values.map {
            ConnectionLogEntity(
                id = it.id,
                timestampStart = it.timestampStart,
                timestampLast = it.timestampLast,
                uid = it.uid,
                packageName = it.packageName,
                appLabel = it.appLabel,
                protocol = it.protocol,
                localIp = it.localIp,
                localPort = it.localPort,
                remoteIp = it.remoteIp,
                remotePort = it.remotePort,
                domain = it.domain,
                txBytes = it.txBytes,
                rxBytes = it.rxBytes,
                requestCount = it.requestCount,
            )
        }
    }

    /** Shared constants and helpers for ConnectionTracker. */
    companion object {
        @Volatile
        var globalInstance: ConnectionTracker? = null
            private set

        fun getOrCreate(): ConnectionTracker {
            val existing = globalInstance
            if (existing != null) return existing
            synchronized(this) {
                val doubleCheck = globalInstance
                if (doubleCheck != null) return doubleCheck
                val created = ConnectionTracker()
                globalInstance = created
                return created
            }
        }

        fun clearGlobal() {
            synchronized(this) {
                globalInstance?.clear()
                globalInstance = null
            }
        }
    }
}
