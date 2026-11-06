package com.vayunmathur.maps.ipc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The findfamily channel client: absent-service behaviour and the
 * composition-bound state holder.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class FamilyLocationClientSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `bind with no service is a silent no-op`() {
        // findfamily is not installed in the test package, so resolving
        // returns null and bind must simply do nothing.
        var updates = 0
        val client = FamilyLocationClient(context) { updates++ }
        client.bind()
        client.bind()
        assertEquals(0, updates)
    }

    @Test
    fun `unbind without bind is a silent no-op`() {
        var updates = 0
        val client = FamilyLocationClient(context) { updates++ }
        client.unbind()
        assertEquals(0, updates)
    }

    @Test
    fun `unbind after a failed bind stays silent`() {
        var updates = 0
        val client = FamilyLocationClient(context) { updates++ }
        client.bind()
        client.unbind()
        client.unbind()
        assertEquals(0, updates)
    }

    @Test
    fun `the composable holds an empty list when the service is absent`() {
        var seen: List<FamilyMember>? = null
        rule.setContent {
            val members = rememberFamilyMembers()
            seen = members.value
        }
        rule.waitForIdle()
        assertTrue(seen != null)
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `the composable state is observable`() {
        var observed by mutableStateOf(0)
        rule.setContent {
            val members = rememberFamilyMembers()
            observed = members.value.size
        }
        rule.waitForIdle()
        assertEquals(0, observed)
    }
}
