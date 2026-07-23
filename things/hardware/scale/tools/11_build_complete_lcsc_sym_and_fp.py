#!/usr/bin/env python3
"""
Build COMPLETE libs with BOTH symbols and footprints from LCSC via EasyEDA.
Uses vendor converters for BOTH, then merges into AFE4300PNR.kicad_sym with compact custom overrides.
Uses kicad-cli fp upgrade AND sym upgrade per plan to ensure v20260206/v20251024.
"""

import pathlib, json, re, sys, subprocess, tempfile, shutil, uuid
BASE = pathlib.Path(__file__).resolve().parents[1]
CACHE_DIR = BASE / "tools" / "cache"
LIBS_DIR = BASE / "libs"
KICLI = pathlib.Path("/Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli")
VENDOR = BASE / "tools" / "vendor" / "kicad-lcsc-manager" / "plugins"
import sys
sys.path.insert(0, str(VENDOR))

from lcsc_manager.converters.footprint_converter import FootprintConverter
from lcsc_manager.converters.symbol_converter import SymbolConverter

# Clean libs for fresh start - but keep Pogo to regenerate
# Actually keep all but we'll overwrite

# Generate legacy files
tmp_root = pathlib.Path(tempfile.mkdtemp(prefix="lcsc_complete_"))
legacy_fp_dir = tmp_root / "legacy_fp.pretty"
legacy_fp_dir.mkdir(parents=True, exist_ok=True)
legacy_sym_dir = tmp_root / "legacy_sym"
legacy_sym_dir.mkdir(parents=True, exist_ok=True)

conv_fp = FootprintConverter()
conv_sym = SymbolConverter()

for jf in sorted(CACHE_DIR.glob("C*.json")):
    try:
        data = json.loads(jf.read_text())
        lcsc_id = data.get('lcsc_id')
        package = data.get('package','')
        ed = data.get('easyeda_data')
        if not ed:
            continue
        comp_info = {"lcsc_id": lcsc_id, "package": package, "name": data.get('name',''), "prefix": data.get('prefix','U'), "description": data.get('description','')}
        # footprint
        fp_text = conv_fp.convert(ed, comp_info)
        fp_name = conv_fp._get_footprint_name(comp_info)
        (legacy_fp_dir / f"{fp_name}.kicad_mod").write_text(fp_text)
        # symbol
        sym_text = conv_sym.convert(ed, comp_info)
        # Symbol converter returns full lib - save per file
        (legacy_sym_dir / f"LCSC_{lcsc_id}.kicad_sym").write_text(sym_text)
    except Exception as e:
        print(f"error {jf.name}: {e}")
        import traceback; traceback.print_exc()

print(f"legacy fp {len(list(legacy_fp_dir.glob('*.kicad_mod')))} sym files {len(list(legacy_sym_dir.glob('*.kicad_sym')))}")

# Upgrade footprints via kicad-cli fp upgrade
tmp_new_fp_root = pathlib.Path(tempfile.mkdtemp(prefix="new_fp_"))
tmp_new_fp = tmp_new_fp_root / "new.pretty"
cmd = [str(KICLI), "fp", "upgrade", str(legacy_fp_dir), "--output", str(tmp_new_fp), "--force"]
print(f"Running fp upgrade: {' '.join(cmd)}")
res = subprocess.run(cmd, capture_output=True, text=True)
print(res.stdout[-1000:])
print(res.stderr[-1000:])

# For symbols: need to merge legacy syms into one file then sym upgrade
# Each legacy_sym file is itself a kicad_symbol_lib with maybe 1 symbol - we need to extract inner symbols and combine into one legacy lib, then upgrade

