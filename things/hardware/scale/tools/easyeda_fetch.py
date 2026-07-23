#!/usr/bin/env python3
"""Thin wrapper around manager/api.py LCSC search, per plan."""
import sys
sys.path.insert(0, str(pathlib.Path(__file__).parent / "vendor" / "kicad-lcsc-manager" / "plugins"))
from lcsc_manager.api.lcsc_api import LCSCAPIClient
import pathlib, json, time

def fetch_lcsc(lcsc_code, cache_dir="cache"):
    cache_dir = pathlib.Path(__file__).parent / cache_dir
    cache_dir.mkdir(exist_ok=True)
    cache_file = cache_dir / f"{lcsc_code}.json"
    if cache_file.exists():
        print(f"Cache hit {lcsc_code}")
        return json.loads(cache_file.read_text())
    client = LCSCAPIClient()
    print(f"Fetching {lcsc_code}")
    comp = client.search_component(lcsc_code)
    if not comp:
        print(f"NOT FOUND {lcsc_code}")
        return None
    cache_file.write_text(json.dumps(comp))
    print(f"Saved {cache_file}")
    time.sleep(5)
    return comp

if __name__=="__main__":
    import sys
    for code in sys.argv[1:]:
        fetch_lcsc(code)
