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

## What does NOT belong

- Common German words that are also foreign words (`bar`, `boot`, `arm`, `arm`) — these are valid German
- Loanwords actively used in German (`baby`, `cool`, `boss`, `team`, `hotel`, `taxi`, `euro`, `pro`) — keep these
- German words that happen to be names (`weber`, `fischer`, `müller`) — valid game words
- Grammatical function words (`ist`, `und`, `der`) — valid, just won't have translations
