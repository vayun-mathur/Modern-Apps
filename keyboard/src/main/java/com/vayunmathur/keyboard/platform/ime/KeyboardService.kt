package com.vayunmathur.keyboard.platform.ime

import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vayunmathur.keyboard.platform.VoiceInput
import com.vayunmathur.keyboard.platform.VoicePermission
import com.vayunmathur.keyboard.ui.KeyboardScreen
import com.vayunmathur.keyboard.data.ClipboardStore
import com.vayunmathur.keyboard.domain.ComposerKind
import com.vayunmathur.keyboard.data.Dictionary
import com.vayunmathur.keyboard.data.EmojiData
import com.vayunmathur.keyboard.data.KeyboardSettings
import com.vayunmathur.keyboard.data.PinyinDictionary
import com.vayunmathur.keyboard.data.RecentEmoji
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The input method (IME). Renders the keyboard with Compose and turns key actions into edits
 * on the target field's [android.view.inputmethod.InputConnection].
 *
 * Compose views need a [LifecycleOwner], [ViewModelStoreOwner] and [SavedStateRegistryOwner]
 * in their view tree; an [InputMethodService] provides none, so this service implements all
 * three and drives the lifecycle from the IME window callbacks.
 *
 * Key handling lives in `KeyboardService*Ops.kt` (same package, internal extensions) and the
 * [ImeActions] implementation in [KeyboardActionHandler]; this class keeps only the
 * framework lifecycle callbacks. Behaviour is unchanged.
 */
