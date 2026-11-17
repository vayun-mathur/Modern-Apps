package com.vayunmathur.keyboard.platform.ime

import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder
import com.vayunmathur.keyboard.R
import com.vayunmathur.keyboard.domain.KeyboardLayouts
import com.vayunmathur.keyboard.data.KeyboardSettings
import com.vayunmathur.keyboard.domain.ShiftState
import kotlinx.coroutines.launch

/**
 * Framework subtype registration split from KeyboardService
 * (LargeClass/TooManyFunctions). Behaviour identical.
 */

/**
 * Layouts that stay registered as framework subtypes on platforms below API 34 even when
 * they share a language prefix with another layout. Same-language *arrangements* (QWERTY
 * vs Dvorak) collapse to one entry there because the platform cannot label them apart, but
 * these are different inputs, not different arrangements: hiding one would remove a script
 * the user cannot reach any other way.
 */
private val PRE34_DISTINCT_LAYOUTS = setOf("zh_pinyin", "zh_pinyin_tc", "ja_romaji", "ja_kana", "tr_q", "tr_f")

/**
 * Register the whole layout catalog with the framework as additional input-method
 * subtypes, so the user enables the ones they want in Android's own "Languages" screen
 * and switches between them with the system globe. The layout id rides along in the
 * subtype's extra value so [onCurrentInputMethodSubtypeChanged] can map a framework
 * switch back to a layout.
 *
 * Several layouts can share a language (English alone has QWERTY, Dvorak, Colemak…), so
 * each subtype needs a distinct name to be tellable apart in the enabler. Naming a
 * subtype requires [InputMethodSubtypeBuilder.setSubtypeNameOverride], added in API 34;
 * below that the extra same-language *arrangements* are not registered (rather than
 * showing several indistinguishable "English" entries), but layouts that are different
 * inputs rather than different arrangements — simplified vs traditional, romaji vs kana,
 * Q vs F — are kept, via [PRE34_DISTINCT_LAYOUTS]. The fallback name resource keeps
 * the entry labelled on old platforms instead of rendering blank.
 */
internal fun KeyboardService.registerLayoutSubtypes() {
    val imm = getSystemService(InputMethodManager::class.java) ?: return
    val id = imeId ?: imm.inputMethodList
        .firstOrNull { it.packageName == packageName }?.id
    if (id == null) return
    imeId = id
    val nameable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val layouts = if (nameable) {
        KeyboardLayouts.ALL
    } else {
        val seen = HashSet<String>()
        KeyboardLayouts.ALL.filter { layout ->
            layout.id in PRE34_DISTINCT_LAYOUTS || seen.add(layout.id.substringBefore('_'))
        }
    }
    val map = LinkedHashMap<String, InputMethodSubtype>()
    for (layout in layouts) {
        val builder = InputMethodSubtypeBuilder()
            .setSubtypeMode("keyboard")
            .setLanguageTag(layout.id.substringBefore('_'))
            .setSubtypeExtraValue("layoutId=${layout.id}")
            .setSubtypeId(layout.id.hashCode())
            .setSubtypeNameResId(R.string.subtype_keyboard)
        if (nameable) {
            builder.setSubtypeNameOverride(layout.description)
        }
        map[layout.id] = builder.build()
    }
    subtypeByLayoutId = map
    imm.setAdditionalInputMethodSubtypes(id, map.values.toTypedArray())
}

/** Pull the layout id out of a framework subtype, by extra value then language tag. */
internal fun KeyboardService.layoutIdOf(subtype: InputMethodSubtype?): String? {
    if (subtype == null) return null
    val fromExtra = subtype.extraValue
        ?.split(',')
        ?.firstOrNull { it.startsWith("layoutId=") }
        ?.substringAfter('=')
    if (fromExtra != null && subtypeByLayoutId.containsKey(fromExtra)) return fromExtra
    val tag = subtype.languageTag
    return KeyboardLayouts.ALL.firstOrNull {
        it.id.substringBefore('_') == tag
    }?.id
}

/**
 * Switch the active layout to [id], committing anything still composing first (the
 * layouts may not share a script). Shared by the framework subtype change and start-up.
 */
internal fun KeyboardService.applyLayout(id: String) {
    currentInputConnection?.let {
        finishComposition(it)
        commitCurrentWord(it, autoCorrect = false)
    }

    kbState.settings = kbState.settings.copy(activeLayoutId = id)
    kbState.shift = ShiftState.OFF
    kbState.suggestions = emptyList()
    syncComposer()
    scope.launch { ds.setString(KeyboardSettings.Keys.ACTIVE_LAYOUT, id) }
    updateAutoCapShift()
}
