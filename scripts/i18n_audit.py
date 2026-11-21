#!/usr/bin/env python3
"""i18n coverage audit for Modern-Apps (stdlib-only, read-only).

For every module with src/main/res/values/strings.xml, parse the English
keys (string, string-array, plurals) and compare them against each
values-XX/strings.xml sibling. Emits per app x locale counts:

    translated  keys present in the locale file
    missing     English keys absent from the locale file
    suspect     present keys whose value is identical to English
    coverage    translated / total English keys (%)

Empty values-XX/ dirs are Weblate scaffolding and correctly report 0%.

Usage:
    python3 scripts/i18n_audit.py [--root DIR] [--app SUBSTR]
        [--locale CODE] [--min-coverage PCT] [--csv] [--json PATH]

Filters: --app is a case-insensitive substring match on the module path
(repeat or comma-separate for several); --locale is an exact locale dir
suffix (e.g. de, pt-rBR; case-insensitive); --min-coverage keeps only
rows at or above that percentage. Exit code is 0 on success (malformed
XML is reported in the output and does not abort the run). Never writes
to the repo except the explicit --json path.
"""

import argparse
import csv
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

LOCALE_RE = re.compile(r"^[a-z]{2,3}(-r[A-Z]{2})?$|^b\+[a-zA-Z+]+$")
XLIFF_G = "{urn:oasis:names:tc:xliff:document:1.2}g"


def elem_text(elem) -> str:
    """All text of an element, with <xliff:g> content inlined."""
    parts = []
    if elem.text:
        parts.append(elem.text)
    for child in elem:
        if child.tag == XLIFF_G:
            inner = elem_text(child)
            parts.append(inner if inner else (child.get("example", "") or ""))
        else:
            parts.append(elem_text(child))
        if child.tail:
            parts.append(child.tail)
    return "".join(parts).strip()


def parse_strings(path: Path):
    """Return (keys: dict[str, str], error: str | None).

    Key value is the normalized text: joined items for string-array,
    quantity-sorted item texts for plurals. translatable="false"
    strings are excluded from the English denominator.
    """
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return {}, f"malformed XML: {exc}"
    except OSError as exc:
        return {}, f"unreadable: {exc}"
    keys = {}
    for elem in root:
        name = elem.get("name")
        if not name:
            continue
        if elem.tag == "string":
            if elem.get("translatable") == "false":
                continue
            keys[name] = elem_text(elem)
        elif elem.tag == "string-array":
            keys[name] = "\x1f".join(
                elem_text(i) for i in elem if i.tag == "item")
        elif elem.tag == "plurals":
            items = sorted(
                (i.get("quantity", ""), elem_text(i))
                for i in elem if i.tag == "item")
            keys[name] = "\x1f".join(f"{q}={t}" for q, t in items)
    return keys, None


def find_en_files(root: Path):
    for path in sorted(root.rglob("values/strings.xml")):
        if "build" in path.parts:  # skip build/ output dirs
            continue
        if path.parent.parent.name == "res" and path.parent.parent.parent.name == "main":
            yield path


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent),
                    help="repo root (default: parent of scripts/)")
    ap.add_argument("--app", action="append", default=[],
                    help="module substring filter (repeatable/comma-separated)")
    ap.add_argument("--locale", action="append", default=[],
                    help="locale filter, e.g. de or pt-rBR (repeatable)")
    ap.add_argument("--min-coverage", type=float, default=None,
                    help="only show rows with coverage >= PCT")
    ap.add_argument("--csv", action="store_true", help="CSV to stdout")
    ap.add_argument("--json", metavar="PATH", help="also write JSON report to PATH")
    args = ap.parse_args(argv)

    root = Path(args.root)
    if not root.is_dir():
        print(f"error: root not found: {root}", file=sys.stderr)
        return 2
    apps = [a.lower() for grp in args.app for a in grp.split(",") if a]
    locales = [a.lower() for grp in args.locale for a in grp.split(",") if a]

    rows, errors = [], []
    for en_path in find_en_files(root):
        res_dir = en_path.parent.parent
        module = str(res_dir.parent.parent.parent.relative_to(root))
        if apps and not any(a in module.lower() for a in apps):
            continue
        en_keys, err = parse_strings(en_path)
        if err:
            errors.append({"app": module, "locale": "(en)", "file": str(en_path), "error": err})
        locale_dirs = sorted(
            d for d in res_dir.iterdir()
            if d.is_dir() and d.name.startswith("values-")
            and LOCALE_RE.match(d.name[len("values-"):]))
        for loc_dir in locale_dirs:
            locale = loc_dir.name[len("values-"):]
            if locales and locale.lower() not in locales:
                continue
            loc_file = loc_dir / "strings.xml"
            loc_keys = {}
            if loc_file.is_file():
                loc_keys, err = parse_strings(loc_file)
                if err:
                    errors.append({"app": module, "locale": locale,
                                   "file": str(loc_file), "error": err})
            total = len(en_keys)
            present = [k for k in en_keys if k in loc_keys]
            suspect = sum(1 for k in present if loc_keys[k] == en_keys[k])
            coverage = (100.0 * len(present) / total) if total else 0.0
            if args.min_coverage is not None and coverage < args.min_coverage:
                continue
            rows.append({"app": module, "locale": locale, "total": total,
                         "translated": len(present), "missing": total - len(present),
                         "suspect": suspect, "coverage": round(coverage, 1)})

    rows.sort(key=lambda r: (r["coverage"], r["app"], r["locale"]))
    tot = {"app": "TOTAL", "locale": f"{len({r['locale'] for r in rows})} locales",
           "total": sum(r["total"] for r in rows),
           "translated": sum(r["translated"] for r in rows),
           "missing": sum(r["missing"] for r in rows),
           "suspect": sum(r["suspect"] for r in rows)}
    tot["coverage"] = round(100.0 * tot["translated"] / tot["total"], 1) if tot["total"] else 0.0

    if args.json:
        Path(args.json).write_text(json.dumps(
            {"rows": rows, "total": tot, "errors": errors}, indent=2) + "\n")
    if args.csv:
        w = csv.writer(sys.stdout)
        w.writerow(["app", "locale", "total", "translated", "missing", "suspect", "coverage_pct"])
        for r in rows + [tot]:
            w.writerow([r["app"], r["locale"], r["total"], r["translated"],
                        r["missing"], r["suspect"], r["coverage"]])
    else:
        hdr = f"{'app':<28} {'locale':<10} {'keys':>6} {'transl':>6} {'miss':>6} {'ident':>6} {'cover%':>7}"
        print(hdr); print("-" * len(hdr))
        for r in rows:
            print(f"{r['app']:<28} {r['locale']:<10} {r['total']:>6} {r['translated']:>6} "
                  f"{r['missing']:>6} {r['suspect']:>6} {r['coverage']:>7}")
        print("-" * len(hdr))
        print(f"{tot['app']:<28} {tot['locale']:<10} {tot['total']:>6} {tot['translated']:>6} "
              f"{tot['missing']:>6} {tot['suspect']:>6} {tot['coverage']:>7}")
    for e in errors:
        print(f"warning: {e['file']}: {e['error']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