class KeyboardService : InputMethodService(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    internal lateinit var ds: DataStoreUtils
    internal val kbState = KeyboardState()
    internal var dictionary: Dictionary = Dictionary.EMPTY

    // Credential-encrypted cache, like before: pre-unlock the files are unreadable rather
    // than readable-without-a-passcode, so clipboard I/O below tolerates failure and the
    // history is simply unavailable until first unlock (see onCreate/restoreState).
    internal val clipboard by lazy { ClipboardStore(File(cacheDir, "clips")) }
    internal var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** Clears a strip notice after a while; cancelled by the next notice. */
    internal var noticeJob: Job? = null

    /** Id of the clip the chip is currently offering, so a stale timeout can't clear a newer one. */
    internal var chipClipId = 0L

    /** The word currently being composed (underlined) on the letters page. */
    internal val composing = StringBuilder()

    /**
     * What is in front of the cursor, mirrored locally rather than read back from the field
     * on every keystroke. Every edit below goes through [commit], [setComposing],
     * [finishComposing] or [deleteBackward] so it stays in step.
     */
    internal val before = TextBeforeCursor()

    /** The composition currently shown, so finalizing it can be mirrored into [before]. */
    internal var pendingComposition: CharSequence = ""

    /**
     * The composition engine for the active layout, or null for layouts whose keys are
     * already characters. Owns everything about Hangul/pinyin/kana input; the service only
     * applies what it returns to the [android.view.inputmethod.InputConnection].
     */
    internal var composer: Composer? = null
    internal var composerKind: ComposerKind? = null
    internal var pinyinSimplified = PinyinDictionary.EMPTY
    internal var pinyinTraditional = PinyinDictionary.EMPTY
    internal var bopomofoSpellings: Map<String, String> = emptyMap()
    internal var loadingChinese = false

    internal var lastSpaceTime = 0L
    internal var lastShiftTime = 0L

    /** Enter behaviour derived from the current field. */
    internal var editorActionId = EditorInfo.IME_ACTION_UNSPECIFIED
    internal var enterSendsAction = false

    internal val voiceInput by lazy { VoiceInput(this) }

    /** Clears a dictation failure from the strip after a while; cancelled by the next change. */
    internal var voiceMessageJob: Job? = null

    /** True between [onWindowShown] and [onWindowHidden] — i.e. while there is a live field. */
    internal var windowShown = false

    /**
     * When the microphone was granted while the keyboard was away for the prompt, so
     * dictation can pick up where it left off. Zero when nothing is waiting.
     */
    internal var voiceGrantedAt = 0L

    /** This IME's id in the framework, resolved once from [InputMethodManager.getInputMethodList]. */
    internal var imeId: String? = null

    /** Layout id -> the framework subtype registered for it, for subtype lookup. */
    internal var subtypeByLayoutId: Map<String, InputMethodSubtype> = emptyMap()

    internal val audio by lazy { getSystemService(AudioManager::class.java) }

    private val actionsHandler by lazy { KeyboardActionHandler(this) }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        ds = DataStoreUtils.getInstance(this, deviceProtected = true)
        kbState.settings = KeyboardSettings.load(ds)

        // Load the dictionary off the main thread; suggestions stay empty until it is ready.
        scope.launch { dictionary = Dictionary.load(this@KeyboardService) }
        scope.launch { kbState.emojiData = EmojiData.load(this@KeyboardService) }
        kbState.recentEmoji = RecentEmoji.decode(ds.getString(KeyboardSettings.Keys.EMOJI_RECENTS))
        scope.launch(Dispatchers.IO) {
            migrateLegacyClips()
            // Pre-unlock (direct boot) credential-encrypted storage is unreadable;
            // the history then stays empty until the next start rather than crashing.
            runCatching { clipboard.restoreState() }
            val items = clipboard.items
            withContext(Dispatchers.Main) { kbState.clips = items }
        }
        observeClipboard()
        syncComposer()
        registerLayoutSubtypes()
        observeSettings()
        scope.launch { VoicePermission.results.collect { onVoicePermissionResult(it) } }
    }

    override fun onCreateInputView(): View {
        // Compose resolves its per-window recomposer from the IME window's decor view
        // (the ancestor of the framework's `parentPanel`), NOT from our ComposeView.
        // Setting the ViewTree owners only on the ComposeView therefore crashes with
        // "ViewTreeLifecycleOwner not found"; they must live on the window decor view.
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@KeyboardService)
            setViewTreeViewModelStoreOwner(this@KeyboardService)
            setViewTreeSavedStateRegistryOwner(this@KeyboardService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Some devices dispatch window insets to the input view; capture them when
            // they do (complements the decor-view read in updateBottomInset()).
            setOnApplyWindowInsetsListener { _, insets ->
                kbState.bottomInsetPx = insets.getInsets(
                    android.view.WindowInsets.Type.navigationBars(),
                ).bottom
                insets
            }
            setContent {
                // The framework draws the "hide keyboard" chevron in the navigation bar of the IME
                // window, not inside our Compose tree. Its icon color is the nav-bar icon appearance,
                // which defaults to light (white) icons. In a light theme that renders nearly invisible,
                // so track the theme: light theme -> dark nav-bar icons, dark theme -> light icons.
                val isDark = isSystemInDarkTheme()
                LaunchedEffect(isDark) {
                    window?.window?.let { imeWindow ->
                        WindowInsetsControllerCompat(imeWindow, imeWindow.decorView)
                            .isAppearanceLightNavigationBars = !isDark
                    }
                }
                DynamicTheme {
                    KeyboardScreen(kbState, actionsHandler)
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        pendingComposition = ""
        before.onStartInput(info.initialSelStart, info.initialSelEnd)
        // Open on whatever subtype the system currently has selected for us, so the app's
        // active layout tracks the framework (e.g. after a system-level switch).
        val current = getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype
        layoutIdOf(current)?.let {
            if (it != kbState.settings.activeLayout.id) applyLayout(it)
        }
        syncComposer()
        composer?.reset()
        // After the field kind: the composer follows the effective (possibly
        // ASCII-forced) layout, not just the user's layout.
        configureForEditor(info)
        syncComposer()
        composer?.reset()
        kbState.suggestions = emptyList()
        kbState.emojiQuery = null
        kbState.emojiResults = emptyList()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        updateAutoCapShift()
        // The listener only fires while we are bound, so anything copied while the keyboard
        // was away is picked up here instead. It is not treated as a password-field copy:
        // this clip predates the field, whoever it came from.
        captureCurrentClip(inPasswordField = false)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        updateBottomInset()
        windowShown = true
        // Resume the dictation the permission prompt interrupted — but only if the keyboard
        // came straight back. Granting and then wandering off must not open the microphone
        // the next time some unrelated field is tapped.
        if (voiceGrantedAt != 0L) {
            val resumed = android.os.SystemClock.uptimeMillis() - voiceGrantedAt < VOICE_GRANT_MS
            voiceGrantedAt = 0L
            if (resumed) beginListening()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        windowShown = false
        // Keep the composition alive (STARTED, not DESTROYED) so re-showing is instant.
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        before.onSelectionChanged(newSelStart, newSelEnd)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        composing.setLength(0)
        pendingComposition = ""
        before.invalidate()
        composer?.reset()
        // There is no longer a field for a transcript to land in. A failure raised *after*
        // this (the permission prompt is what took the field away) still shows on the way back.
        if (kbState.voice != null) dismissVoice()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        clipListener?.let {
            getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(it)
        }
        clipListener = null
        voiceInput.destroy()
        store.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val id = layoutIdOf(newSubtype) ?: return
        if (id == kbState.settings.activeLayout.id) return
        applyLayout(id)
    }
}
