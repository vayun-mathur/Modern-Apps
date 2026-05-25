"""
Generate German WordMaker crossword level files from a frequency-ordered word list.

Mirrors the English generate_levels.py approach:
  - Picks a "wheel word" whose letters form the chooser circle
  - All other placed words must use only letters from the wheel word
  - Words placed as an intersecting crossword on an 11x11 grid

Difficulty scales with level:
  Levels   1- 30:  wheel 3-4 letters, chooser ≤4, min 3 words
  Levels  31-100:  wheel 4-5 letters, chooser ≤5, min 4 words
  Levels 101-250:  wheel 5   letters, chooser ≤5, min 4 words
  Levels 251-450:  wheel 5-6 letters, chooser ≤6, min 5 words
  Levels 451-650:  wheel 6   letters, chooser ≤6, min 5 words
  Levels 651-800:  wheel 6-7 letters, chooser ≤7, min 6 words

Run from scripts/wordmaker/:
    python3 generate_levels_de.py [--start N] [--count N] [--words-csv FILE] [--fix-bad-words]
"""

import argparse
import os
import re
import random
from collections import Counter

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
VOWELS = set('aeiouäöü')


def difficulty_params(level):
    """Return (wheel_min, wheel_max, max_total, min_distinct, min_words)."""
    if level <= 30:
        return 3, 4, 4, 2, 3   # tiny: 3-4 letter wheel, 3 words
    elif level <= 100:
        return 4, 5, 5, 3, 4   # small: 4-5 letter wheel, 4 words
    elif level <= 250:
        return 5, 5, 5, 3, 4   # medium-small: 5 letter wheel, 4 words
    elif level <= 450:
        return 5, 6, 6, 4, 5   # medium: 5-6 letter wheel, 5 words
    elif level <= 650:
        return 6, 6, 6, 4, 5   # medium-large: 6 letter wheel, 5 words
    else:
        return 6, 7, 7, 5, 6   # large: 6-7 letter wheel, 6 words


def get_wheel_letters(words):
    """Merged chooser: max occurrence of each char across all placed words."""
    max_counts = {}
    for word in words:
        for ch, cnt in Counter(word).items():
            max_counts[ch] = max(max_counts.get(ch, 0), cnt)
    wheel = []
    for ch, cnt in max_counts.items():
        wheel.extend([ch] * cnt)
    return wheel


def can_place(grid, word, r, c, horizontal, rows, cols, isolated=False):
    if horizontal:
        if c + len(word) > cols:
            return False
        for i, ch in enumerate(word):
            cell = grid[r][c + i]
            if cell != ' ' and cell != ch:
                return False
            if cell == ' ' and not check_neighbors(grid, r, c + i, True, rows, cols):
                return False
        if c > 0 and grid[r][c - 1] != ' ':
            return False
        if c + len(word) < cols and grid[r][c + len(word)] != ' ':
            return False
    else:
        if r + len(word) > rows:
            return False
        for i, ch in enumerate(word):
            cell = grid[r + i][c]
            if cell != ' ' and cell != ch:
                return False
            if cell == ' ' and not check_neighbors(grid, r + i, c, False, rows, cols):
                return False
        if r > 0 and grid[r - 1][c] != ' ':
            return False
        if r + len(word) < rows and grid[r + len(word)][c] != ' ':
            return False

    if isolated:
        for i in range(len(word)):
            rr = r if horizontal else r + i
            cc = c + i if horizontal else c
            if has_any_neighbor(grid, rr, cc, rows, cols):
                return False
    return True


def check_neighbors(grid, r, c, horizontal, rows, cols):
    if horizontal:
        if r > 0 and grid[r - 1][c] != ' ':
            return False
        if r < rows - 1 and grid[r + 1][c] != ' ':
            return False
    else:
        if c > 0 and grid[r][c - 1] != ' ':
            return False
        if c < cols - 1 and grid[r][c + 1] != ' ':
            return False
    return True


def has_any_neighbor(grid, r, c, rows, cols):
    for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
        nr, nc = r + dr, c + dc
        if 0 <= nr < rows and 0 <= nc < cols and grid[nr][nc] != ' ':
            return True
    return False


def intersects(grid, word, r, c, horizontal):
    for i, ch in enumerate(word):
        rr = r if horizontal else r + i
        cc = c + i if horizontal else c
        if grid[rr][cc] == ch:
            return True
    return False