combined_legacy_sym_blocks=[]
for sym_file in legacy_sym_dir.glob("*.kicad_sym"):
    txt=sym_file.read_text()
    # Extract all top-level symbols (symbol "..." with embedded_fonts no closing)
    blocks = re.findall(r'\(symbol\s+"[^"]+"(?:[^()]|\([^()]*\)|\(symbol[^\)]*\)|\(property[^\)]*\)|\(pin[^\)]*\)|\(rectangle[^\)]*\)|\(circle[^\)]*\)|\(arc[^\)]*\)|\(polyline[^\)]*\)|\(text[^\)]*\)|\(effects[^\)]*\)|\(font[^\)]*\)|\(at[^\)]*\)|\(size[^\)]*\)|\(length[^\)]*\)|\(name[^\)]*\)|\(number[^\)]*\)|\(stroke[^\)]*\)|\(fill[^\)]*\)|\(pts[^\)]*\)|\(xy[^\)]*\)|\(width[^\)]*\)|\(hide[^\)]*\)|\(show_name[^\)]*\)|\(do_not_autoplace[^\)]*\)|\(justify[^\)]*\)|\(offset[^\)]*\))*?\(embedded_fonts no\)\s*\)', txt, flags=re.DOTALL)
    # Fallback simpler: split by (symbol " and reconstruct via balanced parser
    if not blocks:
        # Use balanced parser for each symbol
        def extract_symbols(s):
            res=[]
            pat = re.compile(r'\(symbol\s+"([^"]+)"')
            for m in pat.finditer(s):
                start=m.start()
                depth=0; in_str=False; esc=False
                i=start
                while i < len(s):
                    c=s[i]
                    if esc: esc=False
                    elif c=='\\': esc=True
                    elif c=='"' and not esc: in_str=not in_str
                    elif not in_str:
                        if c=='(': depth+=1
                        elif c==')':
                            depth-=1
                            if depth==0:
                                res.append(s[start:i+1])
                                break
                    i+=1
            return res
        # Need to avoid outer wrapper (kicad_symbol_lib) - extract inner
        inner = txt[txt.find('\n', txt.find('(kicad_symbol_lib')):]
        # Remove outer wrapper's first closing? Simpler: extract all symbols after header
        blocks = extract_symbols(inner)
        # Filter out _0_1/_1_1 sub-symbols
        blocks = [b for b in blocks if '_0_1' not in b and '_1_1' not in b]
    combined_legacy_sym_blocks.extend(blocks)

print(f"Combined legacy symbol blocks extracted: {len(combined_legacy_sym_blocks)}")

# Build combined legacy lib
combined_legacy_path = tmp_root / "combined_legacy.kicad_sym"
combined_content = '(kicad_symbol_lib\n\t(version 20241209)\n\t(generator "kicad_lcsc_manager")\n\t(generator_version "1.0")\n'
for blk in combined_legacy_sym_blocks:
    combined_content += blk + "\n"
combined_content += ")\n"
combined_legacy_path.write_text(combined_content)

# Upgrade symbol lib via kicad-cli sym upgrade
tmp_new_sym_root = pathlib.Path(tempfile.mkdtemp(prefix="new_sym_"))
tmp_new_sym = tmp_new_sym_root / "upgraded.kicad_sym"
# Ensure output not exists
if tmp_new_sym.exists():
    tmp_new_sym.unlink()
cmd2 = [str(KICLI), "sym", "upgrade", str(combined_legacy_path), "--output", str(tmp_new_sym), "--force"]
print(f"Running sym upgrade: {' '.join(cmd2)}")
res2 = subprocess.run(cmd2, capture_output=True, text=True)
print(res2.stdout[-2000:])
print(res2.stderr[-2000:])

# Copy upgraded footprints to libs/
LIBS_DIR.mkdir(parents=True, exist_ok=True)
# Clean old LCSC_* mods
for old in LIBS_DIR.glob("LCSC_*"):
    old.unlink()
    print(f"Removed {old}")

for src in (tmp_new_fp.glob("*.kicad_mod") if tmp_new_fp.exists() else []):
    txt = src.read_text()
    txt = re.sub(r'\n\t\(model[\s\S]*?\n\t\)\n', '\n', txt)  # strip 3d model
    # Ensure single footprint count
    dst = LIBS_DIR / src.name
    dst.write_text(txt)
                pad_cnt = txt.count('(pad "')
                print(f"  pads {pad_cnt}")

# Ensure Pogo pad custom footprint exists (only manual exception per plan)
pogo = LIBS_DIR / "Pogo_Pad_6mm.kicad_mod"
if not pogo.exists():
    pogo.write_text('(footprint "Pogo_Pad_6mm"\n\t(version 20260206)\n\t(generator "pcbnew")\n\t(generator_version "10.0")\n\t(layer "F.Cu")\n\t(descr "Pogo 6mm SMD ENIG")\n\t(property "Reference" "E*" (at 0 3.5 0) (layer "F.SilkS") (effects (font (size 1 1) (thickness 0.15))))\n\t(property "Value" "Pogo_Pad_6mm" (at 0 -3.5 0) (layer "F.Fab") (effects (font (size 1 1) (thickness 0.15))))\n\t(attr smd)\n\t(pad "1" smd circle (at 0 0) (size 6 6) (layers "F.Cu" "F.Paste" "F.Mask") (clearance 0.5))\n\t(embedded_fonts no)\n)\n')

