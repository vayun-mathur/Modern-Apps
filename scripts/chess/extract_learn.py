#!/usr/bin/env python3
"""Extract the Lichess "Learn" (chess basics) lessons into a compact JSON asset.

Source: the lila repository (https://github.com/lichess-org/lila), AGPL-3.0.
The interactive lesson definitions live in `ui/learn/src/stage/*.ts` and the
English instruction strings live in `translation/source/{learn,site}.xml`.

This script reads a local lila checkout and emits
`games/chess/src/main/assets/learn.json`, consumed by `LearnRepository` /
`LearnViewModel` in the app. Only functional data is copied: the chess FENs
(positions), target ("apple") squares, scripted opponent scenarios, hint shapes,
per-level flags, and the project's own English UI strings.

ATTRIBUTION / LICENSE: lila is AGPL-3.0. The generated `learn.json` is derived
from it; the app that bundles it must comply with the AGPL (attribution + making
source available). See games/chess/src/main/assets/LEARN_LICENSE.txt.

Usage:
    python3 extract_learn.py [path-to-lila-checkout] [output.json]

Defaults: checkout /tmp/lila-learn, output
Modern-Apps/games/chess/src/main/assets/learn.json.
"""
from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_CHECKOUT = "/tmp/lila-learn"
DEFAULT_OUTPUT = os.path.join(
    REPO_ROOT, "games", "chess", "src", "main", "assets", "learn.json"
)

# Category order and membership, mirroring ui/learn/src/stage/list.ts.
CATEGORIES = [
    ("chessPieces", ["rook", "bishop", "queen", "king", "knight", "pawn"]),
    ("fundamentals", ["capture", "protection", "combat", "check1", "outOfCheck", "checkmate1"]),
    ("intermediate", ["setup", "castling", "enpassant", "stalemate"]),
    ("advanced", ["value", "check2"]),
]

# Per-stage goal type (the success mechanic), derived from each stage's success/
# failure predicate in ui/learn/src/stage/*.ts.
STAGE_GOAL = {
    "rook": "apples", "bishop": "apples", "queen": "apples", "king": "apples",
    "knight": "apples", "pawn": "apples", "setup": "apples",
    "capture": "captureAll", "combat": "captureAll",
    "protection": "protection",
    "check1": "check",
    "outOfCheck": "escapeCheck",
    "checkmate1": "mate",
    "castling": "castle",
    "enpassant": "scenario", "stalemate": "scenario", "value": "scenario",
    "check2": "checkIn",
}


def load_xml(path):
    strings = {}
    if not os.path.exists(path):
        return strings
    root = ET.parse(path).getroot()
    for s in root.findall("string"):
        name = s.get("name")
        if name is not None:
            strings[name] = s.text or ""
    return strings


def balanced(text, open_ch, close_ch, start):
    """Return (inner_text, end_index_after_close) for the balanced block whose
    opening bracket is the first `open_ch` at or after `start`."""
    i = text.index(open_ch, start)
    depth = 0
    j = i
    while j < len(text):
        c = text[j]
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return text[i + 1:j], j + 1
        j += 1
    raise ValueError("unbalanced")


def split_objects(arr_text):
    """Split a `[ {..}, {..} ]` inner body into top-level `{..}` object texts."""
    objs = []
    k = 0
    while True:
        try:
            start = arr_text.index("{", k)
        except ValueError:
            break
        inner, end = balanced(arr_text, "{", "}", start)
        objs.append(inner)
        k = end
    return objs


FLAG_NAMES = [
    "pointsForCapture", "showPieceValues", "autoCastle", "emptyApples",
    "offerIllegalMove", "nextButton", "explainPromotion", "showFailureFollowUp",
]


def parse_flags(text):
    d = {}
    for f in FLAG_NAMES:
        if re.search(rf"\b{f}:\s*true", text):
            d[f] = True
    m = re.search(r"\bnbMoves:\s*(\d+)", text)
    if m:
        d["nbMoves"] = int(m.group(1))
    m = re.search(r"\bcaptures:\s*(\d+)", text)
    if m:
        d["captures"] = int(m.group(1))
    m = re.search(r"\bdetectCapture:\s*(false|true|'unprotected')", text)
    if m:
        v = m.group(1)
        d["detectCapture"] = {"false": "none", "true": "all", "'unprotected'": "unprotected"}[v]
    return d


def parse_scenario(obj_text):
    m = re.search(r"\bscenario:\s*", obj_text)
    if not m:
        return None
    inner, _ = balanced(obj_text, "[", "]", m.start())
    # Drop nested shape arrays so their arrow()/circle() UCIs aren't mistaken for moves.
    inner = re.sub(r"shapes:\s*\[[^\]]*\]", "", inner)
    return re.findall(r"'([a-h][1-8][a-h][1-8][qrbn]?)'", inner)


def parse_shapes(obj_text):
    # Remove any scenario block first so only the level's own hint shapes remain.
    txt = obj_text
    m = re.search(r"\bscenario:\s*", txt)
    if m:
        _, end = balanced(txt, "[", "]", m.start())
        txt = txt[:m.start()] + txt[end:]
    ms = re.search(r"\bshapes:\s*", txt)
    if not ms:
        return None
    inner, _ = balanced(txt, "[", "]", ms.start())
    shapes = []
    for a in re.finditer(r"arrow\('([a-h][1-8])([a-h][1-8])'(?:\s*,\s*'(\w+)')?\)", inner):
        shapes.append({"orig": a.group(1), "dest": a.group(2), "brush": a.group(3) or "green"})
    for c in re.finditer(r"circle\('([a-h][1-8])'(?:\s*,\s*'(\w+)')?\)", inner):
        shapes.append({"orig": c.group(1), "brush": c.group(2) or "green"})
    return shapes or None


