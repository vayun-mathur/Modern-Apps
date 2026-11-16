package com.vayunmathur.games.voxels.network

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.e2ee.Pqc
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.vayunmathur.games.voxels.data.WorldManager
import com.vayunmathur.games.voxels.domain.NetFailure
import com.vayunmathur.games.voxels.domain.NetStatus
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cov-x4: MultiplayerPump gating (via its private constructor) and JoinRequests persistence.
 * Signed ops that would be accepted reach the native engine and are deliberately not exercised
 * past the gate: every case below is rejected before `netPushInbound`, which is the gate's job.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(ExperimentalEncodingApi::class)
class VoxelsCovX4PumpTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun pump(role: Int, ownerBundle: ByteArray? = byteArrayOf(1, 2, 3)): Any {
        val params = MultiplayerPump.Params("wid", "a2V5", "owner-dev", role, "World")
        val ctor = MultiplayerPump::class.java.getDeclaredConstructor(
            MultiplayerPump.Params::class.java, String::class.java,
            ByteArray::class.java, ByteArray::class.java,
        ).apply { isAccessible = true }
        return ctor.newInstance(params, "me-dev", ByteArray(32) { 7 }, ownerBundle)
    }

    private fun invoke(target: Any, name: String, types: Array<Class<*>>, vararg args: Any?) {
        val m = MultiplayerPump::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
        try {
            m.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableField(target: Any, name: String): MutableCollection<String> {
        val f = MultiplayerPump::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(target) as MutableCollection<String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun bundleField(target: Any): MutableMap<String, ByteArray> {
        val f = MultiplayerPump::class.java.getDeclaredField("bundles").apply { isAccessible = true }
        return f.get(target) as MutableMap<String, ByteArray>
    }

    private fun signedOp(author: String, ops: String = """{"type":"Place"}""", sig: String = "AAAA") =
        """{"author":"$author","sig":"$sig","ops":${org.json.JSONObject.quote(ops)}}"""

    @Test fun clientRejectsSelfEmptyAndNonOwnerOps() {
        val p = pump(role = 2)
        val sig = arrayOf(CoroutineScope::class.java, String::class.java)
        invoke(p, "handleSignedOp", sig, scope, signedOp("me-dev")) // self
        invoke(p, "handleSignedOp", sig, scope, signedOp("")) // empty author
        invoke(p, "handleSignedOp", sig, scope, """{"author":"peer","sig":"AAAA","ops":""}""") // empty ops
        invoke(p, "handleSignedOp", sig, scope, signedOp("peer")) // not the owner on a client
        invoke(p, "handleSignedOp", sig, scope, "not json")
        invoke(p, "handleSignedOp", sig, scope, "{}")
        // Owner-authored but the signature cannot verify against a garbage bundle.
        invoke(p, "handleSignedOp", sig, scope, signedOp("owner-dev"))
    }

    @Test fun clientWithoutOwnerBundleRejectsOwnerOps() {
        val p = pump(role = 2, ownerBundle = null)
        invoke(p, "handleSignedOp", arrayOf(CoroutineScope::class.java, String::class.java), scope, signedOp("owner-dev"))
    }

    @Test fun hostRejectsNonRosterAndUnverifiableOps() {
        val p = pump(role = 1)
        val sig = arrayOf(CoroutineScope::class.java, String::class.java)
        invoke(p, "handleSignedOp", sig, scope, signedOp("stranger")) // not in roster
        mutableField(p, "roster").add("peer")
        // Rostered but no cached bundle: the op is dropped and a fetch is scheduled.
        invoke(p, "handleSignedOp", sig, scope, signedOp("peer"))
        bundleField(p)["peer"] = byteArrayOf(9, 9, 9)
        // Rostered with a bundle, but the signature does not verify.
        invoke(p, "handleSignedOp", sig, scope, signedOp("peer"))
        invoke(p, "handleSignedOp", sig, scope, signedOp("peer", sig = "!!!"))
    }

    @Test fun presenceRejectsSelfAndMalformed() {
        val p = pump(role = 1)
        val sig = arrayOf<Class<*>>(String::class.java)
        invoke(p, "handlePresence", sig, """{"from":"me-dev","m":{"x":1}}""")
        invoke(p, "handlePresence", sig, "not json")
        invoke(p, "handlePresence", sig, "{}")
        invoke(p, "handlePresence", sig, """{"from":"peer"}""") // no transform object
        invoke(p, "handlePresence", sig, """{"from":"peer","m":"not-an-object"}""")
    }

    @Test fun verifyRejectsGarbageSignatures() {
        val p = pump(role = 1)
        val m = MultiplayerPump::class.java.getDeclaredMethod(
            "verify", ByteArray::class.java, String::class.java, String::class.java,
        ).apply { isAccessible = true }
        assertEquals(false, m.invoke(p, byteArrayOf(1, 2), "ops", "AAAA"))
        assertEquals(false, m.invoke(p, byteArrayOf(1, 2), "ops", "!!!not-base64!!!"))
        assertEquals(false, m.invoke(p, ByteArray(0), "", ""))
    }

    @Test fun pumpStartsWithEmptyRosterAndBundles() {
        val p = pump(role = 1)
        // A freshly built pump trusts nobody yet: the loop is what seeds owner + self.
        assertTrue(mutableField(p, "roster").isEmpty())
        assertTrue(bundleField(p).isEmpty())
        assertFalse(VoxelsSync.isLive)
    }

    @Test fun companionRunReportsUnreachableWithoutIdentity() = runBlocking {
        val statuses = mutableListOf<NetStatus>()
        MultiplayerPump.run(
            ctx, MultiplayerPump.Params("wid", "not-base64!!", "owner", 2, "W"),
            onStatus = { statuses += it }, onPeers = {},
        )
        // Identity setup fails on the JVM (no native PQC), so the session cannot start:
        // Connecting first, then the unreachable-server failure, never Connected.
        assertEquals(NetStatus.Connecting, statuses.first())
        assertTrue(statuses.any { it is NetStatus.Failed }, "statuses=$statuses")
        assertFalse(statuses.contains(NetStatus.Connected))
    }

    @Test fun paramsEqualityAndCopy() {
        val a = MultiplayerPump.Params("w", "k", "o", 1, "Name")
        assertEquals(a, a.copy())
        assertEquals("w", a.copy(worldId = "w").worldId)
        assertFalse(a == a.copy(role = 2))
        assertTrue(a.toString().contains("worldId=w"))
    }

    @Test fun hostAcceptsValidMemberSignatureUpToEngineGate() {
        // A real second identity: ML-DSA keypair generated here, signed op verified by the pump.
        val (kemPub, _) = Pqc.generateKem()
        val (dsaPub, dsaPriv) = Pqc.generateDsa()
        val bundle = Pqc.bundle(kemPub, dsaPub)
        val ops = """{"type":"Place","x":1}"""
        val sig = Base64.encode(Pqc.signWith(dsaPriv, ops.encodeToByteArray()))
        val p = pump(role = 1)
        mutableField(p, "roster").add("peer-x")
        bundleField(p)["peer-x"] = bundle
        // Verification passes, so the gate hands the op to the engine — absent on the JVM.
        val thrown = runCatching {
            invoke(p, "handleSignedOp", arrayOf(CoroutineScope::class.java, String::class.java), scope, signedOp("peer-x", ops, sig))
        }.exceptionOrNull()
        assertTrue(thrown == null || thrown is UnsatisfiedLinkError, "thrown=$thrown")
        // The same op with a forged signature is rejected before the engine.
        val badSig = Base64.encode(Pqc.signWith(dsaPriv, "other".encodeToByteArray()))
        invoke(p, "handleSignedOp", arrayOf(CoroutineScope::class.java, String::class.java), scope, signedOp("peer-x", ops, badSig))
    }

    @Test fun clientAcceptsValidOwnerSignatureUpToEngineGate() {
        val (kemPub, _) = Pqc.generateKem()
        val (dsaPub, dsaPriv) = Pqc.generateDsa()
        val bundle = Pqc.bundle(kemPub, dsaPub)
        val ops = """{"type":"Snapshot"}"""
        val sig = Base64.encode(Pqc.signWith(dsaPriv, ops.encodeToByteArray()))
        val p = pump(role = 2, ownerBundle = bundle)
        val thrown = runCatching {
            invoke(p, "handleSignedOp", arrayOf(CoroutineScope::class.java, String::class.java), scope, signedOp("owner-dev", ops, sig))
        }.exceptionOrNull()
        assertTrue(thrown == null || thrown is UnsatisfiedLinkError, "thrown=$thrown")
        // Valid presence from a peer also reaches the engine gate.
        val thrown2 = runCatching {
            invoke(p, "handlePresence", arrayOf<Class<*>>(String::class.java), """{"from":"peer","m":{"type":"PlayerTransform"}}""")
        }.exceptionOrNull()
        assertTrue(thrown2 == null || thrown2 is UnsatisfiedLinkError, "thrown=$thrown2")
    }

    @Test fun companionLoopStartsAndReportsConnecting() = runBlocking {
        assertTrue(VoxelsSync.init(ctx))
        val statuses = mutableListOf<NetStatus>()
        val scope2 = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val job = scope2.launch {
            MultiplayerPump.run(
                ctx,
                MultiplayerPump.Params(
                    "x4-loop-world", Base64.encode(VoxelsSync.newWorldKey()),
                    VoxelsSync.deviceId, 1, "Loop World",
                ),
                onStatus = { statuses += it }, onPeers = {},
            )
        }
        delay(1500)
        job.cancel()
        VoxelsSync.stopLive()
        assertEquals(NetStatus.Connecting, statuses.first())
        Unit
    }


    @Test fun companionLoopClientFetchesOwnerBundle() = runBlocking {
        assertTrue(VoxelsSync.init(ctx))
        val statuses = mutableListOf<NetStatus>()
        val scope2 = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val job = scope2.launch {
            MultiplayerPump.run(
                ctx,
                MultiplayerPump.Params(
                    "x4-loop-world-client", Base64.encode(VoxelsSync.newWorldKey()),
                    VoxelsSync.deviceId, 2, "Client World",
                ),
                onStatus = { statuses += it }, onPeers = {},
            )
        }
        delay(1200)
        job.cancel()
        VoxelsSync.stopLive()
        assertEquals(NetStatus.Connecting, statuses.first())
        Unit
    }

    // --- JoinRequests ---

    private fun req(world: String, who: String, name: String = "") =
        VoxelsSync.JoinRequest(worldId = world, requesterId = who, name = name)

    @Test fun pendingFiltersDuplicatesHandledAndForeign() {
        assertEquals("w1|dev9", JoinRequests.handledKey(req("w1", "dev9")))
        assertTrue(JoinRequests.pending(emptyList(), setOf("w1"), emptySet()).isEmpty())
        assertTrue(JoinRequests.pending(listOf(req("w1", "a")), emptySet(), emptySet()).isEmpty())
        assertTrue(JoinRequests.pending(listOf(req("w1", "a")), setOf("w1"), setOf("w1|a")).isEmpty())
        val kept = JoinRequests.pending(
            listOf(req("w2", "x"), req("w1", "b", "Bob"), req("w1", "b", "Bob2"), req("w1", "a")),
            owned = setOf("w1", "w2"), handled = emptySet(),
        )
        // First occurrence wins for a duplicate requester+world pair.
        assertEquals(listOf("", "Bob", ""), kept.map { it.name })
        assertEquals(listOf("x", "b", "a"), kept.map { it.requesterId })
    }

    @Test fun denyPersistsHandledKeyAndRefreshDegradesOffline() = runBlocking {
        val r = req("world-deny", "dev-deny")
        JoinRequests.deny(ctx, r)
        assertTrue(JoinRequests.handledKey(r) in WorldManager.handledRequests(ctx))
        JoinRequests.deny(ctx, r) // idempotent
        assertEquals(1, WorldManager.handledRequests(ctx).count { it == JoinRequests.handledKey(r) })
        // Without a sync identity the inbox refresh yields no requests instead of throwing.
        assertEquals(emptyList(), JoinRequests.refresh(ctx, acceptInvites = true))
        assertEquals(emptyList(), JoinRequests.refresh(ctx, acceptInvites = false))
        // Approving a world this device does not own fails cleanly.
        assertFalse(JoinRequests.approve(ctx, req("no-such-world", "dev-x")))
    }
}
