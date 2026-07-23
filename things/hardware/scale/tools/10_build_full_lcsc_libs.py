#!/usr/bin/env python3
"""
10_build_full_lcsc_libs.py - Build BOTH symbols and footprints from LCSC cache via vendor pipeline.
Uses kicad-cli sym upgrade + fp upgrade to get v20251024 / v20260206 correct.
This ensures footprints AND symbols both imported, not just one.
"""
import pathlib, json, re, sys, subprocess, tempfile, shutil, csv, uuid
BASE = pathlib.Path(__file__).resolve().parents[1]
CACHE_DIR = BASE / "tools" / "cache"
LIBS_DIR = BASE / "libs"
VENDOR = BASE / "tools" / "vendor" / "kicad-lcsc-manager" / "plugins"
sys.path.insert(0, str(VENDOR))
KICLI = pathlib.Path("/Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli")
BOM_JSON = BASE / "tools" / "bom.json"

# Clean old LCSC files (both mod and sym)
for old in LIBS_DIR.glob("LCSC_*"):
    old.unlink()
print(f"Cleaned LCSC files, kept only Pogo and AFE4300 custom? libs count now {len(list(LIBS_DIR.iterdir()))}")

# Use vendor converters via KiCad python if needed
from lcsc_manager.converters.footprint_converter import FootprintConverter
from lcsc_manager.converters.symbol_converter import SymbolConverter

# Generate legacy pretty and legacy symbols via vendor
tmp_legacy_root = pathlib.Path(tempfile.mkdtemp(prefix="scale_legacy_"))
legacy_pretty = tmp_legacy_root / "scale_libs.pretty"
legacy_pretty.mkdir(parents=True, exist_ok=True)
legacy_sym_dir = tmp_legacy_root / "syms"
legacy_sym_dir.mkdir(exist_ok=True)

conv_fp = FootprintConverter()
conv_sym = SymbolConverter()

# For each cached LCSC, generate legacy footprint and symbol
for jf in sorted(CACHE_DIR.glob("C*.json")):
    try:
        data = json.loads(jf.read_text())
        lcsc_id = data.get('lcsc_id')
        package = data.get('package','')
        ed = data.get('easyeda_data')
        if not ed:
            continue
        comp_info = {"lcsc_id": lcsc_id, "package": package, "name": data.get('name',''), "prefix": data.get('prefix',''), "description": data.get('description','')}
        # footprint
        legacy_fp = conv_fp.convert(ed, comp_info)
        fp_name = conv_fp._get_footprint_name(comp_info)
        (legacy_pretty / f"{fp_name}.kicad_mod").write_text(legacy_fp)
        # symbol - SymbolConverter returns KiCad symbol lib text with outer wrapper (kicad_symbol_lib). We need extract symbol block?
        # Actually SymbolConverter.convert returns full lib with one symbol? In our earlier cache we saw it returns (kicad_symbol_lib ...) ?
        legacy_sym = conv_sym.convert(ed, comp_info)
        # Save per LCSC sym file raw
        (legacy_sym_dir / f"LCSC_{lcsc_id}.kicad_sym").write_text(legacy_sym)
    except Exception as e:
        print(f"error {jf.name}: {e}")
        import traceback; traceback.print_exc()

print(f"legacy pretty {len(list(legacy_pretty.glob('*.kicad_mod')))} syms {len(list(legacy_sym_dir.glob('*.kicad_sym')))}")

# Upgrade footprints via kicad-cli fp upgrade
tmp_new_fp = pathlib.Path(tempfile.mkdtemp(prefix="scale_new_fp_")) / "new.pretty"
# ensure not exists
if tmp_new_fp.exists():
    shutil.rmtree(tmp_new_fp)
cmd = [str(KICLI), "fp", "upgrade", str(legacy_pretty), "--output", str(tmp_new_fp), "--force"]
print(f"Running {' '.join(cmd)}")
res = subprocess.run(cmd, capture_output=True, text=True)
print(res.stdout[-1000:])
print(res.stderr[-1000:])