def fen_color(fen):
    parts = fen.split()
    return "black" if len(parts) > 1 and parts[1] == "b" else "white"


def resolve(key_ns, learn, site):
    ns, name = key_ns
    src = site if ns == "site" else learn
    return src.get(name, name)


def parse_stage(path, key, learn, site):
    text = open(path, encoding="utf-8").read()

    def meta(field):
        m = re.search(rf"{field}:\s*i18n\.(\w+)\.(\w+)", text)
        return resolve((m.group(1), m.group(2)), learn, site) if m else ""

    stage = {
        "key": key,
        "title": meta("title"),
        "subtitle": meta("subtitle"),
        "intro": meta("intro"),
        "complete": meta("complete"),
        "levels": [],
    }

    # Stage-wide defaults from a `const common = {...}` block and the `.map(...)` tail.
    defaults_text = ""
    cm = re.search(r"const common\s*=\s*(?:\(\)\s*=>\s*\()?\{([^{}]*)\}", text)
    if cm:
        defaults_text += cm.group(1)
    mm = re.search(r"\.map\(\s*(?:\([^)]*\)\s*=>\s*)?toLevel\(\{([^{}]*)\}", text)
    if mm:
        defaults_text += " " + mm.group(1)
    defaults = parse_flags(defaults_text)

    lm = re.search(r"levels:\s*", text)
    arr, _ = balanced(text, "[", "]", lm.start())
    base_goal = STAGE_GOAL[key]

    for obj in split_objects(arr):
        fen = re.search(r"fen:\s*'([^']+)'", obj).group(1)
        if len(fen.split()) == 4:
            fen += " 0 1"
        gm = re.search(r"goal:\s*i18n\.(\w+)\.(\w+)", obj)
        goal_text = resolve((gm.group(1), gm.group(2)), learn, site) if gm else ""

        level = dict(defaults)
        level.update(parse_flags(obj))

        am = re.search(r"apples:\s*'([^']*)'", obj)
        apples = am.group(1).strip() if am else ""

        cmm = re.search(r"color:\s*'(\w+)'", obj)
        color = cmm.group(1) if cmm else fen_color(fen)

        goal_type = base_goal
        if goal_type == "apples" and not apples:
            goal_type = "info"

        out = {
            "goal": goal_text,
            "fen": fen,
            "color": color,
            "goalType": goal_type,
            "nbMoves": level.get("nbMoves", 1),
        }
        if apples:
            out["apples"] = apples
        if "captures" in level:
            out["captures"] = level["captures"]

        # detectCapture: explicit, else the lila default (apples => none, else unprotected).
        dc = level.get("detectCapture", "none" if apples else "unprotected")
        if dc != "none":
            out["detectCapture"] = dc

        for f in ("emptyApples", "offerIllegalMove", "showPieceValues",
                  "pointsForCapture", "explainPromotion", "nextButton"):
            if level.get(f):
                out[f] = True

        if goal_type == "captureAll":
            out["captureColor"] = "black"
        if goal_type == "checkIn":
            out["n"] = out["nbMoves"]

        if base_goal == "castle":
            if re.search(r"success:\s*castledQueenSide", obj):
                out["castleSide"] = "queen"
            elif re.search(r"success:\s*castledKingSide", obj):
                out["castleSide"] = "king"

        scen = parse_scenario(obj)
        if scen:
            out["scenario"] = scen
        shapes = parse_shapes(obj)
        if shapes:
            out["shapes"] = shapes

        fp = re.search(r"failure:\s*whitePawnOnAnyOf\('([^']*)'\)", obj)
        if fp:
            out["failIfWhitePawnOn"] = fp.group(1).split()
        op = re.search(r"failure:\s*noPieceOn\('([^']*)'\)", obj)
        if op:
            out["failIfPieceOffPath"] = op.group(1).split()

        stage["levels"].append(out)

    return stage


def main():
    checkout = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_CHECKOUT
    output = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUTPUT

    stage_dir = os.path.join(checkout, "ui", "learn", "src", "stage")
    if not os.path.isdir(stage_dir):
        print(f"Error: lila stage dir not found: {stage_dir}", file=sys.stderr)
        sys.exit(1)

    learn = load_xml(os.path.join(checkout, "translation", "source", "learn.xml"))
    site = load_xml(os.path.join(checkout, "translation", "source", "site.xml"))

    categories = []
    total_levels = 0
    stage_id = 1
    for cat_key, stage_keys in CATEGORIES:
        stages = []
        for sk in stage_keys:
            stage = parse_stage(os.path.join(stage_dir, f"{sk}.ts"), sk, learn, site)
            stage = {"id": stage_id, **stage}
            stage_id += 1
            stages.append(stage)
            total_levels += len(stage["levels"])
        categories.append({"key": cat_key, "name": learn.get(cat_key, cat_key), "stages": stages})

    data = {"categories": categories}
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)

    n_stages = sum(len(c["stages"]) for c in categories)
    print(f"Wrote {output}")
    print(f"  {len(categories)} categories, {n_stages} stages, {total_levels} levels")
    for c in categories:
        print(f"  - {c['name']}: " + ", ".join(f"{s['key']}({len(s['levels'])})" for s in c["stages"]))


if __name__ == "__main__":
    main()
