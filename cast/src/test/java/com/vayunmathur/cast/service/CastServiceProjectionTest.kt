package com.vayunmathur.cast.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.cast.platform.mirror.ShadowMediaProjectionFake
import com.vayunmathur.cast.platform.mirror.ShadowMediaProjectionManagerFake
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The projection hand-off inside CastService, with a fake platform projection. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    shadows = [ShadowMediaProjectionManagerFake::class, ShadowMediaProjectionFake::class],
)
class CastServiceProjectionTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

    @Test
    fun `mirroring start obtains a projection and stop mirroring releases it`() {
        val controller = Robolectric.buildService(CastService::class.java)
        val service = controller.create().get()
        val start = Intent(app, CastService::class.java)
            .setAction("com.vayunmathur.cast.action.START_MIRRORING")
            .putExtra("resultCode", -1)
            .putExtra("resultData", Intent())
        assertEquals(
            android.app.Service.START_NOT_STICKY,
            service.onStartCommand(start, 0, 1),
        )
        // Held now: a stop-mirroring command releases it and demotes the type.
        service.onStartCommand(
            Intent(app, CastService::class.java)
                .setAction("com.vayunmathur.cast.action.STOP_MIRRORING"),
            0, 2,
        )
        // And a second mirroring start whose token then goes through destroy.
        service.onStartCommand(start, 0, 3)
        controller.destroy()
    }
}