# Now merge symbols: if upgraded sym file exists, use it, else fallback to combined legacy
# The upgraded file may contain upgraded symbols (version 20251024 after upgrade?)
if tmp_new_sym.exists():
    upgraded_sym_txt = tmp_new_sym.read_text()
                sym_cnt = txt.count('(symbol "')
                print(f"  symbols {sym_cnt}")
else:
    upgraded_sym_txt = combined_content

# Now we need to ensure we have compact custom symbols for AFE4300PNR, ETA9741E8A, NRF52840-QIAA-R (small boxes) overriding LCSC versions
# Remove LCSC versions of those 3 chips from upgraded_sym_txt if present (they would be LCSC_C528638 etc? Actually LCSC symbols for chips have their own names like "AFE4300PNR" already, so they collide)
# We'll filter out any symbol whose name matches custom names, then append compact custom

def extract_all_sym_blocks(txt):
    # balanced extractor
    pat = re.compile(r'\(symbol\s+"([^"]+)"')
    res=[]
    i=0
    while True:
        m=pat.search(txt,i)
        if not m: break
        start=m.start()
        # skip if it's sub-symbol _0_1/_1_1? keep only outer
        # To determine outer vs inner, check if after symbol name there's (property -> outer
        # We'll extract full block
        depth=0; in_str=False; esc=False
        j=start
        while j < len(txt):
            c=txt[j]
            if esc: esc=False
            elif c=='\\': esc=True
            elif c=='"' and not esc: in_str=not in_str
            elif not in_str:
                if c=='(':
                    depth+=1
                elif c==')':
                    depth-=1
                    if depth==0:
                        res.append(txt[start:j+1])
                        i=j+1
                        break
            j+=1
        else:
            break
    return res

all_blocks = extract_all_sym_blocks(upgraded_sym_txt)
# Filter out any that are sub-symbols _0_1/_1_1? Actually outer symbols contain inner _0_1 and _1_1 inside? In new format after upgrade, outer contains inner? Wait earlier AFE compact had _0_1 and _1_1 inside outer. But after vendor converter, it may have different structure: outer symbol contains inner? Let's filter to keep only files where symbol name doesn't contain _0_1 and not kicad_symbol_lib
outer_blocks=[]
for b in all_blocks:
    m=re.search(r'\(symbol "([^"]+)"', b)
    if not m: continue
    name=m.group(1)
    if name.endswith('_0_1') or name.endswith('_1_1') or name=='kicad_symbol_lib':
        continue
    # If name is LCSC_C... with suffix, keep? That is footprint name, not symbol. Our legacy sym blocks have symbol names like "AFE4300PNR" etc.
    outer_blocks.append(b)

print(f"Outer blocks after filter: {len(outer_blocks)} names {[re.search(r'\(symbol \"([^\"]+)\"', b).group(1) for b in outer_blocks[:10]]}")

# Further filter out chips to replace with compact
def is_custom_replace(name):
    return "AFE4300" in name or "ETA9741" in name or "NRF52840" in name

filtered = [b for b in outer_blocks if not is_custom_replace(re.search(r'\(symbol "([^"]+)"', b).group(1))]

# Now create compact custom symbols (small boxes 20x30 vs huge 76x81)
def fmt_pin(x,y,ang,elec,name,num):
    return f'\t\t\t(pin {elec} line (at {x} {y} {ang}) (length 2.54) (name "{name}" (effects (font (size 0.7 0.7)))) (number "{num}" (effects (font (size 0.7 0.7)))))'

def make_small(name, ref, value, footprint, descr, rs, re_, pins):
    lines=[f'\t(symbol "{name}"',
           f'\t\t(property "Reference" "{ref}" (at 0 10.16 0) (effects (font (size 1.27 1.27))))',
           f'\t\t(property "Value" "{value}" (at 0 -15.24 0) (effects (font (size 1.27 1.27))))',
           f'\t\t(property "Footprint" "{footprint}" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))',
           f'\t\t(property "Datasheet" "" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))',
           f'\t\t(property "Description" "{descr}" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))',
           f'\t\t(symbol "{name}_0_1"',
           f'\t\t\t(rectangle (start {rs[0]} {rs[1]}) (end {re_[0]} {re_[1]}) (stroke (width 0.254) (type default)) (fill (type background)))',
           f'\t\t)',
           f'\t\t(symbol "{name}_1_1"']
    for x,y,ang,elec,nm,nn in pins:
        lines.append(fmt_pin(x,y,ang,elec,nm,nn))
    lines.append('\t\t)')
    lines.append('\t\t(embedded_fonts no)')
    lines.append('\t)')
    return "\n".join(lines)

