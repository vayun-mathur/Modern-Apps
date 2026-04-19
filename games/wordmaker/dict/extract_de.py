"""
Extract German dictionary from Deutscher-Thesaurus.oxt as a CSV.

The .oxt is a ZIP containing:
  th_de_DE_v2.idx  — word|byte_offset index
  th_de_DE_v2.dat  — thesaurus entries; each entry at its byte offset:
                     word|synset_count
                     (category)|syn1|syn2|...
                     ...

Output: ../src/main/assets/dictionary_de.csv
Format: word,1,category,"syn1; syn2; ..."  (one row per synset)

Run from the dict/ directory:
    python3 extract_de.py
"""

import zipfile
import re
import os

OXT_FILE = "Deutscher-Thesaurus.oxt"
IDX_FILE = "th_de_DE_v2.idx"
DAT_FILE = "th_de_DE_v2.dat"
OUT_FILE = os.path.join("..", "src", "main", "assets", "dictionary_de.csv")

# Only allow lowercase German alphabet (no ß — its uppercase is SS which changes word length).
# Require at least one vowel to exclude abbreviations like "sms", "btw", "mfs".
WORD_PATTERN = re.compile(r"^(?=[a-zäöü]*[aeiouäöü])[a-zäöü]{3,6}$")

print(f"Opening {OXT_FILE}...")
with zipfile.ZipFile(OXT_FILE) as z:
    idx_bytes = z.read(IDX_FILE)
    dat_bytes = z.read(DAT_FILE)

# Determine dat encoding (older German thesaurus files use ISO-8859-1)
try:
    dat_bytes.decode("utf-8")
    dat_enc = "utf-8"
except UnicodeDecodeError:
    dat_enc = "iso-8859-1"
print(f"Dat encoding: {dat_enc}")

# Parse idx: word_lower -> byte_offset
word_offsets = {}
idx_lines = idx_bytes.decode("utf-8").splitlines()
for line in idx_lines[1:]:  # first line is entry count
    parts = line.split("|")
    if len(parts) < 2:
        continue
    word = parts[0].strip()
    try:
        offset = abs(int(parts[1].strip()))
    except ValueError:
        continue
    word_lower = word.lower()
    if WORD_PATTERN.match(word_lower):
        word_offsets[word_lower] = offset

print(f"Found {len(word_offsets)} matching words in index")

# Parse dat entries for each word and build CSV rows
rows = []
for word, offset in word_offsets.items():
    chunk = dat_bytes[offset:offset + 8192]
    try:
        chunk_text = chunk.decode(dat_enc)
    except Exception:
        chunk_text = chunk.decode("iso-8859-1", errors="replace")

    lines = chunk_text.splitlines()
    if not lines:
        continue

    # First line of each entry: "original_word|synset_count"
    header_parts = lines[0].split("|")
    if len(header_parts) < 2:
        continue
    try:
        synset_count = int(header_parts[1])
    except ValueError:
        continue

    for i in range(1, synset_count + 1):
        if i >= len(lines):
            break
        synset_parts = lines[i].split("|")
        if len(synset_parts) < 2:
            continue

        category = synset_parts[0].strip().strip("()")
        synonyms = [s.strip() for s in synset_parts[1:] if s.strip() and s.strip().lower() != word]

        if not synonyms:
            continue

        definition = "; ".join(synonyms)
        # Strip double-quotes to avoid breaking CSV field quoting
        definition = definition.replace('"', "'")
        category_clean = category.replace(",", " ").replace('"', "'")
        rows.append(f'{word},1,{category_clean},"{definition}"')

os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
with open(OUT_FILE, "w", encoding="utf-8") as f:
    f.write("\n".join(rows))

unique_words = len({r.split(",", 1)[0] for r in rows})
print(f"Written {len(rows)} rows ({unique_words} unique words with definitions) to {OUT_FILE}")
print("You can now delete dictionary_de.txt from assets if it exists.")
