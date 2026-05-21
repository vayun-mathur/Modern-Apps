"""
Extract German→English translations from freedict deu-eng TEI dictionary.

Input:  scripts/wordmaker/deu-eng/deu-eng.tei
Output: assets/translations_de.csv

Format: word,1,pos,"translation1; translation2; ..."

Run from scripts/wordmaker/:
    python3 extract_freedict.py
"""

import os
import re
import xml.etree.ElementTree as ET

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
NS = "http://www.tei-c.org/ns/1.0"
VALID = re.compile(r'^[a-zäöü]+$')
VOWELS = set('aeiouäöü')


def t(name):
    return f"{{{NS}}}{name}"


def load_bad_words():
    try:
        with open(os.path.join(SCRIPT_DIR, 'bad-words.txt'), encoding='utf-8') as f:
            return set(w.strip().lower() for w in f if w.strip())
    except FileNotFoundError:
        return set()


bad_words = load_bad_words()
print(f"Loaded {len(bad_words)} bad words")

tei_path = os.path.join(SCRIPT_DIR, 'deu-eng', 'deu-eng.tei')
print(f"Parsing {tei_path}...")
tree = ET.parse(tei_path)
root = tree.getroot()

rows = []
skipped_filter = 0
skipped_no_trans = 0

for entry in root.iter(t('entry')):
    orth = entry.find(f'.//{t("orth")}')
    if orth is None or not orth.text:
        continue
    word = orth.text.strip().lower()

    if not (3 <= len(word) <= 8):
        skipped_filter += 1
        continue
    if not VALID.match(word):
        skipped_filter += 1
        continue
    if not any(c in VOWELS for c in word):
        skipped_filter += 1
        continue
    if word in bad_words:
        skipped_filter += 1
        continue

    pos_elem = entry.find(f'.//{t("pos")}')
    pos = pos_elem.text.strip() if pos_elem is not None and pos_elem.text else ""

    translations = []
    for cit in entry.iter(t('cit')):
        if cit.get('type') != 'trans':
            continue
        quote = cit.find(t('quote'))
        if quote is not None and quote.text:
            tx = quote.text.strip()
            if tx and tx not in translations:
                translations.append(tx)

    if not translations:
        skipped_no_trans += 1
        continue

    definition = "; ".join(translations).replace('"', "'")
    pos_clean = pos.replace(',', ' ').replace('"', "'")
    rows.append(f'{word},1,{pos_clean},"{definition}"')

out = os.path.normpath(
    os.path.join(SCRIPT_DIR, '../../games/wordmaker/src/main/assets/translations_de.csv')
)
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, 'w', encoding='utf-8') as f:
    f.write('\n'.join(rows))

unique_words = len({r.split(',', 1)[0] for r in rows})
print(f"Written {len(rows)} rows ({unique_words} unique words) → {out}")
print(f"Skipped: {skipped_filter} (filter), {skipped_no_trans} (no translation)")
