package com.vayunmathur.keyboard.platform.ime

/**
 * The two Japanese input styles: romaji typed on a Latin layout, and kana typed directly on
 * the JIS kana layout.
 *
 * Neither converts kana to kanji. That step needs a morphological dictionary of the language
 * — hundreds of thousands of entries with readings and costs — which this keyboard does not
 * ship and cannot derive from Unicode data the way the Chinese index is derived. Kana-only
 * Japanese is a real, usable keyboard (it is what the kana-lock modes of desktop IMEs give
 * you); it is just not the whole of Japanese input, and the layout descriptions say so.
 */

/** Hiragana → katakana: the two blocks are laid out in parallel, 0x60 apart. */
private fun toKatakana(text: String): String = buildString {
    for (c in text) append(if (c in 'ぁ'..'ん') c + KATAKANA_OFFSET else c)
}

/** Offset between parallel hiragana and katakana blocks. */
private const val KATAKANA_OFFSET = 0x60

/**
 * Romaji (ローマ字入力): the standard way Japanese is typed on a Latin keyboard.
 *
 * Conversion is greedy and incremental — `kyo` has to wait for its third letter, `kk` becomes
 * っ before the second `k` is even spelled, and a lone `n` stays a lone `n` until something
 * proves it was ん. Anything typed with shift produces katakana.
 */
class RomajiComposer : Composer {

    private val kana = StringBuilder()
    private val pending = StringBuilder()
    private var katakana = false

    override val composing: String get() = kana.toString() + pending

    override fun accept(text: String): ComposeResult? {
        if (text.length != 1) return null
        val typed = text[0]
        val letter = typed.lowercaseChar()
        if (letter !in 'a'..'z' && letter != '-' && letter != '\'') return null
        if (!isComposing) katakana = typed.isUpperCase()
        pending.append(letter)
        convert()
        return ComposeResult(composing = composing)
    }

    private fun convert() {
        while (pending.isNotEmpty()) {
            val outcome = consumeOne(pending.toString())
            if (outcome == StepResult.WAIT) return
            if (outcome == StepResult.UNMATCHED) spillFirst()
        }
    }

    private enum class StepResult { PROGRESSED, WAIT, UNMATCHED }

    private fun consumeOne(spelling: String): StepResult {
        return when {
            tryDirectMatch(spelling) -> StepResult.PROGRESSED
            trySokuon(spelling) -> StepResult.PROGRESSED
            else -> resolveNonDirect(spelling)
        }
    }

    private fun resolveNonDirect(spelling: String): StepResult {
        val nOutcome = resolveLeadingN(spelling)
        if (nOutcome != null) return nOutcome
        return if (spelling in PREFIXES) StepResult.WAIT else StepResult.UNMATCHED
    }

    /** Emit a complete spelling, clearing what is waiting. True when it matched. */
    private fun tryDirectMatch(spelling: String): Boolean {
        val direct = ROMAJI[spelling] ?: return false
        emit(direct)
        pending.setLength(0)
        return true
    }

    /** A doubled consonant is the sokuon: `kko` is っ then `ko`. True when it matched. */
    private fun trySokuon(spelling: String): Boolean {
        if (spelling.length < 2 || spelling[0] != spelling[1] || spelling[0] in "aiueon") {
            return false
        }
        emit("っ")
        pending.deleteCharAt(0)
        return true
    }

    /**
     * Settle a leading `n`, or null when this spelling does not start with one. Null means
     * "not an n-case" — the caller still checks [PREFIXES], since `na` etc. keep growing.
     */
    private fun resolveLeadingN(spelling: String): StepResult? {
        if (spelling.length < 2 || spelling[0] != 'n') return null
        return resolveNSecond(spelling)
    }

    private fun resolveNSecond(spelling: String): StepResult? {
        // `nn` cannot be resolved yet: `konnichiwa` is こんにちは, so the second `n`
        // is ん only if no vowel follows it. Everything that cannot continue な行
        // proves the first `n` was ん all along.
        if (spelling[1] == 'n') {
            if (spelling.length == 2) return StepResult.WAIT
            emit("ん")
            pending.deleteCharAt(0)
            return StepResult.PROGRESSED
        }
        if (spelling[1] !in "aiueoy'") {
            emit("ん")
            pending.deleteCharAt(0)
            return StepResult.PROGRESSED
        }
        return null
    }

    /** Unspellable: keep the letter as typed rather than swallowing it. */
    private fun spillFirst() {
        kana.append(pending[0])
        pending.deleteCharAt(0)
    }

    private fun emit(hiragana: String) {
        kana.append(if (katakana) toKatakana(hiragana) else hiragana)
    }

    override fun backspace(): ComposeResult? {
        when {
            pending.isNotEmpty() -> pending.setLength(pending.length - 1)
            kana.isNotEmpty() -> kana.setLength(kana.length - 1)
            else -> return null
        }
        return ComposeResult(composing = composing)
    }

    /** A trailing `n` or `nn` is ん once nothing more is coming. */
    override fun finish(): ComposeResult? {
        if (pending.toString() != "n" && pending.toString() != "nn") return null
        emit("ん")
        pending.setLength(0)
        val text = kana.toString()
        reset()
        return ComposeResult(commit = text)
    }

    override fun pick(candidate: String): ComposeResult {
        reset()
        return ComposeResult(commit = candidate)
    }

