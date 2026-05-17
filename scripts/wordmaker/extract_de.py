"""
Extract German word lists from OpenSubtitles frequency data.

Input files (scripts/wordmaker/):
  de_50k.txt              — top 50k German words, format: "word frequency" per line
  de_full.txt             — full corpus (~1.15M words), same format
  de-hunspell/index.dic   — hunspell German dictionary (igerman98) for proper noun filtering

Outputs:
  scripts/wordmaker/common_words_de.txt          — level generation pool (3-8 letters)
  assets/dictionary_de.txt                       — runtime validation (3+ letters)

Run from scripts/wordmaker/:
    python3 extract_de.py
"""

import os
import re

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
VOWELS = set('aeiouäöü')
VALID = re.compile(r'^[a-zäöü]+$')


def load_bad_words():
    try:
        with open(os.path.join(SCRIPT_DIR, 'bad-words.txt'), encoding='utf-8') as f:
            return set(w.strip().lower() for w in f if w.strip())
    except FileNotFoundError:
        return set()


def load_hunspell_stems(dic_path):
    """
    Parse hunspell .dic file and return set of lowercase word stems.
    Entries starting with an uppercase letter that have no lowercase equivalent
    are proper nouns — we keep both cases lowercased so common nouns (Auto→auto)
    pass the filter, but cross-check against the frequency list (which is already
    lowercase) means only words whose lowercased stem exists here will pass.
    """
    stems = set()
    try:
        for enc in ('utf-8', 'iso-8859-1'):
            try:
                with open(dic_path, encoding=enc) as f:
                    for line in f:
                        line = line.strip()
                        if not line or not line[0].isalpha():
                            continue
                        word = line.split('/')[0].split('\t')[0]
                        stems.add(word.lower())
                break
            except UnicodeDecodeError:
                continue
    except FileNotFoundError:
        print(f"WARNING: hunspell dic not found at {dic_path}, skipping filter")
    return stems


def parse_freq_list(path, min_len, max_len, bad_words, hunspell_stems=None):
    """Read frequency list and return words in frequency order (most common first)."""
    words = []
    seen = set()
    with open(path, encoding='utf-8') as f:
        for line in f:
            parts = line.strip().split(' ')
            if len(parts) < 2:
                continue
            word = parts[0].lower()
            if (word not in seen
                    and min_len <= len(word) <= max_len
                    and VALID.match(word)
                    and any(c in VOWELS for c in word)
                    and word not in bad_words
                    and (hunspell_stems is None or word in hunspell_stems)):
                words.append(word)
                seen.add(word)
    return words


bad = load_bad_words()
print(f"Loaded {len(bad)} bad words")

dic_path = os.path.join(SCRIPT_DIR, 'de-hunspell', 'index.dic')
hunspell_stems = load_hunspell_stems(dic_path)
print(f"Loaded {len(hunspell_stems)} hunspell stems")

# common_words_de.txt — for level generation (3-8 letters, top 50k source)
gen_words = parse_freq_list(os.path.join(SCRIPT_DIR, 'de_50k.txt'), 3, 8, bad, hunspell_stems)
out_gen = os.path.join(SCRIPT_DIR, 'common_words_de.txt')
with open(out_gen, 'w', encoding='utf-8') as f:
    f.write('\n'.join(gen_words))
print(f"common_words_de.txt: {len(gen_words)} words")

# dictionary_de.txt — for runtime bonus word validation (3+ letters, full corpus)
# Use hunspell filter here too so bonus words are valid German
dict_words = parse_freq_list(os.path.join(SCRIPT_DIR, 'de_full.txt'), 3, 99, bad, hunspell_stems)
out_dict = os.path.join(SCRIPT_DIR, '../../games/wordmaker/src/main/assets/dictionary_de.txt')
out_dict = os.path.normpath(out_dict)
os.makedirs(os.path.dirname(out_dict), exist_ok=True)
with open(out_dict, 'w', encoding='utf-8') as f:
    f.write('\n'.join(dict_words))
print(f"dictionary_de.txt: {len(dict_words)} words → {out_dict}")
