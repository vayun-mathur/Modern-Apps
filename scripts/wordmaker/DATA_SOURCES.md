# WordMaker German Generator — Data Sources

All files go in `scripts/wordmaker/Data/` (gitignored). Re-download before running the extract/generate scripts.

`bad-words.txt` lives in `scripts/wordmaker/` (committed to git) — edit it to manually block words that slip through the automatic filters.

## OpenSubtitles Frequency Lists

German word frequency data extracted from movie/TV subtitles.

- **Download:** https://github.com/hermitdave/FrequencyWords/tree/master/content/2018/de — extract to get the frequency file
- `de_50k.txt` — top 50k words (used by `extract_de.py` for level generation pool)
- `de_full.txt` — full corpus ~1.15M words (used for runtime word validation)
- **Format:** `word frequency` per line, most frequent first

## German Hunspell Dictionary

Used to filter non-German words and proper nouns from the frequency list.

- **Source:** https://github.com/wooorm/dictionaries (same author as the npm dictionaries package)
- Place `index.dic` and `index.aff` into `Data/de-hunspell/`

## German First Names (philipperemy/name-dataset)

Used to filter first names from the word pool. The pickle contains all countries — we filter by rank in DE, US, GB, CA, AU, IE with a threshold of top 1000.

- **Source:** https://github.com/philipperemy/name-dataset
- Download `names_dataset/v3/first_names.pkl.gz` from the repo
- Rename to `DE_first_names.pkl.gz` and place in `Data/`

## GeoNames German Place Names

Used to filter German city, town, and village names.

- **Download:** https://download.geonames.org/export/dump/DE.zip
- Save as `Data/DE_geonames.zip` (do not extract — the script reads it directly)
- **Format:** tab-separated, column 1 = place name

## freedict deu-eng Dictionary

German→English translation data for offline word definitions bundled with the app.

- **Source:** https://freedict.org/downloads/ → search for "German - English"
- Download the `.src.tar.xz` release and extract — you need `deu-eng.tei`
- Place the extracted `deu-eng/` folder (containing `deu-eng.tei`) into `Data/`

## Regenerating Assets

Run in order from `scripts/wordmaker/`:

```bash
python3 extract_de.py          # generates Data/common_words_de.txt + assets/wordlist_de.txt
python3 extract_freedict.py    # generates assets/translations_de.csv
python3 generate_levels_de.py  # generates assets/levels/de/1.txt … 800.txt
```