    override fun reset() {
        kana.setLength(0)
        pending.setLength(0)
        katakana = false
    }

    private companion object {
        val ROMAJI: Map<String, String> = buildMap {
            fun row(consonant: String, vararg kana: String) {
                "aiueo".forEachIndexed { i, vowel -> if (i < kana.size) put(consonant + vowel, kana[i]) }
            }
            row("", "あ", "い", "う", "え", "お")
            row("k", "か", "き", "く", "け", "こ")
            row("g", "が", "ぎ", "ぐ", "げ", "ご")
            row("s", "さ", "し", "す", "せ", "そ")
            row("z", "ざ", "じ", "ず", "ぜ", "ぞ")
            row("t", "た", "ち", "つ", "て", "と")
            row("d", "だ", "ぢ", "づ", "で", "ど")
            row("n", "な", "に", "ぬ", "ね", "の")
            row("h", "は", "ひ", "ふ", "へ", "ほ")
            row("b", "ば", "び", "ぶ", "べ", "ぼ")
            row("p", "ぱ", "ぴ", "ぷ", "ぺ", "ぽ")
            row("m", "ま", "み", "む", "め", "も")
            row("r", "ら", "り", "る", "れ", "ろ")
            row("y", "や", "", "ゆ", "", "よ")
            row("w", "わ", "ゐ", "", "ゑ", "を")
            row("v", "ゔぁ", "ゔぃ", "ゔ", "ゔぇ", "ゔぉ")
            row("f", "ふぁ", "ふぃ", "ふ", "ふぇ", "ふぉ")
            row("x", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ")
            row("l", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ")
            // Palatalised syllables: consonant cluster + small ya/yu/yo. `i` is skipped
            // because `shi`, `chi` and `ji` are the plain kana, not し + ぃ.
            val small = mapOf("a" to "ゃ", "u" to "ゅ", "o" to "ょ", "e" to "ぇ")
            for ((cluster, base) in mapOf(
                "ky" to "き", "gy" to "ぎ", "sy" to "し", "zy" to "じ", "ty" to "ち",
                "dy" to "ぢ", "ny" to "に", "hy" to "ひ", "by" to "び", "py" to "ぴ",
                "my" to "み", "ry" to "り", "sh" to "し", "ch" to "ち", "j" to "じ",
                "jy" to "じ", "cy" to "ち",
            )) {
                for ((vowel, tail) in small) put(cluster + vowel, base + tail)
            }
            // Irregular spellings last: they are the ones the rules above would get wrong.
            putAll(
                mapOf(
                    "shi" to "し", "chi" to "ち", "tsu" to "つ", "fu" to "ふ", "ji" to "じ",
                    "si" to "し", "ti" to "ち", "tu" to "つ", "hu" to "ふ", "zi" to "じ",
                    "tsa" to "つぁ", "tse" to "つぇ", "tso" to "つぉ",
                    "n'" to "ん", "-" to "ー",
                    "xtsu" to "っ", "ltsu" to "っ", "xtu" to "っ", "ltu" to "っ",
                    "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ", "xwa" to "ゎ",
                    "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ", "lwa" to "ゎ",
                    "thi" to "てぃ", "dhi" to "でぃ", "twu" to "とぅ", "dwu" to "どぅ",
                ),
            )
        }.filterValues { it.isNotEmpty() }

        /** Every proper prefix of a spelling, so the engine knows when to keep waiting. */
        val PREFIXES: Set<String> = buildSet {
            for (key in ROMAJI.keys) for (end in 1 until key.length) add(key.substring(0, end))
        }
    }
}

/**
 * The JIS kana layout (かな入力), where each key is a kana.
 *
 * Nothing needs assembling except the voicing marks: ゛and ゜ apply to the kana just typed, so
 * that one kana is held in the composing region until the next key proves it is finished.
 */
class KanaKeyComposer : Composer {

    private var pending = ""

    override val composing: String get() = pending

    override fun accept(text: String): ComposeResult? {
        if (text.length != 1) return null
        val c = text[0]
        if (c == DAKUTEN || c == HANDAKUTEN) {
            if (pending.isEmpty()) return null
            val marked = mark(pending.last(), c) ?: return ComposeResult(composing = pending)
            pending = marked.toString()
            return ComposeResult(composing = pending)
        }
        if (!isKana(c)) return null
        val settled = pending
        pending = c.toString()
        return ComposeResult(settled, pending)
    }

    override fun backspace(): ComposeResult? {
        if (pending.isEmpty()) return null
        pending = ""
        return ComposeResult()
    }

    override fun pick(candidate: String): ComposeResult {
        reset()
        return ComposeResult(commit = candidate)
    }

    override fun reset() {
        pending = ""
    }

    private fun mark(kana: Char, sign: Char): Char? {
        val table = if (sign == DAKUTEN) VOICED else SEMI_VOICED
        val i = table.first.indexOf(kana)
        return if (i < 0) null else table.second[i]
    }

    private fun isKana(c: Char): Boolean =
        c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c == 'ー'

    private companion object {
        const val DAKUTEN = '゛'
        const val HANDAKUTEN = '゜'

        val VOICED = "かきくけこさしすせそたちつてとはひふへほう" to
            "がぎぐげござじずぜぞだぢづでどばびぶべぼゔ"
        val SEMI_VOICED = "はひふへほ" to "ぱぴぷぺぽ"
    }
}
