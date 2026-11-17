package com.vayunmathur.keyboard.domain

/**
 * The catalogue of layouts the user can enable in settings.
 *
 * The rule every entry keeps is that you can write the language with it: each layout carries
 * its script's whole alphabet across its rows, its shift layer and its long-press alternates.
 * Where a letter would not fit that is checked against Unicode rather than assumed.
 *
 * Where a standard national layout exists and is known, the rows reproduce it, so muscle memory
 * from a physical keyboard carries over. Where it is not — the scripts marked "alphabetical" —
 * the rows are the script's alphabet in order, split across a base and a shift layer: learnable,
 * complete, and honest about what it is. Those are the first ones to replace as speakers of
 * those languages report what the real fingering should be.
 *
 * Korean, Chinese, Japanese and Ethiopic are typed through the composition engines in
 * [com.vayunmathur.keyboard.platform.ime.Composer]: for those the rows are only half the story, since
 * what a key produces depends on the keys around it.
 */
object KeyboardLayouts {

    /** Used when nothing is stored yet, and as the fallback for an unknown persisted id. */
    val DEFAULT: KeyboardLayout by lazy { ALL.first() }

    fun byId(id: String): KeyboardLayout? = byId[id]

    /**
     * Every layout, in picker order: Latin first (most users), then Cyrillic, then the
     * remaining scripts.
     */
    val ALL: List<KeyboardLayout> by lazy {
        latinKeyboardLayouts + cyrillicKeyboardLayouts + otherScriptKeyboardLayouts
    }

    private val byId: Map<String, KeyboardLayout> by lazy { ALL.associateBy { it.id } }
}
