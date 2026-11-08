package com.vayunmathur.library.ui

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice 3/3 round 10: ringtone picker intent/result round-trip. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round10Test {

    @Test fun pickerIntentCarriesTypeTitleAndFlags() {
        val intent = ringtonePickerIntent(
            existing = "content://media/internal/audio/media/42",
            type = RingtoneManager.TYPE_ALARM,
            title = "Pick alarm sound",
        )
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, intent.action)
        assertEquals(
            RingtoneManager.TYPE_ALARM,
            intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1),
        )
        assertEquals("Pick alarm sound", intent.getStringExtra(RingtoneManager.EXTRA_RINGTONE_TITLE))
        assertTrue(intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false))
        assertTrue(intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false))
        assertEquals(
            Uri.parse("content://media/internal/audio/media/42"),
            intent.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI),
        )
    }

    @Test fun pickerIntentSilentExistingHasNoUri() {
        val intent = ringtonePickerIntent(RINGTONE_SILENT, RingtoneManager.TYPE_RINGTONE, "T")
        assertNull(intent.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI))
    }

    @Test fun pickerResultReturnsPickedUriString() {
        val picked = Uri.parse("content://media/internal/audio/media/7")
        val data = Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, picked)
        assertEquals(picked.toString(), ringtonePickerResult(data))
    }

    @Test fun pickerResultNullOrMissingMeansSilent() {
        assertEquals(RINGTONE_SILENT, ringtonePickerResult(null))
        assertEquals(RINGTONE_SILENT, ringtonePickerResult(Intent()))
    }
}
