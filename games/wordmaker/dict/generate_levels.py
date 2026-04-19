"""
Generate WordMaker crossword level files from a word list.

Produces .txt files in the same grid format as the existing English levels.
Words are placed as intersecting crosswords; each level uses a deterministic
seed so the output is reproducible.

Run from the dict/ directory:
    python3 generate_levels.py [--dict PATH] [--out DIR] [--count N] [--start N]

Defaults:
    --dict  ../src/main/assets/dictionary_de.csv
    --out   ../src/main/assets/levels/de
    --count 200
    --start 1
"""

import argparse
import csv
import os
import random
from collections import Counter


# ---------------------------------------------------------------------------
# Difficulty tiers: (min_words, max_words, min_len, max_len, max_chooser)
# max_chooser = max total letters in chooser circle (counting repeats)
# Mirrors English levels: 3-4 chooser early, 5 mid, 6 late.
# ---------------------------------------------------------------------------
def difficulty_params(level: int) -> tuple[int, int, int, int, int]:
    if level <= 30:
        return 3, 4, 3, 4, 4
    elif level <= 150:
        return 4, 6, 3, 5, 5
    else:
        return 5, 8, 4, 6, 6


# ---------------------------------------------------------------------------
# Grid helpers
# ---------------------------------------------------------------------------
def can_place(word: str, row: int, col: int, direction: str, grid: dict) -> bool:
    """
    Check whether `word` can be placed at (row, col) going in `direction`
    ('H' horizontal, 'V' vertical) without violating crossword constraints:
      - no letter conflict at shared cells
      - no letter immediately before/after the word (would extend it)
      - no perpendicular-adjacent letters at new (non-intersection) cells
    """
    dr, dc = (0, 1) if direction == "H" else (1, 0)
    perp_dr, perp_dc = (1, 0) if direction == "H" else (0, 1)

    if (row - dr, col - dc) in grid:
        return False
    end_r = row + dr * (len(word) - 1)
    end_c = col + dc * (len(word) - 1)
    if (end_r + dr, end_c + dc) in grid:
        return False

    for i, ch in enumerate(word):
        r, c = row + dr * i, col + dc * i
        existing = grid.get((r, c))
        if existing is not None:
            if existing != ch:
                return False
        else:
            if (r - perp_dr, c - perp_dc) in grid or (r + perp_dr, c + perp_dc) in grid:
                return False

    return True


def place_word(word: str, row: int, col: int, direction: str, grid: dict) -> None:
    dr, dc = (0, 1) if direction == "H" else (1, 0)
    for i, ch in enumerate(word):
        grid[(row + dr * i, col + dc * i)] = ch


def grid_to_txt(grid: dict) -> str:
    if not grid:
        return ""
    min_r = min(r for r, c in grid)
    max_r = max(r for r, c in grid)
    min_c = min(c for r, c in grid)
    max_c = max(c for r, c in grid)

    lines = []
    for r in range(min_r, max_r + 1):
        row_chars = [grid.get((r, c), " ") for c in range(min_c, max_c + 1)]
        lines.append("".join(row_chars))

    max_len = max(len(l) for l in lines)
    return "\n".join(l.ljust(max_len) for l in lines)


def merge_chooser(chooser: dict, word: str) -> dict:
    """Return new chooser with max letter counts after merging word."""
    result = dict(chooser)
    for ch, cnt in Counter(word).items():
        result[ch] = max(result.get(ch, 0), cnt)
    return result


def extract_words_from_txt(txt: str) -> list[str]:
    """Extract all horizontal and vertical words (length ≥ 2) from a grid txt."""
    if not txt:
        return []
    grid = txt.splitlines()
    maxlen = max(len(l) for l in grid)
    grid = [l.ljust(maxlen).replace(" ", ".") for l in grid]
    words = []

    for row in grid:
        cur = ""
        for ch in row:
            if ch != ".":
                cur += ch
            elif len(cur) > 1:
                words.append(cur)
                cur = ""
            else:
                cur = ""
        if len(cur) > 1:
            words.append(cur)

    for c in range(maxlen):
        cur = ""
        for row in grid:
            ch = row[c]
            if ch != ".":
                cur += ch
            elif len(cur) > 1:
                words.append(cur)
                cur = ""
            else:
                cur = ""
        if len(cur) > 1:
            words.append(cur)

    return words


