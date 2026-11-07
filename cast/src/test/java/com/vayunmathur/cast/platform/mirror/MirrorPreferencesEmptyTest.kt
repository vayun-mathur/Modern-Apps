package com.vayunmathur.cast.platform.mirror

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** A fresh install: nothing stored yet, so every lookup answers empty. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MirrorPreferencesEmptyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `no stored target or key on a fresh install`() = runTest {
        assertNull(MirrorPreferences.target(context))
        assertNull(MirrorPreferences.deviceKey(context, "fresh-tv"))
    }
}
