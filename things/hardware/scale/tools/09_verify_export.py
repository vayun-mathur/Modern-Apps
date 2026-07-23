#!/usr/bin/env python3
"""
09_verify_export.py - Final verification & export via kicad-cli only.
No f-string backslash inside expressions per sys reminder fix.
"""
import pathlib, subprocess, json, re
root = pathlib.Path(__file__).parents[1]
kicad_cli = "/Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli"

def run(cmd):
    print(f"$ {' '.join(cmd)}")
    res = subprocess.run(cmd, capture_output=True, text=True)
    print(res.stdout[-2000:])
    if res.stderr: print(res.stderr[-2000:])
    return res

# upgrade
run([kicli := kicad_cli, "sch", "upgrade", "--force", str(root / "scale.kicad_sch")])
run([kicli, "pcb", "upgrade", "--force", str(root / "scale.kicad_pcb")])

# erc/drc
run([kicli, "sch", "erc", str(root / "scale.kicad_sch"), "--output", "/tmp/erc.json", "--format", "json", "--severity-all"])
run([kicli, "pcb", "drc", str(root / "scale.kicad_pcb"), "--output", "/tmp/drc.json", "--format", "json", "--severity-error"])

# versions
sch_txt = (root / "scale.kicad_sch").read_text()
pcb_txt = (root / "scale.kicad_pcb").read_text()
sym_txt = (root / "libs" / "AFE4300PNR.kicad_sym").read_text()

fp_pattern = '(footprint "'
lib_pattern = '(lib_id "'

fp_cnt = len(re.findall(r'\(footprint "', pcb_txt))
lib_cnt = sch_txt.count(lib_pattern)
print(f"SCH version 20260306: {'20260306' in sch_txt} PCB version 20260206: {'20260206' in pcb_txt} SYM version 20251024: {'20251024' in sym_txt}")
print(f"Footprints exact count: {fp_cnt} (should be 76)")
print(f"SCH lib_id count: {lib_cnt}")
wire_cnt = sch_txt.count('(wire')
label_cnt = sch_txt.count('(label')
junction_cnt = sch_txt.count('(junction')
print(f"SCH wires: {wire_cnt} labels: {label_cnt} junctions: {junction_cnt}")
print(f"Negative checks: font_typo={'font (size 1 27 27)' in sch_txt}")

# exports
gerber_dir = root / "gerbers"
gerber_dir.mkdir(exist_ok=True)
run([kicli, "sch", "export", "pdf", "--output", str(gerber_dir / "schematic.pdf"), str(root / "scale.kicad_sch")])
run([kicli, "pcb", "export", "gerbers", "--output", str(gerber_dir), str(root / "scale.kicad_pcb")])
run([kicli, "pcb", "export", "drill", "--output", str(gerber_dir), str(root / "scale.kicad_pcb")])
run([kicli, "pcb", "export", "pos", "--format", "csv", "--units", "mm", "--output", str(root / "fab" / "CPL_top.csv"), str(root / "scale.kicad_pcb")])

print("Exports done")
