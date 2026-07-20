#!/usr/bin/env python3
"""Generate the compact `puzzles.dat` asset for the Chess app's Puzzles mode.

Source: the Lichess puzzle database CSV (`lichess_db_puzzle.csv.zst`, ~6M rows),
whose columns are:

    PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags

Shipping all ~6M puzzles is infeasible, so we keep only the highest-`Popularity`
puzzles spread evenly across rating bands, and store just the three fields the
app needs (FEN, Moves, Rating) in a tight custom binary blob.

Selection: 200-point rating bands over 400..2799 (12 bands). Within each band we
keep the top `ceil(TARGET / 12)` rows by Popularity, so difficulty is balanced.
All bands 400..2799 are densely populated in the source (each has 130k-880k
rows), so no redistribution is needed; any short band simply contributes fewer.

Binary format (`puzzles.dat`, little-endian) — kept in sync with the Kotlin
loader in `PuzzleRepository` / `Board.fromPuzzle`:

    Header:
      4 bytes  magic  "CPZ1"
      4 bytes  uint32 count (N puzzles)
    Per-puzzle record (variable length), written sorted by rating ascending:
      32 bytes  board: one nibble per square, square index s = row*8 + col
                (row 0 = rank 8, col 0 = file a). Byte b holds square 2b in its
                low nibble and 2b+1 in its high nibble. Nibble 0 = empty; else
                bits0-2 = type (1=K,2=Q,3=R,4=B,5=N,6=P), bit3 = color (0=w,1=b).
       1 byte   flags: bit0 = side-to-move (0=white,1=black);
                bits1-4 = castling rights KQkq (bit1=K,bit2=Q,bit3=k,bit4=q)
       1 byte   enPassantSquare (0..63, or 0xFF = none)
       2 bytes  uint16 rating
       1 byte   moveCount (M, full solution incl. the opponent's setup move)
      2*M bytes  each move uint16: bits0-5 fromSq, bits6-11 toSq,
                bits12-14 promo (0=none,1=Q,2=R,3=B,4=N)

Usage:
    python3 generate_puzzles.py [input.csv.zst] [output.bin]

Defaults: input ~/Documents/lichess_db_puzzle.csv.zst, output
Modern-Apps/games/chess/src/main/assets/puzzles.dat (both overridable via argv).
"""
from __future__ import annotations

import csv
import heapq
import math
import os
import struct
import subprocess
import sys

# Repo layout: scripts/chess/generate_puzzles.py -> repo root is two levels up.
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_INPUT = os.path.expanduser("~/Documents/lichess_db_puzzle.csv.zst")
DEFAULT_OUTPUT = os.path.join(
    REPO_ROOT, "games", "chess", "src", "main", "assets", "puzzles.dat"
)

MAGIC = b"CPZ1"
TARGET_TOTAL = 50000
BAND_MIN = 400
BAND_MAX = 2800  # exclusive upper edge; bands cover 400..2799
BAND_SIZE = 200
NUM_BANDS = (BAND_MAX - BAND_MIN) // BAND_SIZE  # 12
PER_BAND = math.ceil(TARGET_TOTAL / NUM_BANDS)  # 4167

# FEN piece letter -> (type, color). type: 1=K,2=Q,3=R,4=B,5=N,6=P; color 0=w,1=b.
_TYPE_OF = {"k": 1, "q": 2, "r": 3, "b": 4, "n": 5, "p": 6}
_PROMO_OF = {"q": 1, "r": 2, "b": 3, "n": 4}


def band_index(rating: int) -> int:
    """Band for a rating, or -1 if outside the kept range."""
    if rating < BAND_MIN or rating >= BAND_MAX:
        return -1
    return (rating - BAND_MIN) // BAND_SIZE


def parse_fen(fen: str):
    """FEN -> (board[64] nibbles, side_to_move, castling_bits, ep_square)."""
    placement, side, castling, ep = fen.split()[:4]

    board = [0] * 64
    row = 0
    col = 0
    for ch in placement:
        if ch == "/":
            row += 1
            col = 0
        elif ch.isdigit():
            col += int(ch)
        else:
            typ = _TYPE_OF[ch.lower()]
            color = 0 if ch.isupper() else 1
            board[row * 8 + col] = typ | (color << 3)
            col += 1

    stm = 0 if side == "w" else 1

    cbits = 0
    if "K" in castling:
        cbits |= 1
    if "Q" in castling:
        cbits |= 2
    if "k" in castling:
        cbits |= 4
    if "q" in castling:
        cbits |= 8

    if ep == "-":
        ep_sq = 0xFF
    else:
        f = ord(ep[0]) - ord("a")
        r = int(ep[1])
        ep_sq = (8 - r) * 8 + f

    return board, stm, cbits, ep_sq


def parse_move(uci: str) -> int:
    """UCI move (e.g. 'e2e4' or 'e7e8q') -> packed uint16."""
    ffile = ord(uci[0]) - ord("a")
    frank = int(uci[1])
    tfile = ord(uci[2]) - ord("a")
    trank = int(uci[3])
    from_sq = (8 - frank) * 8 + ffile
    to_sq = (8 - trank) * 8 + tfile
    promo = _PROMO_OF[uci[4]] if len(uci) >= 5 else 0
    return from_sq | (to_sq << 6) | (promo << 12)


