package com.vayunmathur.openassistant.util

import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.robolectric.Robolectric

/**
 * InferenceService lifecycle surface that does not need the native engine:
 * binding, null-intent start, and the shared prompt/title state.
 * (Inference itself is native-bound: Gemma4Handle cannot load on the JVM.)
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class InferenceServiceTest {

    @Test fun promptAndSharedState() {
        assertTrue(InferenceService.DEFAULT_SYSTEM_PROMPT.contains("helpful Android assistant"))
        assertTrue(InferenceService.DEFAULT_SYSTEM_PROMPT.contains("add_to_memory"))
        assertEquals("system_prompt", InferenceService.KEY_SYSTEM_PROMPT)
        InferenceService.newTitle = "A title"
        assertEquals("A title", InferenceService.newTitle)
        InferenceService.newTitle = null
        assertNull(InferenceService.newTitle)
        InferenceService.halt = true
        assertTrue(InferenceService.halt)
        InferenceService.halt = false
    }

    @Test fun serviceLifecycleBasics() {
        val controller = Robolectric.buildService(InferenceService::class.java)
        val service = controller.create().get()
        assertNull(service.onBind(null))
        // Null intent (system restart) is sticky and queues nothing.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        // An intent with no conversation and no schema/receiver queues nothing.
        val bare = Intent(ctx, InferenceService::class.java)
        assertEquals(Service.START_STICKY, service.onStartCommand(bare, 0, 2))
        controller.destroy()
    }
}
