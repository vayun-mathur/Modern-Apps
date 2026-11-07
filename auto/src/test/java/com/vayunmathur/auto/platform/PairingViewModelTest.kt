package com.vayunmathur.auto.platform

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.vayunmathur.auto.protocol.UsbSessionState
import com.vayunmathur.auto.protocol.WirelessSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PairingViewModelTest {
    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        TransportState.publishRole(false)
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain(); TransportState.publishRole(false) }

    private var store = ViewModelStore()
    private fun vm(): PairingViewModel {
        store.clear(); store = ViewModelStore()
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(c: Class<T>): T = PairingViewModel() as T
        })[PairingViewModel::class.java]
    }

    @Test fun `defaults mirror transport state`() {
        val vm = vm()
        assertEquals(UsbSessionState.DETACHED, vm.usbState.value)
        assertNull(vm.usbLabel.value)
        assertEquals(WirelessSessionState.IDLE, vm.wirelessState.value)
        assertFalse(vm.roleHeld.value)
        assertNull(vm.selected.value)
        store.clear()
    }

    @Test fun `role held flows through while subscribed`() {
        TransportState.publishRole(true)
        val vm = vm()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.roleHeld.collect() }
        try { assertEquals(true, vm.roleHeld.value) } finally { job.cancel(); store.clear() }
    }
}
