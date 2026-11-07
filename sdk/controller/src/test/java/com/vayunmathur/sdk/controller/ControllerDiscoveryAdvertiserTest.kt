package com.vayunmathur.sdk.controller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidJUnit4::class)
class ControllerDiscoveryAdvertiserTest {

    private fun contextWith(manager: NsdManager?): Context {
        val context = mock(Context::class.java)
        whenever(context.getSystemService(Context.NSD_SERVICE)).thenReturn(manager)
        return context
    }

    private fun resolvedInfo(
        instance: String = "Living Room (Game Host)",
        host: String? = "192.168.1.20",
        port: Int = 4321,
        attrs: Map<String, ByteArray> = mapOf(
            "fn" to "Living Room".toByteArray(Charsets.UTF_8),
            "pv" to "1".toByteArray(Charsets.UTF_8),
            "id" to "tv-1".toByteArray(Charsets.UTF_8),
            "gameId" to "kart".toByteArray(Charsets.UTF_8),
        ),
    ): NsdServiceInfo {
        val info = mock(NsdServiceInfo::class.java)
        whenever(info.serviceName).thenReturn(instance)
        whenever(info.host).thenReturn(host?.let { InetAddress.getByName(it) })
        whenever(info.port).thenReturn(port)
        whenever(info.attributes).thenReturn(attrs)
        return info
    }

