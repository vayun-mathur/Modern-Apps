package com.vayunmathur.youpipe.ui.tv

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.tv.MaTvTheme
import com.vayunmathur.library.tv.TvAccent
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.youpipe.platform.DEFAULT_PAGE_KEY
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.YouPipeViewModelFactory

/**
 * The TV entry: opened from TV launcher rows (LEANBACK_LAUNCHER). The phone MainActivity
 * stays exactly as it is — this activity hosts the D-pad browse + player UI.
 *
 * Landscape-locked and non-edge-to-edge per the manifest; the activity owns the [TvDpadInput]
 * key map so scrub/volume/BACK work consistently across browse and player. The theme is the
 * shared always-dark [MaTvTheme] in YouPipe red — never a phone dynamic scheme.
 */
class TvMainActivity : MaActivity() {
    override val edgeToEdge: Boolean = false

    private val youPipeViewModel: YouPipeViewModel by viewModels {
        YouPipeViewModelFactory(
            application,
            SubscriptionRepository.get(application),
        )
    }

    private lateinit var initialBackStack: List<TvRoute>

    override fun onCreateBeforeContent() {
        // Same arming the phone does: touch the VM so its hourly subscription task schedules.
        youPipeViewModel
        // Honor the "Open to" default page (same DataStore key + mapping the phone uses),
        // read synchronously here because this runs in onCreate before setContent.
        initialBackStack = tvDefaultBackStack(DataStoreUtils.getInstance(this).getString(DEFAULT_PAGE_KEY))
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        // Always dark in YouPipe red; screens set their own backgrounds over it.
        MaTvTheme(accent = TvAccent.Red, content = content)
    }

    @Composable
    override fun Content() {
        TvNavigation(youPipeViewModel, initialBackStack)
    }
}
