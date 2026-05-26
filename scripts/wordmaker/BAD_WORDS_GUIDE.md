# Bad Words Guide

Add entries to `bad-words.txt` (one word per line, lowercase) when a word passes automatic filters but shouldn't appear in German crossword levels.

## Automatic filters already applied

These are handled by the pipeline — do NOT re-add them manually:
- Non-German words (hunspell filter)
- First names ranked in top 1000 in DE, US, GB, CA, AU, IE (philipperemy dataset)
- Cities with population >1000 worldwide (GeoNames cities1000)

## What belongs in bad-words.txt

### Org acronyms and abbreviations
Words that are all-lowercase but originate from an acronym. The frequency corpus treats them as words because they appear constantly in subtitles.

Examples: `raf`, `fbi`, `cia`, `nsa`, `who`, `hiv`, `aeg`, `sos`, `fbi`

Rule: if the word is only ever written in uppercase in real German text → add it.

### International city names missed by GeoNames
Cities1000 covers most cases, but misses:
- Historical city names (`konstantinopel`, `byzanz`)
- German-specific spellings of foreign cities where the German name differs from the GeoNames entry (`mailand` for Milan, `brüssel` for Brussels)

Rule: if a word is primarily known as a city/place name and has no common German meaning → add it.

### Mythological and historical proper names
Greek/Roman gods, Norse gods, historical figures whose names pass as lowercase words.

Examples: `hera`, `zeus`, `eros`, `thor`, `odin`, `mars`, `nero`

Note: some of these overlap with German words (`mars` = March in some contexts) — use judgment.

### English words from the subtitle corpus
OpenSubtitles German data contains English dialogue. Hunspell catches most but misses words that also exist as German proper nouns.

Examples: `sun`, `new`, `who`

Rule: if the word has no German meaning and is only present due to English dialogue in German-dubbed content → add it.

### English honorifics and titles
`sir`, `lord`, `duke`, `earl` — not German words, appear in period drama subtitles.

## Workflow: adding bad words and patching levels

1. Add offending words to `bad-words.txt` (one per line, lowercase, under the appropriate category comment)
2. From `scripts/wordmaker/`, run:
   ```bash
   python3 generate_levels_de.py --fix-bad-words
   ```
   This scans all 800 level files, identifies which contain a newly blocked word, and regenerates only those levels. Untouched levels are not modified.
3. Spot-check a few of the regenerated levels in `../../games/wordmaker/src/main/assets/levels/de/`
4. Repeat steps 1–3 until no bad words remain (usually converges in 1–2 passes)

## What does NOT belong

- Common German words that are also foreign words (`bar`, `boot`, `arm`, `arm`) — these are valid German
- Loanwords actively used in German (`baby`, `cool`, `boss`, `team`, `hotel`, `taxi`, `euro`, `pro`) — keep these
- German words that happen to be names (`weber`, `fischer`, `müller`) — valid game words
- Grammatical function words (`ist`, `und`, `der`) — valid, just won't have translations
