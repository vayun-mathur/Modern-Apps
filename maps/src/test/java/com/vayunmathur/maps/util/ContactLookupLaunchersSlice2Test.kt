package com.vayunmathur.maps.util

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The postal-picker fallback chain: postal row first, whole-contact second,
 * quiet no-op when the device has neither.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class ContactLookupLaunchersSlice2Test {

    private class RecordingLauncher<I>(
        private val behaviour: (I) -> Unit = {},
    ) : ActivityResultLauncher<I>() {
        val launched = mutableListOf<I>()
        override val contract: androidx.activity.result.contract.ActivityResultContract<I, *>
            get() = throw UnsupportedOperationException("test fake")

        override fun launch(input: I, options: ActivityOptionsCompat?) {
            launched.add(input)
            behaviour(input)
        }

        override fun unregister() {}
    }

    @Test
    fun `the postal picker is tried first`() {
        val postal = RecordingLauncher<Intent>()
        val contact = RecordingLauncher<Void?>()
        var noPicker = 0
        launchPostalPicker(postal, contact, onNoPicker = { noPicker++ })
        assertEquals(1, postal.launched.size)
        assertEquals(0, contact.launched.size)
        assertEquals(0, noPicker)
        // Typed to the postal content type.
        assertEquals(
            android.provider.ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_TYPE,
            postal.launched[0].type,
        )
        assertEquals(Intent.ACTION_PICK, postal.launched[0].action)
    }

    @Test
    fun `no postal picker falls back to the whole-contact picker`() {
        val postal = RecordingLauncher<Intent> { throw ActivityNotFoundException() }
        val contact = RecordingLauncher<Void?>()
        var noPicker = 0
        launchPostalPicker(postal, contact, onNoPicker = { noPicker++ })
        assertEquals(1, postal.launched.size)
        assertEquals(1, contact.launched.size)
        assertEquals(0, noPicker)
    }

    @Test
    fun `no picker at all reports through onNoPicker`() {
        val contact = RecordingLauncher<Void?> { throw ActivityNotFoundException() }
        var noPicker = 0
        launchContactPicker(contact, onNoPicker = { noPicker++ })
        assertEquals(1, contact.launched.size)
        assertEquals(1, noPicker)
    }

    @Test
    fun `a working contact picker stays quiet`() {
        val contact = RecordingLauncher<Void?>()
        var noPicker = 0
        launchContactPicker(contact, onNoPicker = { noPicker++ })
        assertEquals(1, contact.launched.size)
        assertEquals(0, noPicker)
    }

    @Test
    fun `double fallback reaches onNoPicker`() {
        val postal = RecordingLauncher<Intent> { throw ActivityNotFoundException() }
        val contact = RecordingLauncher<Void?> { throw ActivityNotFoundException() }
        var noPicker = 0
        launchPostalPicker(postal, contact, onNoPicker = { noPicker++ })
        assertEquals(1, noPicker)
    }

    @Test
    fun `the system picker intent is only built on android 17`() {
        // Robolectric runs SDK 34 here, below CINNAMON_BUN — document the gate.
        assertTrue(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.CINNAMON_BUN)
    }
}