def find_fp_lcsc(code):
    cands=list(LIBS_DIR.glob(f"*{code}*.kicad_mod"))
    if not cands: return f"LCSC_{code}"
    txt=cands[0].read_text()
    m=re.search(r'\(footprint "([^"]+)"', txt)
    return m.group(1) if m else f"LCSC_{code}"

afe_fp=find_fp_lcsc("C528638")
eta_fp=find_fp_lcsc("C7465513")
nrf_fp=find_fp_lcsc("C190794")
pesd_fp=find_fp_lcsc("C84374")

afe_pins=[
    (-12.7,12.7,0,"passive","IFILTER_P","11"),
    (-12.7,10.16,0,"passive","IFILTER_N","12"),
    (-12.7,7.62,0,"output","I_OUTP","13"),
    (-12.7,5.08,0,"output","I_OUTN","14"),
    (-12.7,2.54,0,"input","V_INP","15"),
    (-12.7,0.0,0,"input","V_INN","16"),
    (-12.7,-2.54,0,"passive","RREF","18"),
    (-12.7,-5.08,0,"passive","VREF_C","20"),
    (-12.7,-7.62,0,"power_in","AVDD","23"),
    (-12.7,-10.16,0,"input","WS1_P","30"),
    (-12.7,-12.7,0,"input","WS1_N","31"),
    (-12.7,-15.24,0,"power_in","WS5","36"),
    (-12.7,-17.78,0,"power_in","WS6","37"),
    (12.7,12.7,180,"power_in","DVDD","45"),
    (12.7,10.16,180,"power_in","AVDD2","46"),
    (12.7,7.62,180,"input","XIN","79"),
    (12.7,5.08,180,"input","RESET_B","53"),
    (12.7,2.54,180,"input","DRDY","59"),
    (12.7,0.0,180,"input","CS","54"),
    (12.7,-2.54,180,"output","SDO","56"),
    (12.7,-5.08,180,"input","SDI","57"),
    (12.7,-7.62,180,"input","SCLK","58"),
    (12.7,-10.16,180,"power_in","AVSS","1"),
    (12.7,-12.7,180,"power_in","AVSS2","32"),
    (12.7,-15.24,180,"power_in","AVSS3","60"),
]
eta_pins=[
    (-7.62,3.81,0,"input","CE","1"),
    (-7.62,1.27,0,"input","NTC","2"),
    (-7.62,-1.27,0,"passive","ISET","3"),
    (-7.62,-3.81,0,"power_in","BAT","6"),
    (0,-7.62,90,"power_in","GND","7"),
    (7.62,-3.81,180,"input","EN","9"),
    (7.62,-1.27,180,"power_out","VOUT","12"),
    (7.62,1.27,180,"output","SW","15"),
    (7.62,3.81,180,"power_in","VIN","20"),
]
nrf_pins=[
    (-12.7,15.24,0,"output","P0.02_MCO_8MHz","A12"),
    (-12.7,12.7,0,"input","P0.03_VBAT_ADC","B13"),
    (-12.7,10.16,0,"bidirectional","P0.09_INT1_ACC","L24"),
    (-12.7,7.62,0,"input","P0.10_BTN","J24"),
    (-12.7,5.08,0,"input","P0.11_MISO","T2"),
    (-12.7,2.54,0,"output","P0.12_MOSI","U1"),
    (-12.7,0.0,0,"output","P0.13_SCLK","AD8"),
    (-12.7,-2.54,0,"output","P0.14_CS","AC9"),
    (-12.7,-5.08,0,"input","P0.20_DRDY","AD16"),
    (-12.7,-7.62,0,"output","P0.22_RESET","AD18"),
    (-12.7,-10.16,0,"bidirectional","P0.30_SDA","B9"),
    (-12.7,-12.7,0,"bidirectional","P0.31_SCL","A8"),
    (12.7,15.24,180,"input","XC1","B24"),
    (12.7,12.7,180,"input","XC2","A23"),
    (12.7,10.16,180,"input","XL1","D2"),
    (12.7,7.62,180,"input","XL2","F2"),
    (12.7,5.08,180,"power_in","VDD","B1"),
    (12.7,2.54,180,"power_in","GND","B7"),
    (12.7,0.0,180,"bidirectional","USB_D-","AD4"),
    (12.7,-2.54,180,"bidirectional","USB_D+","AD6"),
    (12.7,-5.08,180,"output","ANT","H23"),
    (12.7,-7.62,180,"bidirectional","SWDIO","AC24"),
    (12.7,-10.16,180,"input","SWCLK","AA24"),
    (12.7,-12.7,180,"power_out","DEC","B5"),
]