    private fun captureDiscoveryListener(manager: NsdManager): NsdManager.DiscoveryListener {
        // callbackFlow body starts on another thread once collection begins: poll for it.
        val deadline = System.currentTimeMillis() + 10_000
        var lastError: AssertionError? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val captor = ArgumentCaptor.forClass(NsdManager.DiscoveryListener::class.java)
                verify(manager).discoverServices(
                    org.mockito.ArgumentMatchers.eq(ControllerContract.GAMEPAD_SERVICE_TYPE),
                    org.mockito.ArgumentMatchers.eq(NsdManager.PROTOCOL_DNS_SD),
                    captor.capture(),
                )
                return captor.value
            } catch (e: AssertionError) {
                lastError = e
                Thread.sleep(10)
            }
        }
        throw lastError ?: AssertionError("discoverServices never called")
    }

    // ---- Discovery ----

    @Test
    fun discoveryWithoutNsdManagerEmitsNothing() = runBlocking {
        val discovery = ControllerDiscovery(contextWith(null))
        val received = mutableListOf<ControllerHostInfo>()
        val job = launch { discovery.discover().collect { received += it } }
        job.join()
        assertTrue(received.isEmpty())
        assertTrue(discovery.hosts.value.isEmpty())
        assertFalse(discovery.localNetworkBlocked.value)
    }

    @Test
    fun discoveryResolvesMergesAndLosesHosts() {
        val manager = mock(NsdManager::class.java)
        val discovery = ControllerDiscovery(contextWith(manager))
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val emitted = mutableListOf<ControllerHostInfo>()
        val job = scope.launch { discovery.discover().collect { emitted += it } }
        try {
            val listener = captureDiscoveryListener(manager)
            listener.onDiscoveryStarted(ControllerContract.GAMEPAD_SERVICE_TYPE)
            assertFalse(discovery.localNetworkBlocked.value)

            // Resolve callback is captured per found service.
            val resolveCaptor = ArgumentCaptor.forClass(NsdManager.ResolveListener::class.java)
            val found = mock(NsdServiceInfo::class.java)
            whenever(found.serviceName).thenReturn("Living Room (Game Host)")
            listener.onServiceFound(found)
            verify(manager).resolveService(any(NsdServiceInfo::class.java), resolveCaptor.capture())

            resolveCaptor.value.onServiceResolved(resolvedInfo())
            assertEquals(1, discovery.hosts.value.size)
            val host = discovery.hosts.value.single()
            assertEquals("tv-1", host.id)
            assertEquals("Living Room", host.friendlyName)
            assertEquals("192.168.1.20", host.host)
            assertEquals(4321, host.port)
            assertEquals(1, host.protocolVersion)
            assertEquals("kart", host.gameId)

            // Same id re-announced with a new port replaces, never duplicates.
            resolveCaptor.value.onServiceResolved(resolvedInfo(port = 5555))
            assertEquals(1, discovery.hosts.value.size)
            assertEquals(5555, discovery.hosts.value.single().port)

            // Unresolvable records (no host / no port / no name) are dropped.
            resolveCaptor.value.onServiceResolved(resolvedInfo(instance = "NoHost", host = null))
            resolveCaptor.value.onServiceResolved(resolvedInfo(instance = "NoPort", port = 0))
            assertEquals(1, discovery.hosts.value.size)

            // A resolve failure only logs; state is untouched.
            resolveCaptor.value.onResolveFailed(resolvedInfo(), 3)
            assertEquals(1, discovery.hosts.value.size)

            // Losing the instance removes it; losing an unknown one is a no-op.
            val lost = mock(NsdServiceInfo::class.java)
            whenever(lost.serviceName).thenReturn("Someone Else")
            listener.onServiceLost(lost)
            assertEquals(1, discovery.hosts.value.size)
            whenever(lost.serviceName).thenReturn("Living Room (Game Host)")
            listener.onServiceLost(lost)
            assertTrue(discovery.hosts.value.isEmpty())

            listener.onDiscoveryStopped(ControllerContract.GAMEPAD_SERVICE_TYPE)
            listener.onStopDiscoveryFailed(ControllerContract.GAMEPAD_SERVICE_TYPE, 1)

            discovery.clear()
            assertTrue(discovery.hosts.value.isEmpty())
        } finally {
            job.cancel()
            scope.cancel()
        }
        // Cancelling the collection stops the browse exactly once (awaitClose runs async).
        val deadline = System.currentTimeMillis() + 10_000
        var stopped = false
        while (System.currentTimeMillis() < deadline && !stopped) {
            stopped = try {
                verify(manager, times(1)).stopServiceDiscovery(any(NsdManager.DiscoveryListener::class.java))
                true
            } catch (_: AssertionError) {
                Thread.sleep(10)
                false
            }
        }
        assertTrue(stopped, "stopServiceDiscovery was never called")
    }

    @Test
    fun discoveryDefaultsTxtFieldsWhenAbsent() {
        val manager = mock(NsdManager::class.java)
        val discovery = ControllerDiscovery(contextWith(manager))
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val job = scope.launch { discovery.discover().collect { } }
        try {
            val listener = captureDiscoveryListener(manager)
            val resolveCaptor = ArgumentCaptor.forClass(NsdManager.ResolveListener::class.java)
            val found = mock(NsdServiceInfo::class.java)
            whenever(found.serviceName).thenReturn("Bare (Game Host)")
            listener.onServiceFound(found)
            verify(manager).resolveService(any(NsdServiceInfo::class.java), resolveCaptor.capture())
            resolveCaptor.value.onServiceResolved(
                resolvedInfo(instance = "Bare (Game Host)", attrs = emptyMap()),
            )
            val host = discovery.hosts.value.single()
            // Missing TXT falls back to the instance name / zero version / empty game.
            assertEquals("Bare (Game Host)", host.id)
            assertEquals("Bare (Game Host)", host.friendlyName)
            assertEquals(0, host.protocolVersion)
            assertEquals("", host.gameId)
            // Blank TXT counts as missing too.
            resolveCaptor.value.onServiceResolved(
                resolvedInfo(
                    instance = "Bare (Game Host)",
                    attrs = mapOf("fn" to "  ".toByteArray(), "pv" to "x".toByteArray()),
                ),
            )
            assertEquals("Bare (Game Host)", discovery.hosts.value.single().friendlyName)
            assertEquals(0, discovery.hosts.value.single().protocolVersion)
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun discoverySecurityExceptionMarksBlocked() {
        val manager = mock(NsdManager::class.java)
        doThrow(SecurityException("blocked")).`when`(manager)
            .discoverServices(anyString(), anyInt(), any(NsdManager.DiscoveryListener::class.java))
        val discovery = ControllerDiscovery(contextWith(manager))
        runBlocking {
            val job = launch { discovery.discover().collect { } }
            job.join()
        }
        assertTrue(discovery.localNetworkBlocked.value)
    }

    @Test
    fun discoveryStartFailureCallbackMarksBlocked() {
        val manager = mock(NsdManager::class.java)
        val discovery = ControllerDiscovery(contextWith(manager))
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val job = scope.launch { discovery.discover().collect { } }
        try {
            val listener = captureDiscoveryListener(manager)
            listener.onStartDiscoveryFailed(ControllerContract.GAMEPAD_SERVICE_TYPE, 42)
            assertTrue(discovery.localNetworkBlocked.value)
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // ---- Advertiser ----

    private fun captureRegistration(manager: NsdManager, count: Int = 1): List<NsdManager.RegistrationListener> {
        val captor = ArgumentCaptor.forClass(NsdManager.RegistrationListener::class.java)
        verify(manager, times(count)).registerService(
            any(NsdServiceInfo::class.java),
            org.mockito.ArgumentMatchers.eq(NsdManager.PROTOCOL_DNS_SD),
            captor.capture(),
        )
        return captor.allValues
    }

    @Test
    fun advertiserWithoutNsdManagerIsSilentNoOp() {
        val advertiser = ControllerAdvertiser(contextWith(null))
        advertiser.advertise("TV", "tv-1", 1234, "kart")
        assertNull(advertiser.registeredName)
        assertFalse(advertiser.localNetworkBlocked)
        advertiser.unadvertise()
    }

    @Test
    fun advertiseRegistersContractRecordAndReportsSuccess() {
        val manager = mock(NsdManager::class.java)
        val advertiser = ControllerAdvertiser(contextWith(manager))
        advertiser.advertise("Living Room", "tv-1", 4321, "kart")

        val infoCaptor = ArgumentCaptor.forClass(NsdServiceInfo::class.java)
        verify(manager).registerService(infoCaptor.capture(), anyInt(), any(NsdManager.RegistrationListener::class.java))
        val info = infoCaptor.value
        assertEquals("Living Room (Game Host)", info.serviceName)
        assertEquals(ControllerContract.GAMEPAD_SERVICE_TYPE, info.serviceType)
        assertEquals(4321, info.port)

        val listener = captureRegistration(manager).single()
        val registered = mock(NsdServiceInfo::class.java)
        whenever(registered.serviceName).thenReturn("Living Room (Game Host)")
        listener.onServiceRegistered(registered)
        assertEquals("Living Room (Game Host)", advertiser.registeredName)
        assertFalse(advertiser.localNetworkBlocked)
        listener.onServiceUnregistered(registered)

        advertiser.unadvertise()
        verify(manager).unregisterService(any(NsdManager.RegistrationListener::class.java))
        assertNull(advertiser.registeredName)
    }

    @Test
    fun registrationConflictRetriesNumberedNamesThenGivesUp() {
        val manager = mock(NsdManager::class.java)
        val advertiser = ControllerAdvertiser(contextWith(manager))
        advertiser.advertise("TV", "tv-1", 1000, "kart")
        val info = mock(NsdServiceInfo::class.java)
        // Fail every attempt: 1 initial + 4 retries = 5 registrations, then stop.
        var listener = captureRegistration(manager, 1).single()
        repeat(4) { attempt ->
            listener.onRegistrationFailed(info, 1)
            listener = captureRegistration(manager, attempt + 2).last()
        }
        listener.onRegistrationFailed(info, 1)
        // No sixth attempt.
        verify(manager, times(5)).registerService(
            any(NsdServiceInfo::class.java), anyInt(), any(NsdManager.RegistrationListener::class.java),
        )
        assertNull(advertiser.registeredName)
    }

    @Test
    fun staleConflictRetryCannotResurrectAfterUnadvertise() {
        val manager = mock(NsdManager::class.java)
        val advertiser = ControllerAdvertiser(contextWith(manager))
        advertiser.advertise("TV", "tv-1", 1000, "kart")
        val listener = captureRegistration(manager).single()
        advertiser.unadvertise()
        // A failure delivered for the torn-down registration must not re-register.
        listener.onRegistrationFailed(mock(NsdServiceInfo::class.java), 1)
        verify(manager, times(1)).registerService(
            any(NsdServiceInfo::class.java), anyInt(), any(NsdManager.RegistrationListener::class.java),
        )
    }

    @Test
    fun advertiseSecurityExceptionMarksBlocked() {
        val manager = mock(NsdManager::class.java)
        doThrow(SecurityException("blocked")).`when`(manager)
            .registerService(any(NsdServiceInfo::class.java), anyInt(), any(NsdManager.RegistrationListener::class.java))
        val advertiser = ControllerAdvertiser(contextWith(manager))
        advertiser.advertise("TV", "tv-1", 1000, "kart")
        assertTrue(advertiser.localNetworkBlocked)
        assertNull(advertiser.registeredName)
        // Nothing was registered, so unadvertise stays silent.
        advertiser.unadvertise()
        verify(manager, never()).unregisterService(any(NsdManager.RegistrationListener::class.java))
    }

    @Test
    fun readvertiseReplacesPriorRegistration() {
        val manager = mock(NsdManager::class.java)
        val advertiser = ControllerAdvertiser(contextWith(manager))
        advertiser.advertise("TV", "tv-1", 1000, "kart")
        advertiser.advertise("TV", "tv-1", 2000, "kart")
        verify(manager, times(1)).unregisterService(any(NsdManager.RegistrationListener::class.java))
        verify(manager, times(2)).registerService(
            any(NsdServiceInfo::class.java), anyInt(), any(NsdManager.RegistrationListener::class.java),
        )
    }
}
