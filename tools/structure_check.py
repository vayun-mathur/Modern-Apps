#!/usr/bin/env python3
"""Cheap structure ratchets for Modern-Apps (no build, stdlib only).

Three checks, wired into ./check (see docs/structure/README.md):

  wear       Token-stream identity of the duplicated Wear protocol pairs
             listed in docs/structure/wear-protocol-pairs.tsv. Each file is
             normalized (package/import lines dropped, comments stripped
             with a string-aware scanner) and the resulting token streams
             must be identical per pair.
  generated  Every Kotlin/Rust file carrying a GENERATED/@generated header
             (definition in docs/structure/README.md) must be listed in
             docs/structure/generated-sources.tsv with a generator command,
             and every listed file must still exist and carry the header.
             Presence only: nothing is regenerated.
  exceptions Every escape-hatch marker occurrence in Kotlin sources must
             match docs/structure/exceptions.tsv exactly (type, file,
             reason). Regenerate with --regen after an intentional change
             and review the TSV diff; a new marker without a reason edit
             fails here (and malformed markers fail lint anyway).

Usage:
  python3 tools/structure_check.py [wear|generated|exceptions|all]
  python3 tools/structure_check.py exceptions --regen

Exit code 0 = clean, 1 = violations (each violation is named).
Scope exclusions follow root AGENTS.md "Never scan": build/, target/,
third_party/, analysis/, personal/, .git/ etc. are never walked, and
lint-rules/ + tools/ + docs/ are excluded from the marker/generated scans
because they legitimately quote marker/header strings as data.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs", "structure")
WEAR_TSV = os.path.join(DOCS, "wear-protocol-pairs.tsv")
GENERATED_TSV = os.path.join(DOCS, "generated-sources.tsv")
EXCEPTIONS_TSV = os.path.join(DOCS, "exceptions.tsv")

SKIP_DIRS = {
    ".git", ".gradle", ".kotlin", "build", "target", "analysis",
    "third_party", "personal", "release", "captures", ".idea", "__pycache__",
}
# Dirs whose files quote markers/headers as data, not as uses.
DATA_DIRS = ("lint-rules/", "tools/", "docs/")


def rel(path):
    return os.path.relpath(path, ROOT).replace(os.sep, "/")


def walk_files(exts):
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in sorted(filenames):
            if name.endswith(exts):
                yield os.path.join(dirpath, name)


def is_data_path(rpath):
    return rpath.startswith(DATA_DIRS)


# ---------------------------------------------------------------- wear ----
def strip_comments(text):
    """Remove // and /* */ comments, respecting Kotlin strings/chars."""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(text[i:j]); i = j
        elif text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1]); i = j + 1
        elif text[i] == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1]); i = j + 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth += 1; j += 2
                elif text.startswith("*/", j):
                    depth -= 1; j += 2
                else:
                    j += 1
            out.append(" "); i = j
        else:
            out.append(text[i]); i += 1
    return "".join(out)


TOKEN_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|\d[\w.]*|[^\w\s]")


def normalized_tokens(path):
    text = open(path, encoding="utf-8", errors="ignore").read()
    text = strip_comments(text)
    lines = [
        ln for ln in text.splitlines()
        if not re.match(r"\s*(package|import)\s", ln)
    ]
    return TOKEN_RE.findall("\n".join(lines))