def encode_record(fen: str, moves: str, rating: int) -> bytes:
    board, stm, cbits, ep_sq = parse_fen(fen)
    move_list = moves.split()

    out = bytearray(32)
    for b in range(32):
        out[b] = board[2 * b] | (board[2 * b + 1] << 4)

    flags = stm | (cbits << 1)
    out.append(flags)
    out.append(ep_sq)
    out += struct.pack("<H", rating)
    out.append(len(move_list))
    for uci in move_list:
        out += struct.pack("<H", parse_move(uci))
    return bytes(out)


def select_puzzles(input_path: str):
    """Stream the CSV, keeping the top-PER_BAND rows by popularity in each band.

    Returns a list of (rating, encoded_record_bytes) for all kept puzzles.
    Uses one bounded min-heap per band so memory stays O(NUM_BANDS * PER_BAND).
    """
    # heap entries are (popularity, seq, fen, moves, rating); seq is a unique
    # tiebreaker so heapq never has to compare the string payloads.
    heaps = [[] for _ in range(NUM_BANDS)]
    seq = 0
    total_rows = 0

    proc = subprocess.Popen(
        ["zstd", "-dc", input_path],
        stdout=subprocess.PIPE,
        text=True,
    )
    try:
        reader = csv.reader(proc.stdout)
        header = next(reader)  # discard header row
        # Column indices (guard against future column reordering).
        i_fen = header.index("FEN")
        i_moves = header.index("Moves")
        i_rating = header.index("Rating")
        i_pop = header.index("Popularity")

        for fields in reader:
            total_rows += 1
            try:
                rating = int(fields[i_rating])
            except (ValueError, IndexError):
                continue
            bi = band_index(rating)
            if bi < 0:
                continue
            try:
                pop = int(fields[i_pop])
            except ValueError:
                continue

            h = heaps[bi]
            entry = (pop, seq, fields[i_fen], fields[i_moves], rating)
            seq += 1
            if len(h) < PER_BAND:
                heapq.heappush(h, entry)
            elif pop > h[0][0]:
                heapq.heapreplace(h, entry)
    finally:
        if proc.stdout:
            proc.stdout.close()
        proc.wait()

    if proc.returncode not in (0, None):
        raise RuntimeError(f"zstd exited with code {proc.returncode}")

    kept = []
    band_counts = []
    for bi, h in enumerate(heaps):
        band_counts.append(len(h))
        for pop, _seq, fen, moves, rating in h:
            kept.append((rating, encode_record(fen, moves, rating)))

    return kept, band_counts, total_rows


def write_blob(output_path: str, kept):
    kept.sort(key=lambda x: x[0])  # ascending rating -> bands contiguous
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack("<I", len(kept)))
        for _rating, rec in kept:
            f.write(rec)


def self_check(output_path: str, expected_count: int):
    """Re-read the header and decode the first and last records as a sanity test."""
    with open(output_path, "rb") as f:
        data = f.read()
    assert data[:4] == MAGIC, "bad magic"
    (count,) = struct.unpack_from("<I", data, 4)
    assert count == expected_count, f"count mismatch: {count} != {expected_count}"

    def decode_at(off):
        # Returns (next_offset, rating, move_count).
        rating = struct.unpack_from("<H", data, off + 34)[0]
        mcount = data[off + 36]
        return off + 37 + 2 * mcount, rating, mcount

    off = 8
    next_off, first_rating, first_moves = decode_at(off)
    assert first_moves >= 1, "first record has no moves"
    # Walk to the last record to confirm the whole blob parses cleanly.
    last_rating = first_rating
    for _ in range(count):
        next_off, last_rating, mcount = decode_at(off)
        assert mcount >= 1, "record with no moves"
        off = next_off
    assert off == len(data), f"trailing bytes: parsed to {off} of {len(data)}"
    return first_rating, last_rating


def main():
    input_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_INPUT
    output_path = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUTPUT

    if not os.path.exists(input_path):
        print(f"Error: input not found: {input_path}", file=sys.stderr)
        sys.exit(1)

    print(f"Reading {input_path} ...")
    kept, band_counts, total_rows = select_puzzles(input_path)
    print(f"Scanned {total_rows:,} rows; kept {len(kept):,} puzzles.")
    print("Per-band counts (band lo -> kept):")
    for bi, c in enumerate(band_counts):
        lo = BAND_MIN + bi * BAND_SIZE
        print(f"  {lo:>4}-{lo + BAND_SIZE - 1:<4}: {c:>6,}")

    write_blob(output_path, kept)
    size = os.path.getsize(output_path)
    print(f"Wrote {output_path} ({size:,} bytes, {size / (1024 * 1024):.2f} MB)")

    first_rating, last_rating = self_check(output_path, len(kept))
    print(f"Self-check OK: first rating {first_rating}, last rating {last_rating}")


if __name__ == "__main__":
    main()