# ---------------------------------------------------------------------------
# Level generation
# ---------------------------------------------------------------------------
def try_generate(words: list[str], level: int, seed_offset: int) -> str | None:
    min_words, max_words, min_len, max_len, max_chooser = difficulty_params(level)

    rng = random.Random(level + seed_offset * 100_003)

    # words is pre-sorted by frequency (synset count) descending.
    # Filter by length and require at least one vowel (exclude abbreviations).
    VOWELS = frozenset("AEIOUÄÖÜ")
    candidates = [
        w.upper() for w in words
        if min_len <= len(w) <= max_len and any(ch in VOWELS for ch in w.upper())
    ]
    pool_size = 1000 if level <= 30 else 2000 if level <= 150 else 3000
    candidates = candidates[:min(pool_size, len(candidates))]
    rng.shuffle(candidates)

    if not candidates:
        return None

    target_words = rng.randint(min_words, max_words)

    # Prefer a short first word to leave more chooser budget for subsequent words
    short_candidates = [c for c in candidates if len(c) <= min_len + 1]
    first_word = short_candidates[0] if short_candidates else candidates[0]

    grid: dict[tuple[int, int], str] = {}
    placed: list[tuple[str, int, int, str]] = []
    chooser: dict[str, int] = dict(Counter(first_word))

    place_word(first_word, 0, 0, "H", grid)
    placed.append((first_word, 0, 0, "H"))

    remaining = target_words - 1
    all_candidates = [c for c in candidates if c != first_word]
    idx = 0

    while remaining > 0 and idx < len(all_candidates):
        word = all_candidates[idx]
        idx += 1

        # Reject words that would push the chooser over the budget
        new_chooser = merge_chooser(chooser, word)
        if sum(new_chooser.values()) > max_chooser:
            continue

        placed_this = False

        for pw, pw_row, pw_col, pw_dir in placed:
            if placed_this:
                break
            new_dir = "V" if pw_dir == "H" else "H"
            dr, dc = (0, 1) if pw_dir == "H" else (1, 0)

            for i, pw_ch in enumerate(pw):
                if placed_this:
                    break
                pr = pw_row + dr * i
                pc = pw_col + dc * i

                ndr, ndc = (0, 1) if new_dir == "H" else (1, 0)
                for j, ch in enumerate(word):
                    if ch != pw_ch:
                        continue
                    new_row = pr - ndr * j
                    new_col = pc - ndc * j
                    if can_place(word, new_row, new_col, new_dir, grid):
                        place_word(word, new_row, new_col, new_dir, grid)
                        placed.append((word, new_row, new_col, new_dir))
                        chooser = new_chooser
                        remaining -= 1
                        placed_this = True
                        break

    if len(placed) < min_words:
        return None

    return grid_to_txt(grid)


def generate_level(
    words: list[str], level: int, used_word_sets: set, max_retries: int = 200
) -> str | None:
    """Generate a level whose word set hasn't been used in a previous level."""
    for offset in range(max_retries):
        result = try_generate(words, level, offset)
        if result is not None:
            ws = frozenset(extract_words_from_txt(result))
            if ws not in used_word_sets:
                used_word_sets.add(ws)
                return result
    return None


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dict", default=os.path.join("..", "src", "main", "assets", "dictionary_de.csv"))
    parser.add_argument("--out", default=os.path.join("..", "src", "main", "assets", "levels", "de"))
    parser.add_argument("--count", type=int, default=200)
    parser.add_argument("--start", type=int, default=1)
    args = parser.parse_args()

    word_scores: dict[str, int] = {}
    word_set: set[str] = set()
    with open(args.dict, encoding="utf-8") as f:
        if args.dict.endswith(".csv"):
            for row in csv.reader(f):
                if row and row[0].strip():
                    w = row[0].strip().lower()
                    word_scores[w] = word_scores.get(w, 0) + 1
                    word_set.add(w)
        else:
            for line in f:
                w = line.strip()
                if w:
                    word_set.add(w)
                    word_scores[w] = 1

    # Require at least 2 synset entries — filters abbreviations that appear only once.
    # Sort by synset count (frequency proxy) descending so pool slicing picks common words.
    words = sorted(
        [w for w in word_set if word_scores.get(w, 0) >= 2],
        key=lambda w: -word_scores.get(w, 0),
    )
    print(f"Loaded {len(words)} words (score≥2) from {args.dict}")

    os.makedirs(args.out, exist_ok=True)

    used_word_sets: set[frozenset] = set()
    failed = []
    for level in range(args.start, args.start + args.count):
        result = generate_level(words, level, used_word_sets)
        if result:
            path = os.path.join(args.out, f"{level}.txt")
            with open(path, "w", encoding="utf-8") as f:
                f.write(result)
            if level % 25 == 0:
                print(f"  Generated level {level}")
        else:
            failed.append(level)
            print(f"  WARNING: could not generate level {level}")

    total = args.count - len(failed)
    print(f"\nDone. {total}/{args.count} levels generated in {args.out}")
    if failed:
        print(f"Failed levels: {failed}")


if __name__ == "__main__":
    main()