custom=[
    make_small("AFE4300PNR","U","AFE4300PNR",f"scale_libs:{afe_fp}","AFE4300",(-10.16,15.24),(10.16,-15.24),afe_pins),
    make_small("ETA9741E8A","U","ETA9741E8A",f"scale_libs:{eta_fp}","ETA9741",(-5.08,5.08),(5.08,-5.08),eta_pins),
    make_small("NRF52840-QIAA-R","U","NRF52840-QIAA-R",f"scale_libs:{nrf_fp}","nRF52840",(-10.16,17.78),(10.16,-17.78),nrf_pins),
    '\t(symbol "Pogo_Pad_6mm"\n\t\t(property "Reference" "E" (at 0 2.54 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Value" "Pogo_Pad_6mm" (at 0 -2.54 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Footprint" "scale_libs:Pogo_Pad_6mm" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))\n\t\t(symbol "Pogo_Pad_6mm_0_1"\n\t\t\t(circle (center 0 0) (radius 1.27) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t)\n\t\t(symbol "Pogo_Pad_6mm_1_1"\n'+f'\t\t\t(pin passive line (at 0 -2.54 90) (length 2.54) (name "1" (effects (font (size 1 1)))) (number "1" (effects (font (size 1 1)))))\n'+'\t\t)\n\t\t(embedded_fonts no)\n\t)',
    '\t(symbol "PESD5V0S1BL"\n\t\t(property "Reference" "D" (at 1.27 1.27 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Value" "PESD5V0S1BL" (at 1.27 -1.27 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Footprint" "scale_libs:'+pesd_fp+'" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))\n\t\t(symbol "PESD5V0S1BL_0_1"\n\t\t\t(polyline (pts (xy -1.27 0) (xy 1.27 0)) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t\t(polyline (pts (xy 0 0) (xy 0 -1.27) (xy -1.27 -0.635) (xy 1.27 -0.635) (xy 0 -1.27)) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t)\n\t\t(symbol "PESD5V0S1BL_1_1"\n\t\t\t(pin passive line (at 0 2.54 270) (length 0.762) (name "1" (effects (font (size 1 1)))) (number "1" (effects (font (size 1 1)))))\n\t\t\t(pin passive line (at 0 -2.54 90) (length 0.762) (name "2" (effects (font (size 1 1)))) (number "2" (effects (font (size 1 1)))))\n\t\t)\n\t\t(embedded_fonts no)\n\t)',
]

final_lib = '(kicad_symbol_lib\n\t(version 20251024)\n\t(generator "kicad_symbol_editor")\n\t(generator_version "10.0")\n'
for blk in filtered:
    final_lib+=blk+"\n"
for cb in custom:
    final_lib+=cb+"\n"
final_lib+=")\n"

out_sym = BASE / "libs" / "AFE4300PNR.kicad_sym"
out_sym.write_text(final_lib)
print(f"Final LIB both symbols and footprints merged: {out_sym} symbols total {len(filtered)+len(custom)} includes LCSC passives + custom small boxes")

# Write tables
(BASE / "sym-lib-table").write_text('(sym_lib_table\n\t(version 7)\n\t(lib (name "scale_libs") (type "KiCad") (uri "${KIPRJMOD}/libs/AFE4300PNR.kicad_sym") (options "") (descr ""))\n)\n')
(BASE / "fp-lib-table").write_text('(fp_lib_table\n\t(version 7)\n\t(lib (name "scale_libs") (type "KiCad") (uri "${KIPRJMOD}/libs") (options "") (descr "LCSC sourced footprints"))\n)\n')

# Cleanup
shutil.rmtree(tmp_root, ignore_errors=True)
try: shutil.rmtree(tmp_new_fp_root, ignore_errors=True)
except: pass
try: shutil.rmtree(tmp_new_sym_root, ignore_errors=True)
except: pass

print("Done")