# Upgrade symbols: need to merge? kicad-cli sym upgrade works on single file, not dir? Use fp-like? Actually sym upgrade is file.
# We'll merge all LCSC symbols into one combined lib first (still legacy format) then upgrade via CLI sym upgrade.
combined_legacy_sym = '(kicad_symbol_lib (version 20241209) (generator "kicad_lcsc_manager") (generator_version "1.0")\n'
for sym_file in legacy_sym_dir.glob("*.kicad_sym"):
    txt = sym_file.read_text()
    # Extract inner symbols: find all (symbol "NAME"... ) inside outer wrapper
    # Remove outer header (kicad_symbol_lib ...) and footer )
    # Find all top-level symbols via regex
    inner_syms = re.findall(r'\(symbol\s+"[^"]+".*?\(embedded_fonts no\)\n\)\n', txt, flags=re.DOTALL)
    # Actually after converter, might be different footer? Try to get all (symbol "...
    if not inner_syms:
        # fallback: search for (symbol "
        parts = txt.split('(symbol "')[1:]
        for part in parts:
            # reconstruct? Easier: if no embedded_fonts, just take?
            pass
        print(f"no inner for {sym_file.name} txt len {len(txt)}")
        continue
    for s in inner_syms:
        combined_legacy_sym += s + "\n"

combined_legacy_sym += ")\n"
tmp_legacy_sym_combined = tmp_legacy_root / "combined_legacy.kicad_sym"
tmp_legacy_sym_combined.write_text(combined_legacy_sym)
print(f"combined legacy sym file len {len(combined_legacy_sym)} at {tmp_legacy_sym_combined}")

# Upgrade via kicad-cli sym upgrade - output must not exist
tmp_new_sym = pathlib.Path(tempfile.mkdtemp(prefix="scale_new_sym_")) / "combined_new.kicad_sym"
cmd2 = [str(KICLI), "sym", "upgrade", str(tmp_legacy_sym_combined), "--output", str(tmp_new_sym), "--force"]
print(f"Running {' '.join(cmd2)}")
res2 = subprocess.run(cmd2, capture_output=True, text=True)
print(res2.stdout[-2000:])
print(res2.stderr[-2000:])
print(f"new sym exists {tmp_new_sym.exists()} size {tmp_new_sym.stat().st_size if tmp_new_sym.exists() else 0}")

# Now copy upgraded footprints to libs/ and upgraded symbol to libs/AFE4300PNR.kicad_sym with custom additions
# First, check upgraded footprints
upgraded_fps = list(tmp_new_fp.glob("*.kicad_mod")) if tmp_new_fp.exists() else []
print(f"Upgraded FP files: {len(upgraded_fps)}")

# For each upgraded footprint in tmp_new_fp, copy to libs with long name preserving
for src in upgraded_fps:
    txt = src.read_text()
    # Remove model blocks to avoid missing 3dmodels
    txt = re.sub(r'\n\t\(model[\s\S]*?\n\t\)\n', '\n', txt)
    dst = LIBS_DIR / src.name
    dst.write_text(txt)
    # Also short alias LCSC_Cxxxx.kicad_mod pointing to same content but footprint name still long - needed for old references? We'll create short alias only if not exists
    m_code = re.search(r'LCSC_(C\d+)', src.name)
    if m_code:
        code=m_code.group(1)
        short = LIBS_DIR / f"LCSC_{code}.kicad_mod"
        if not short.exists():
            # For short alias file, we need footprint name == file base (LCSC_Cxxxx) but our content has long name. We'll make short file content with footprint name = short base
            # Extract long name
            m_fp = re.search(r'\(footprint "([^"]+)"', txt)
            long_name = m_fp.group(1) if m_fp else f"LCSC_{code}"
            # For short alias, replace footprint name with short base
            short_txt = txt.replace(f'(footprint "{long_name}"', f'(footprint "LCSC_{code}"', 1)
            short.write_text(short_txt)
            print(f"Created short alias {short.name} from {src.name}")

