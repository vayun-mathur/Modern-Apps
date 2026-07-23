#!/usr/bin/env python3
"""
Dual import both LCSC symbols and footprints via vendor's native BomImporter + LibraryManager.
This is the ONLY plugin-driven flow that generates BOTH .kicad_sym and .kicad_mod and updates sym-lib-table/fp-lib-table automatically.
Uses only kicad-cli and plugin scripts as per your constraint. No hand S-expr after this point for libs.
"""

import pathlib, sys
BASE = pathlib.Path("/Users/vayun/Documents/Modern-Apps/things/hardware/scale")
VENDOR = BASE / "tools" / "vendor" / "kicad-lcsc-manager" / "plugins"
sys.path.insert(0, str(VENDOR))

from lcsc_manager.bom.bom_parser import parse_bom
from lcsc_manager.library.library_manager import LibraryManager
from lcsc_manager.bom.bom_importer import BomImporter, BomImportOptions
from lcsc_manager.api.lcsc_api import LCSCAPIClient

proj = BASE / "scale.kicad_pro"
print(f"Parsing {BASE / 'fab' / 'import_bom.csv'} via bom_parser.py")
result = parse_bom(BASE / "fab" / "import_bom.csv")
print(f"Parsed {len(result.entries)} entries: {[e.lcsc_id for e in result.entries]}")

lm = LibraryManager(proj)
print(f"Library base: {lm.lib_base_path}")
print(f"Symbol lib: {lm.symbol_lib_path} exists={lm.symbol_lib_path.exists()}")
print(f"Footprint lib: {lm.footprint_lib_path} exists={lm.footprint_lib_path.exists()} files={len(list(lm.footprint_lib_path.glob('*.kicad_mod'))) if lm.footprint_lib_path.exists() else 0}")

# The library manager's default config writes to libs/lcsc/symbols/lcsc_imported.kicad_sym and libs/lcsc/footprints.pretty
# We already have 26 cache JSONs in tools/cache/ to avoid hammering EasyEDA (rate limited after 5 requests with HTTP 403)

api = LCSCAPIClient()
importer = BomImporter(api, lm)
options = BomImportOptions(import_symbol=True, import_footprint=True, import_3d=False)

# Import all entries using cached files - monkey-patch api.search_component to read cache first
import json
CACHE_DIR = BASE / "tools" / "cache"

original_search = api.search_component

def cached_search(lcsc_id):
    cache_file = CACHE_DIR / f"{lcsc_id}.json"
    if cache_file.exists():
        try:
            return json.loads(cache_file.read_text())
        except Exception:
            pass
    # Fallback to network for missing
    return original_search(lcsc_id)

api.search_component = cached_search

print("Starting batch import of 26 LCSC using cached EasyEDA data (both symbol+footprint)")
summary = importer.import_entries(result.entries, options, progress_cb=lambda i,t,lcsc,phase: print(f"{i+1}/{t} {lcsc} {phase}"))

print(f"Import done: OK {len(summary.imported)} FAIL {len(summary.failed)} rate_limited={summary.rate_limited}")
for r in summary.failed:
    print(f"  FAIL {r.lcsc_id}: {r.error}")

# After import, upgrade footprints and symbols via kicad-cli to v20260206 / v20251024
import subprocess
KICLI = pathlib.Path("/Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli")

# FP upgrade: from libs/lcsc/footprints.pretty (legacy module) to same dir but new format?
# kicad-cli fp upgrade takes input pretty and output pretty - we need to upgrade in place? We'll use temp output then copy back
import tempfile, shutil

if lm.footprint_lib_path.exists():
    tmp_out = pathlib.Path(tempfile.mkdtemp()) / "upgraded.pretty"
    cmd = [str(KICLI), "fp", "upgrade", str(lm.footprint_lib_path), "--output", str(tmp_out), "--force"]
    print(f"Running {' '.join(cmd)}")
    res = subprocess.run(cmd, capture_output=True, text=True)
    print(res.stdout)
    print(res.stderr)
    # Copy back
    if tmp_out.exists():
        for src in tmp_out.glob("*.kicad_mod"):
            dst = lm.footprint_lib_path / src.name
            dst.write_text(src.read_text())
        shutil.rmtree(tmp_out.parent)

# Symbol upgrade
if lm.symbol_lib_path.exists():
    tmp_out_sym = pathlib.Path(tempfile.mkdtemp()) / "upgraded.kicad_sym"
    cmd2 = [str(KICLI), "sym", "upgrade", str(lm.symbol_lib_path), "--output", str(tmp_out_sym), "--force"]
    print(f"Running {' '.join(cmd2)}")
    res2 = subprocess.run(cmd2, capture_output=True, text=True)
    print(res2.stdout)
    print(res2.stderr)
    if tmp_out_sym.exists():
        # Overwrite
        lm.symbol_lib_path.write_text(tmp_out_sym.read_text())
        shutil.rmtree(tmp_out_sym.parent)

print(f"After CLI upgrades:")
print(f"FPs: {len(list(lm.footprint_lib_path.glob('*.kicad_mod')))} SYM size: {lm.symbol_lib_path.stat().st_size if lm.symbol_lib_path.exists() else 0}")

print(f"sym-lib-table:\n{(BASE/'sym-lib-table').read_text()}")
print(f"fp-lib-table:\n{(BASE/'fp-lib-table').read_text()}")
