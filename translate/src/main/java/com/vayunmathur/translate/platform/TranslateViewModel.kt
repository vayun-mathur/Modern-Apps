package com.vayunmathur.translate.platform

import com.vayunmathur.translate.data.SharedImageCache
import com.vayunmathur.translate.data.TranslateRepository
import com.vayunmathur.translate.data.TranslateSettings
import com.vayunmathur.translate.data.TranslationRecord
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.domain.TranslationEngine

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ocr.OcrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An image handed over by Share / Open with, waiting for the navigation to open it. */
sealed interface SharedImageArrival {
    /** Copied into the app cache; [imageUri] is the copy's file uri. */
    data class Ready(val imageUri: String) : SharedImageArrival

    /** Not opened; [reason] says why, for the message shown instead. */
    data class Rejected(val reason: SharedImageCache.Result) : SharedImageArrival
}

/**
 * Holds the shared engines (translation, OCR, TTS) and the persisted language
 * selection. The NLLB-200 model is now gated via
 * [com.vayunmathur.library.downloadservice.InitialModelDownloadChecker] in
 * MainActivity — just like OpenAssistant — so it starts installing automatically
 * as soon as the page is opened. By the time this screen is shown,
 * [NllbModel] files are already present, and we just load the Vulkan engine.
 */
class TranslateViewModel(app: Application) : AndroidViewModel(app) {

    val translator: TranslationEngine = NllbTranslator(app)
    val ocr = OcrEngine(app)
    private val tts = TtsSpeaker(app)
    private val settings = TranslateSettings(app)
    private val historyRepo = TranslateRepository.get(app)

    private val _sourceLang = MutableStateFlow(Languages.AUTO.code)
    val sourceLang: StateFlow<String> = _sourceLang.asStateFlow()

    private val _targetLang = MutableStateFlow("es")
    val targetLang: StateFlow<String> = _targetLang.asStateFlow()

    /** Most-recently-used language codes per direction, for the language picker. */
    private val _recentSourceLangs = MutableStateFlow<List<String>>(emptyList())
    val recentSourceLangs: StateFlow<List<String>> = _recentSourceLangs.asStateFlow()

    private val _recentTargetLangs = MutableStateFlow<List<String>>(emptyList())
    val recentTargetLangs: StateFlow<List<String>> = _recentTargetLangs.asStateFlow()

    /** True once the NLLB engine is loaded (model already downloaded by the checker). */
    private val _translationAvailable = MutableStateFlow(false)
    val translationAvailable: StateFlow<Boolean> = _translationAvailable.asStateFlow()

    /** Saved translations, favorites first then newest — for the history screen. */
    val history: StateFlow<List<TranslationRecord>> = historyRepo.history.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )

    /** Favorites only, newest first. */
    val favorites: StateFlow<List<TranslationRecord>> = historyRepo.favorites.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )

    /**
     * The latest shared/opened image, held until [consumeSharedImage]. It lives here,
     * not in the activity, so it waits out the model-download gate and survives the
     * activity being recreated before the navigation has opened it.
     */
    private val _sharedImage = MutableStateFlow<SharedImageArrival?>(null)
    val sharedImage: StateFlow<SharedImageArrival?> = _sharedImage.asStateFlow()

    /**
     * Set once the activity has looked at its launch intent, so a recreated activity
     * (same intent, same ViewModel) does not open the image a second time.
     */
    var launchIntentHandled: Boolean = false

    private val sharedImageCache = SharedImageCache.inCacheDir(app.cacheDir)

    init {
        viewModelScope.launch {
            _sourceLang.value = settings.source()
            _targetLang.value = settings.target()
            _recentSourceLangs.value = settings.recentSources()
            _recentTargetLangs.value = settings.recentTargets()
            _translationAvailable.value = translator.isAvailable()
        }
    }

    fun setSource(code: String) {
        _sourceLang.value = code
        viewModelScope.launch {
            settings.setSource(code)
            settings.pushRecentSource(code)
            _recentSourceLangs.value = settings.recentSources()
        }
    }

    fun setTarget(code: String) {
        _targetLang.value = code
        viewModelScope.launch {
            settings.setTarget(code)
            settings.pushRecentTarget(code)
            _recentTargetLangs.value = settings.recentTargets()
        }
    }

    /** Swap source and target. No-op while source is Auto (nothing to swap into). */
    fun swap() {
        val src = _sourceLang.value
        if (src == Languages.AUTO.code) return
        val tgt = _targetLang.value
        setSource(tgt)
        setTarget(src)
    }

    /** Translate using the current selection; [from] null means auto-detect. */
    suspend fun translate(text: String): String? {
        val from = _sourceLang.value.takeIf { it != Languages.AUTO.code }
        return translator.translate(text, from, _targetLang.value)
    }

    /**
     * Save a completed translation to history. Called by the page once the debounced
     * translation lands; the repository dedupes re-translations of the same input.
     *
     * The pair rides along explicitly rather than being re-read here: the language
     * can change between the translate call and this write, and the record must
     * name the pair that was actually translated.
     */
    fun recordHistory(source: String, translated: String, src: String, tgt: String) {
        viewModelScope.launch { historyRepo.record(source, translated, src, tgt) }
    }

    fun setFavorite(id: Long, favorite: Boolean) {
        viewModelScope.launch { historyRepo.setFavorite(id, favorite) }
    }

    fun deleteHistory(record: TranslationRecord) {
        viewModelScope.launch { historyRepo.delete(record) }
    }

    /** Removes every non-favorite row. Favorites are kept. */
    fun clearHistory() {
        viewModelScope.launch { historyRepo.clearHistory() }
    }

    /**
     * Speak [text] in [languageCode] (defaults to the current target). Nothing is spoken
     * when the TTS engine has no voice for that language — [onMissingVoice] fires instead,
     * so the caller can say so rather than have it read out in the device's language.
     */
    fun speak(
        text: String,
        languageCode: String = _targetLang.value,
        onMissingVoice: (() -> Unit)? = null,
    ) = tts.speak(text, languageCode, onMissingVoice)

    fun stopSpeaking() = tts.stop()

    /**
     * Copies the image at [uri] (a sender's content uri, readable only for now) into
     * the app cache, then publishes it through [sharedImage]. See [SharedImageCache]
     * for why the copy is needed.
     */
    @Suppress("TooGenericExceptionCaught")
    fun openSharedImage(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    resolver.openInputStream(uri)?.use { sharedImageCache.store(it) }
                        ?: SharedImageCache.Result.Unreadable
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Another app's provider is on the other end: besides IOException
                    // and SecurityException (no or expired grant) it can throw any
                    // RuntimeException, all of which mean "couldn't read that image".
                    Log.status(TAG, "shared image unreadable", e)
                    SharedImageCache.Result.Unreadable
                }
            }
            _sharedImage.value = when (result) {
                is SharedImageCache.Result.Cached ->
                    SharedImageArrival.Ready(Uri.fromFile(result.file).toString())
                else -> SharedImageArrival.Rejected(result)
            }
        }
    }

    /** The navigation has acted on [sharedImage]; clear it so it is not acted on again. */
    fun consumeSharedImage() {
        _sharedImage.value = null
    }

    override fun onCleared() {
        ocr.close()
        (translator as? NllbTranslator)?.close()
        tts.shutdown()
    }
}

private const val TAG = "TranslateViewModel"
