#!/usr/bin/env python3
"""Phase1 - vendor clone & deps, per plan."""
import pathlib, subprocess, sys
root = pathlib.Path(__file__).parent
vendor_dir = root / "vendor" / "kicad-lcsc-manager"
if not vendor_dir.exists():
    print("Cloning hulryung/kicad-lcsc-manager")
    subprocess.run(["git","clone","https://github.com/hulryung/kicad-lcsc-manager", str(vendor_dir)], check=True)
else:
    print(f"Exists {vendor_dir}")
print(f"easyeda2kicad at {vendor_dir / 'plugins' / 'lcsc_manager' / 'vendor' / 'easyeda2kicad'}")
print(f"NOTICE {vendor_dir / 'NOTICE.md'}")
# Check deps via KiCad python
kicad_py = "/Applications/KiCad/KiCad.app/Contents/Frameworks/Python.framework/Versions/Current/bin/python3"
subprocess.run([kicad_py, "-m","pip","list"], check=False)
print("Vendor ready - use kicad python for LCSC API: from lcsc_manager.api.lcsc_api import LCSCAPIClient")