def do_place(grid, word, r, c, horizontal):
    for i, ch in enumerate(word):
        if horizontal:
            grid[r][c + i] = ch
        else:
            grid[r + i][c] = ch


def place_first_word(grid, word, rows, cols):
    horizontal = random.choice([True, False])
    r = random.randint(rows // 4, rows // 2)
    c = random.randint(cols // 4, cols // 2)
    if can_place(grid, word, r, c, horizontal, rows, cols):
        do_place(grid, word, r, c, horizontal)
        return True
    return False


def try_place_intersecting(grid, word, rows, cols):
    placements = []
    for r in range(rows):
        for c in range(cols):
            for horiz in (True, False):
                if (can_place(grid, word, r, c, horiz, rows, cols)
                        and intersects(grid, word, r, c, horiz)):
                    placements.append((r, c, horiz))
    if placements:
        r, c, horiz = random.choice(placements)
        do_place(grid, word, r, c, horiz)
        return True
    return False


def add_disconnected_word(grid, candidates, placed, rows, cols):
    available = [w for w in candidates if w not in placed]
    if not available:
        return
    min_r = min(r for r in range(rows) for c in range(cols) if grid[r][c] != ' ')
    max_r = max(r for r in range(rows) for c in range(cols) if grid[r][c] != ' ')

    random.shuffle(available)
    for word in available[:20]:
        placements = []
        for target_r in (min_r - 2, max_r + 2):
            if 0 <= target_r < rows:
                for c in range(cols - len(word) + 1):
                    if can_place(grid, word, target_r, c, True, rows, cols, isolated=True):
                        placements.append((target_r, c))
        if placements:
            r, c = random.choice(placements)
            do_place(grid, word, r, c, True)
            placed.append(word)
            return


def grid_to_string(grid):
    rows, cols = len(grid), len(grid[0])
    min_r = min((r for r in range(rows) for c in range(cols) if grid[r][c] != ' '), default=0)
    max_r = max((r for r in range(rows) for c in range(cols) if grid[r][c] != ' '), default=0)
    min_c = min((c for r in range(rows) for c in range(cols) if grid[r][c] != ' '), default=0)
    max_c = max((c for r in range(rows) for c in range(cols) if grid[r][c] != ' '), default=0)
    return '\n'.join(
        ''.join(grid[r][min_c:max_c + 1]) for r in range(min_r, max_r + 1)
    )


def generate_level(words, level, used_word_sets):
    wheel_min, wheel_max, max_total, min_distinct, min_words = difficulty_params(level)
    rows, cols = 11, 11

    base_offset = (level - 1) * 60
    wheel_candidates = [
        w for w in words[base_offset:base_offset + 10000]
        if wheel_min <= len(w) <= wheel_max
    ]
    if not wheel_candidates:
        wheel_candidates = [w for w in words if wheel_min <= len(w) <= wheel_max]
    if not wheel_candidates:
        return None

    for _ in range(300):
        wheel_word = random.choice(wheel_candidates)
        wheel_counts = Counter(wheel_word)

        candidates = [
            w for w in words
            if 3 <= len(w) <= wheel_max
            and all(Counter(w)[c] <= wheel_counts[c] for c in Counter(w))
        ]
        if len(candidates) < min_words:
            continue

        def difficulty_score(w):
            try:
                idx = words.index(w)
                if idx < base_offset // 2:
                    return 999999
                return abs(idx - base_offset)
            except ValueError:
                return 999999

        candidates.sort(key=difficulty_score)
        other_candidates = candidates[:50]

        for _ in range(200):
            grid = [[' '] * cols for _ in range(rows)]
            mandatory = [wheel_word]
            if random.random() < 0.5:
                long_cands = [w for w in candidates if len(w) >= wheel_min and w != wheel_word]
                if long_cands:
                    mandatory.append(random.choice(long_cands[:10]))

            random.shuffle(other_candidates)
            words_to_try = mandatory + [w for w in other_candidates if w not in mandatory]
            words_to_try = words_to_try[:15]
            words_sorted = sorted(words_to_try, key=len, reverse=True)

            placed = []
            if not place_first_word(grid, words_sorted[0], rows, cols):
                continue
            placed.append(words_sorted[0])

            for word in words_sorted[1:]:
                if try_place_intersecting(grid, word, rows, cols):
                    placed.append(word)
                if len(placed) >= 12:
                    break

            if len(placed) < min_words or not all(w in placed for w in mandatory):
                continue

            if level % 3 == 0:
                add_disconnected_word(grid, candidates, placed, rows, cols)

            wheel = get_wheel_letters(placed)
            distinct = len(set(wheel))
            total = len(wheel)

            if total <= max_total and distinct >= min_distinct and total >= min_distinct:
                word_set = frozenset(placed)
                if word_set in used_word_sets:
                    continue
                used_word_sets.add(word_set)
                return grid_to_string(grid), placed

    return None, None


def extract_words_from_grid(path):
    """Parse a level file and return the set of words (uppercase) it contains."""
    try:
        with open(path, encoding='utf-8') as f:
            lines = f.read().splitlines()
    except FileNotFoundError:
        return set()
    words = set()
    # Horizontal runs
    for line in lines:
        for m in re.finditer(r'[A-ZÄÖÜa-zäöü]{3,}', line):
            words.add(m.group().upper())
    # Vertical runs — transpose grid to reuse horizontal logic
    max_len = max((len(l) for l in lines), default=0)
    cols = [''.join(l[i] if i < len(l) else ' ' for l in lines) for i in range(max_len)]
    for col in cols:
        for m in re.finditer(r'[A-ZÄÖÜa-zäöü]{3,}', col):
            words.add(m.group().upper())
    return words


def load_bad_words_set():
    path = os.path.join(SCRIPT_DIR, 'bad-words.txt')
    try:
        with open(path, encoding='utf-8') as f:
            return {w.strip().lower() for w in f if w.strip() and not w.startswith('#')}
    except FileNotFoundError:
        return set()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--start', type=int, default=1)
    parser.add_argument('--count', type=int, default=800)
    parser.add_argument('--words-csv', metavar='FILE',
                        help='write a CSV of level,word for all placed words')
    parser.add_argument('--fix-bad-words', action='store_true',
                        help='only regenerate levels that contain a bad word')
    args = parser.parse_args()

    word_file = os.path.join(SCRIPT_DIR, 'Data', 'common_words_de.txt')
    with open(word_file, encoding='utf-8') as f:
        raw = [w.strip().upper() for w in f if w.strip()]
        words = []
        seen = set()
        for w in raw:
            if w not in seen:
                words.append(w)
                seen.add(w)
    print(f"Loaded {len(words)} words from {word_file}")

    output_dir = os.path.normpath(
        os.path.join(SCRIPT_DIR, '../../games/wordmaker/src/main/assets/levels/de')
    )
    os.makedirs(output_dir, exist_ok=True)

    # --fix-bad-words: determine which levels need regeneration
    bad_words = load_bad_words_set() if args.fix_bad_words else set()
    levels_to_regen = set()
    if args.fix_bad_words:
        all_levels = range(args.start, args.start + args.count)
        for level in all_levels:
            existing = extract_words_from_grid(os.path.join(output_dir, f'{level}.txt'))
            if any(w.lower() in bad_words for w in existing):
                levels_to_regen.add(level)
        print(f"Bad words found in {len(levels_to_regen)} levels — regenerating those only.")

    # Pre-populate used_word_sets from levels we're keeping unchanged
    used_word_sets = set()
    if args.fix_bad_words:
        for level in range(args.start, args.start + args.count):
            if level not in levels_to_regen:
                existing = extract_words_from_grid(os.path.join(output_dir, f'{level}.txt'))
                if existing:
                    used_word_sets.add(frozenset(existing))

    csv_rows = []
    failed = []
    for level in range(args.start, args.start + args.count):
        if args.fix_bad_words and level not in levels_to_regen:
            continue
        result, placed = generate_level(words, level, used_word_sets)
        if result:
            with open(os.path.join(output_dir, f'{level}.txt'), 'w', encoding='utf-8') as f:
                f.write(result)
            if args.words_csv:
                for w in placed:
                    csv_rows.append(f'{level},{w.lower()}')
            if level % 100 == 0:
                print(f"  Generated level {level}...")
        else:
            failed.append(level)
            print(f"  WARNING: could not generate level {level}")

    if args.words_csv:
        with open(args.words_csv, 'w', encoding='utf-8') as f:
            f.write('level,word\n')
            f.write('\n'.join(csv_rows))
        print(f"Words CSV written to {args.words_csv}")

    attempted = len(levels_to_regen) if args.fix_bad_words else args.count
    total = attempted - len(failed)
    print(f"\nDone. {total}/{attempted} levels generated in {output_dir}")
    if failed:
        print(f"Failed: {failed}")


if __name__ == '__main__':
    main()