def check_wear():
    violations = []
    pairs = []
    for ln, line in enumerate(open(WEAR_TSV, encoding="utf-8"), 1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.rstrip("\n").split("\t")
        if len(parts) != 3:
            violations.append(f"wear: {WEAR_TSV}:{ln}: expected 3 tab-separated fields")
            continue
        pairs.append(parts)
    for pair_id, phone, wear in pairs:
        pp, wp = os.path.join(ROOT, phone), os.path.join(ROOT, wear)
        if not os.path.isfile(pp):
            violations.append(f"wear[{pair_id}]: missing phone file {phone}")
            continue
        if not os.path.isfile(wp):
            violations.append(f"wear[{pair_id}]: missing wear file {wear}")
            continue
        a, b = normalized_tokens(pp), normalized_tokens(wp)
        if a != b:
            idx = next((k for k in range(min(len(a), len(b))) if a[k] != b[k]),
                       min(len(a), len(b)))
            lo = max(0, idx - 6)
            violations.append(
                f"wear[{pair_id}]: DRIFT {phone} <-> {wear} "
                f"(tokens phone={len(a)} wear={len(b)}, first diff at token {idx}: "
                f"phone ...{' '.join(a[lo:idx + 6])} | wear ...{' '.join(b[lo:idx + 6])})")
    print(f"wear: {len(pairs)} pairs checked")
    return violations


# ------------------------------------------------------------- generated ----
GENERATED_RE = re.compile(
    r"BEGIN GENERATED|@generated\b"
    r"|^\s*(?://[!/]?|/\*+|\*)?\s*GENERATED\b"
    r"|Code generated[^\n]*DO NOT EDIT|// Generated (constants|constant tables)"
    r"|Do not hand-edit")
HEADER_LINES = 40


def has_generated_header(path):
    try:
        with open(path, encoding="utf-8", errors="ignore") as fh:
            for i, line in enumerate(fh):
                if i >= HEADER_LINES:
                    break
                if GENERATED_RE.search(line):
                    return line.strip()
    except OSError:
        return None
    return None


def check_generated():
    violations = []
    listed = {}
    for ln, line in enumerate(open(GENERATED_TSV, encoding="utf-8"), 1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.rstrip("\n").split("\t")
        if len(parts) != 2 or not parts[1].strip():
            violations.append(
                f"generated: {GENERATED_TSV}:{ln}: expected '<path>\\t<generator command>'")
            continue
        listed[parts[0]] = parts[1]
    found = {}
    for path in walk_files((".kt", ".rs")):
        rpath = rel(path)
        if is_data_path(rpath):
            continue
        marker = has_generated_header(path)
        if marker:
            found[rpath] = marker
    for rpath in sorted(found):
        if rpath not in listed:
            violations.append(
                f"generated: UNLISTED {rpath} (header: {found[rpath][:100]}) "
                f"-- add it to docs/structure/generated-sources.tsv with its generator")
    for rpath in sorted(listed):
        path = os.path.join(ROOT, rpath)
        if not os.path.isfile(path):
            violations.append(f"generated: STALE {rpath} listed but file is gone")
        elif not has_generated_header(path):
            violations.append(
                f"generated: STALE {rpath} listed but its GENERATED header is gone")
    print(f"generated: {len(found)} generated files in tree, {len(listed)} listed")
    return violations


# ------------------------------------------------------------ exceptions ----
# Canonical markers (verbatim list in root AGENTS.md and
# lint-rules ExceptionMarkerFormatDetector). type -> (keyword, reason prefix)
MARKERS = [
    ("PACKAGE_JNI", "PACKAGE STRUCTURE EXCEPTION (JNI)", None),
    ("RAW_SCAFFOLD", "RAW SCAFFOLD EXCEPTION", "// RAW SCAFFOLD EXCEPTION:"),
    ("TOAST", "TOAST EXCEPTION", "// TOAST EXCEPTION:"),
    ("RAW_ANIMATION", "RAW ANIMATION EXCEPTION", "// RAW ANIMATION EXCEPTION:"),
    ("SCAFFOLD_LOADING", "SCAFFOLD LOADING EXCEPTION", "// SCAFFOLD LOADING EXCEPTION:"),
    ("SCAFFOLD_SEARCH", "SCAFFOLD SEARCH EXCEPTION", "// SCAFFOLD SEARCH EXCEPTION:"),
    ("SCREENSHOT_COVERAGE", "SCREENSHOT COVERAGE EXCEPTION",
     "// SCREENSHOT COVERAGE EXCEPTION:"),
]


def scan_exceptions():
    # Aggregated by (type, file, reason) with an occurrence count: line
    # numbers are deliberately not recorded, so moving a marker inside its
    # file does not churn the inventory -- only adding, removing, or
    # rewording a marker does (the growth this ratchet exists to catch).
    from collections import Counter
    counts = Counter()
    total = 0
    for path in walk_files((".kt", ".kts")):
        rpath = rel(path)
        if is_data_path(rpath):
            continue
        with open(path, encoding="utf-8", errors="ignore") as fh:
            for line in fh:
                for mtype, keyword, prefix in MARKERS:
                    if keyword in line:
                        if prefix is None:
                            reason = "JNI (verbatim marker; no free-text reason)"
                        else:
                            idx = line.find(prefix)
                            reason = (line[idx + len(prefix):].strip()
                                      if idx >= 0 else "(malformed: no colon prefix)")
                        counts[(mtype, rpath, reason)] += 1
                        total += 1
                        break
    rows = [(t, f, r, str(c)) for (t, f, r), c in sorted(counts.items())]
    return rows, total


def exceptions_tsv_text(rows):
    out = ["# type\tfile\treason\tcount",
           "# Generated by: python3 tools/structure_check.py exceptions --regen",
           "# A new/changed marker requires regenerating this file and reviewing "
           "the diff; the reason column is copied verbatim from the source marker."]
    out += ["\t".join(r) for r in rows]
    return "\n".join(out) + "\n"


def check_exceptions(regen=False):
    rows, total = scan_exceptions()
    if regen:
        with open(EXCEPTIONS_TSV, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(exceptions_tsv_text(rows))
        print(f"exceptions: regenerated {EXCEPTIONS_TSV} with {total} markers")
        return []
    expected = exceptions_tsv_text(rows)
    actual = ""
    if os.path.isfile(EXCEPTIONS_TSV):
        actual = open(EXCEPTIONS_TSV, encoding="utf-8").read()
    if actual != expected:
        exp = [l for l in expected.splitlines() if l and not l.startswith("#")]
        act = [l for l in actual.splitlines() if l and not l.startswith("#")]
        violations = []
        for l in sorted(set(exp) - set(act)):
            violations.append(f"exceptions: UNRECORDED {l}")
        for l in sorted(set(act) - set(exp)):
            violations.append(f"exceptions: STALE {l}")
        violations.append(
            "exceptions: run `python3 tools/structure_check.py exceptions --regen` "
            "and review/commit the docs/structure/exceptions.tsv diff")
        recorded = sum(int(l.split("\t")[3]) for l in act if len(l.split("\t")) == 4)
        print(f"exceptions: {total} markers in tree, {recorded} recorded")
        return violations
    from collections import Counter
    counts = Counter()
    for r in rows:
        counts[r[0]] += int(r[3])
    print(f"exceptions: {total} markers recorded "
          f"({', '.join(f'{k}={v}' for k, v in sorted(counts.items()))})")
    return []


def main(argv):
    which = argv[1] if len(argv) > 1 else "all"
    regen = "--regen" in argv
    violations = []
    if which in ("wear", "all"):
        violations += check_wear()
    if which in ("generated", "all"):
        violations += check_generated()
    if which in ("exceptions", "all"):
        violations += check_exceptions(regen=regen)
    if which not in ("wear", "generated", "exceptions", "all"):
        print(__doc__)
        return 2
    if violations:
        print("\nSTRUCTURE CHECK FAILURES:")
        for v in violations:
            print(f"  - {v}")
        return 1
    print("structure: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