# Ensure Pogo custom footprint
pogo_path = LIBS_DIR / "Pogo_Pad_6mm.kicad_mod"
if not pogo_path.exists():
    pogo_path.write_text('(footprint "Pogo_Pad_6mm"\n\t(version 20260206)\n\t(generator "pcbnew")\n\t(generator_version "10.0")\n\t(layer "F.Cu")\n\t(descr "Pogo 6mm SMD")\n\t(property "Reference" "E*" (at 0 3.5 0) (layer "F.SilkS") (effects (font (size 1 1) (thickness 0.15))))\n\t(property "Value" "Pogo_Pad_6mm" (at 0 -3.5 0) (layer "F.Fab") (effects (font (size 1 1) (thickness 0.15))))\n\t(attr smd)\n\t(pad "1" smd circle (at 0 0) (size 6 6) (layers "F.Cu" "F.Paste" "F.Mask") (clearance 0.5))\n\t(embedded_fonts no)\n)\n')

# Now handle symbols: if upgrade succeeded, use new file, else fallback to legacy combined
if tmp_new_sym.exists():
    new_sym_txt = tmp_new_sym.read_text()
    # Add custom compact symbols? We already have LCSC symbols from vendor which are full-size, not compact small boxes.
    # Per plan, we need to generate compact functional symbols for AFE, ETA, NRF, Pogo, PESD as small boxes, plus LCSC symbols for passives etc.
    # Strategy: Keep LCSC symbols as-is for passives (they are correct LCSC sourced), but for U1 U2 U3 custom we replace with compact versions.
    # Let's generate compact custom symbols for AFE4300PNR, ETA9741E8A, NRF52840-QIAA-R, Pogo, PESD and append to new_sym_txt after removing their LCSC versions if present.

    # Remove any existing AFE, ETA, NRF from new_sym_txt to replace with compact
    # We'll extract all symbols and filter out those that match our custom names
    all_syms = re.findall(r'\(symbol\s+"[^"]+".*?\(embedded_fonts no\)\n\)\n', new_sym_txt, flags=re.DOTALL)
    print(f"Upgraded symbols count {len(all_syms)}")

    # Filter out conflicting
    def keep_sym(s):
        m=re.search(r'\(symbol "([^"]+)"', s)
        if not m: return True
        name=m.group(1)
        # If name contains AFE4300PNR, ETA9741, NRF52840, Pogo, PESD -> skip, we'll replace with compact
        if "AFE4300" in name or "ETA9741" in name or "NRF52840" in name or "Pogo" in name or "PESD" in name:
            # But check if it's passives generic? Those don't contain those strings
            return False
        return True

    filtered = [s for s in all_syms if keep_sym(s)]
    print(f"Filtered {len(filtered)} keeping passives")

    # Now generate compact custom symbols with small boxes
    def fmt_pin(x,y,ang,elec,name,num):
        return f'\t\t\t(pin {elec} line\n\t\t\t\t(at {x} {y} {ang})\n\t\t\t\t(length 2.54)\n\t\t\t\t(name "{name}" (effects (font (size 0.7 0.7))))\n\t\t\t\t(number "{num}" (effects (font (size 0.7 0.7))))\n\t\t\t)'

    def make_symbol(sym_name, ref, value, footprint, descr, rs, re_, pins):
        lines=[f'\t(symbol "{sym_name}"',
               f'\t\t(property "Reference" "{ref}" (at 0 10.16 0) (effects (font (size 1.27 1.27))))',
               f'\t\t(property "Value" "{value}" (at 0 -15.24 0) (effects (font (size 1.27 1.27))))',
               f'\t\t(property "Footprint" "{footprint}" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))',
               f'\t\t(property "Datasheet" "" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))',
               f'\t\t(property "Description" "{descr}" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))',
               f'\t\t(symbol "{sym_name}_0_1"',
               f'\t\t\t(rectangle (start {rs[0]} {rs[1]}) (end {re_[0]} {re_[1]}) (stroke (width 0.254) (type default)) (fill (type background)))',
               f'\t\t)',
               f'\t\t(symbol "{sym_name}_1_1"']
        for pin in pins:
            x,y,ang,elec,name,num=pin
            lines.append(fmt_pin(x,y,ang,elec,name,num))
        lines.append('\t\t)')
        lines.append('\t\t(embedded_fonts no)')
        lines.append('\t)')
        return "\n".join(lines)

    def find_fp(code):
        cands=list(LIBS_DIR.glob(f"LCSC_{code}*.kicad_mod"))
        if not cands:
            return f"LCSC_{code}"
        txt=cands[0].read_text()
        m=re.search(r'\(footprint\s+"([^"]+)"', txt)
        return m.group(1) if m else f"LCSC_{code}"

    afe_fp=find_fp("C528638")
    eta_fp=find_fp("C7465513")
    nrf_fp=find_fp("C190794")
    pesd_fp=find_fp("C84374")

    afe = [
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
    eta=[
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
    nrf=[
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

    custom_syms=[]
    custom_syms.append(make_symbol("AFE4300PNR","U","AFE4300PNR",f"scale_libs:{afe_fp}","AFE4300",(-10.16,15.24),(10.16,-15.24),afe))
    custom_syms.append(make_symbol("ETA9741E8A","U","ETA9741E8A",f"scale_libs:{eta_fp}","ETA9741",(-5.08,5.08),(5.08,-5.08),eta))
    custom_syms.append(make_symbol("NRF52840-QIAA-R","U","NRF52840-QIAA-R",f"scale_libs:{nrf_fp}","nRF52840",(-10.16,17.78),(10.16,-17.78),nrf))
    custom_syms.append('\t(symbol "Pogo_Pad_6mm"\n\t\t(property "Reference" "E" (at 0 2.54 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Value" "Pogo_Pad_6mm" (at 0 -2.54 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Footprint" "scale_libs:Pogo_Pad_6mm" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))\n\t\t(symbol "Pogo_Pad_6mm_0_1"\n\t\t\t(circle (center 0 0) (radius 1.27) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t)\n\t\t(symbol "Pogo_Pad_6mm_1_1"\n'+fmt_pin(0,-2.54,90,"passive","1","1")+'\n\t\t)\n\t\t(embedded_fonts no)\n\t)')
    custom_syms.append('\t(symbol "PESD5V0S1BL"\n\t\t(property "Reference" "D" (at 1.27 1.27 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Value" "PESD5V0S1BL" (at 1.27 -1.27 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Footprint" "scale_libs:'+pesd_fp+'" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))\n\t\t(symbol "PESD5V0S1BL_0_1"\n\t\t\t(polyline (pts (xy -1.27 0) (xy 1.27 0)) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t\t(polyline (pts (xy 0 0) (xy 0 -1.27) (xy -1.27 -0.635) (xy 1.27 -0.635) (xy 0 -1.27)) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t)\n\t\t(symbol "PESD5V0S1BL_1_1"\n'+fmt_pin(0,2.54,270,"passive","1","1")+'\n'+fmt_pin(0,-2.54,90,"passive","2","2")+'\n\t\t)\n\t\t(embedded_fonts no)\n\t)')

    # Generate generic passives? Already have filtered containing R_Small etc? Actually legacy LCSC symbols include passives but they have generic values. Our filtered list already includes them.
    # Now build final combined symbol lib: header version 20251024 + filtered + custom

    final_sym = '(kicad_symbol_lib\n\t(version 20251024)\n\t(generator "kicad_symbol_editor")\n\t(generator_version "10.0")\n'
    for s in filtered:
        final_sym += s + "\n"
    for cs in custom_syms:
        final_sym += cs + "\n"
    final_sym += ")\n"
    COMPACT_SYM = LIBS_DIR / "AFE4300PNR.kicad_sym"
    COMPACT_SYM.write_text(final_sym)
    print(f"Final sym written {COMPACT_SYM} total symbols {len(filtered)+len(custom_syms)}")

else:
    print(f"new sym upgrade failed, using legacy")
    pathlib.Path(BASE/"libs"/"AFE4300PNR.kicad_sym").write_text(combined_legacy_sym)

# cleanup tmp
shutil.rmtree(tmp_legacy_root, ignore_errors=True)
try:
    tmp_fp_parent = tmp_new_fp.parent
    shutil.rmtree(tmp_fp_parent, ignore_errors=True)
except:
    pass
try:
    tmp_sym_parent = tmp_new_sym.parent
    shutil.rmtree(tmp_sym_parent, ignore_errors=True)
except:
    pass

print("Done building full LCSC libs: BOTH symbols and footprints")
