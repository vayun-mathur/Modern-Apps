#!/usr/bin/env python3
"""
04_build_libs_from_cache.py – final version using kicad-cli fp upgrade dir approach

Steps:
1. Validate 26 cache JSONs
2. Generate legacy (module) footprints to /tmp/legacy.pretty using vendor FootprintConverter (KiCad Python)
3. Run kicad-cli fp upgrade /tmp/legacy.pretty --output /tmp/new.pretty --force to get v20260206 with uuids
4. Apply ETA9741 C7465513 pad remap to spec: 1 CE,2 NTC,3 ISET,6 BAT,7 GND,9 EN,12 VOUT,15 SW,20 VIN
5. Copy upgraded files to libs/ preserving long names LCSC_Cxxxx_<package>.kicad_mod and also short alias LCSC_Cxxxx.kicad_mod
6. Ensure custom Pogo_Pad_6mm footprint v20260206
7. Generate compact functional symbol lib libs/AFE4300PNR.kicad_sym v20251024 generator kicad_symbol_editor
8. Write fp-lib-table v7 and sym-lib-table v7

KiCad CLI: /Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli
KiCad Python: /Applications/KiCad/KiCad.app/Contents/Frameworks/Python.framework/Versions/Current/bin/python3
"""
import json, pathlib, sys, subprocess, tempfile, shutil, re, os

BASE = pathlib.Path(__file__).resolve().parents[1]
CACHE_DIR = BASE / "tools" / "cache"
LIBS_DIR = BASE / "libs"
FP_TABLE = BASE / "fp-lib-table"
SYM_TABLE = BASE / "sym-lib-table"
COMPACT_SYM = LIBS_DIR / "AFE4300PNR.kicad_sym"

KICAD_CLI = pathlib.Path("/Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli")
VENDOR_PLUGIN = BASE / "tools" / "vendor" / "kicad-lcsc-manager" / "plugins"
sys.path.insert(0, str(VENDOR_PLUGIN))

def load_cache():
    files = sorted(CACHE_DIR.glob("*.json"))
    print(f"[cache] {len(files)} JSONs in {CACHE_DIR}")
    # Must have 26
    if len(files) != 26:
        print(f"[warn] expected 26, got {len(files)} – may need 03_fetch")
    return files

def generate_legacy_pretty():
    from lcsc_manager.converters.footprint_converter import FootprintConverter
    tmp_root = pathlib.Path(tempfile.mkdtemp(prefix="kicad_scale_legacy_"))
    legacy_pretty = tmp_root / "legacy.pretty"
    legacy_pretty.mkdir(parents=True, exist_ok=True)
    conv = FootprintConverter()
    # Build mapping
    pkg_sanitize = lambda pkg: pkg.replace(" ", "_").replace(".", "_").replace("/", "_").replace("\\","_").replace("<","_").replace(">","_").replace(":","_").replace('"',"_")
    for jf in sorted(CACHE_DIR.glob("C*.json")):
        try:
            data = json.loads(jf.read_text())
            lcsc_id = data.get('lcsc_id') or jf.stem
            package = data.get('package','Unknown')
            easyeda_data = data.get('easyeda_data')
            if not easyeda_data:
                print(f"[skip] {jf.name} no easyeda_data")
                continue
            comp_info = {"lcsc_id": lcsc_id, "package": package}
            legacy_text = conv.convert(easyeda_data, comp_info)
            # converter's footprint name is lcsc_id + _ + sanitized package
            fp_name = conv._get_footprint_name(comp_info)
            # Ensure file name equals footprint name (KiCad pretty convention)
            out_file = legacy_pretty / f"{fp_name}.kicad_mod"
            out_file.write_text(legacy_text)
        except Exception as e:
            print(f"[error] {jf.name} {e}")
            import traceback; traceback.print_exc()
    print(f"[legacy] {len(list(legacy_pretty.glob('*.kicad_mod')))} files in {legacy_pretty}")
    return tmp_root, legacy_pretty

def upgrade_pretty(legacy_pretty: pathlib.Path):
    # output path must NOT exist before
    tmp_root = pathlib.Path(tempfile.mkdtemp(prefix="kicad_scale_new_"))
    output_dir = tmp_root / "new.pretty"
    # Ensure not exists
    if output_dir.exists():
        shutil.rmtree(output_dir)
    cmd = [str(KICAD_CLI), "fp", "upgrade", str(legacy_pretty), "--output", str(output_dir), "--force"]
    print(f"[fp-upgrade] {' '.join(cmd)}")
    res = subprocess.run(cmd, capture_output=True, text=True)
    print(res.stdout[-3000:])
    print(res.stderr[-3000:])
    upgraded = list(output_dir.rglob("*.kicad_mod")) if output_dir.exists() else []
    print(f"[fp-upgrade] upgraded {len(upgraded)} files")
    return tmp_root, output_dir, upgraded

def eta_remap_upgraded(txt: str) -> str:
    """Remap ETA9741 pads based on at coordinates to spec numbers"""
    # Mapping physical positions (approx) to spec pin numbers
    # From C7465513 footprint: pads at 1.905,2.682 etc. Spec: 1 CE,2 NTC,3 ISET,6 BAT,7 GND(EP),9 EN,12 VOUT,15 SW,20 VIN
    table = {
        (-1.905, 2.682): "1",
        (-0.635, 2.682): "2",
        (0.635, 2.682): "3",
        (1.905, 2.682): "20",
        (1.905, -2.682): "15",
        (0.635, -2.682): "12",
        (-0.635, -2.682): "9",
        (-1.905, -2.682): "6",
        (0.0, 0.0): "7",
    }
    lines = txt.splitlines()
    new_lines=[]
    i=0
    while i < len(lines):
        line=lines[i]
        m = re.match(r'\s*\(pad\s+"([^"]+)"\s+smd\s+(oval|rect|roundrect|circle)', line)
        if m:
            old_num=m.group(1)
            # Next non-empty line should contain (at
            j=i+1
            while j < len(lines) and lines[j].strip()=="":
                j+=1
            if j < len(lines):
                at_line=lines[j]
                m_at=re.search(r'\(at\s+([-\d\.]+)\s+([-\d\.]+)', at_line)
                if m_at:
                    ax=float(m_at.group(1)); ay=float(m_at.group(2))
                    for (cx,cy), nn in table.items():
                        if abs(ax-cx)<0.08 and abs(ay-cy)<0.08:
                            if nn!=old_num:
                                line = line.replace(f'"{old_num}"', f'"{nn}"', 1)
                            break
        new_lines.append(line)
        i+=1
    return "\n".join(new_lines)

def copy_to_libs(output_dir: pathlib.Path, upgraded_files):
    LIBS_DIR.mkdir(parents=True, exist_ok=True)
    # Clear old LCSC_*.kicad_mod to ensure clean
    for old in LIBS_DIR.glob("LCSC_*.kicad_mod"):
        old.unlink()
        print(f"[clean] removed old {old.name}")
    for src in upgraded_files:
        txt=src.read_text()
        # Determine LCSC code
        m_code=re.search(r'LCSC_(C\d+)', src.stem)
        code=m_code.group(1) if m_code else src.stem
        # ETA special
        if code=="C7465513":
            txt=eta_remap_upgraded(txt)
        # Ensure internal footprint name matches filename (KiCad already does this: filename == footprint name)
        # But filename is long like LCSC_C1002_L0603 – that's okay
        dst=LIBS_DIR / f"{src.stem}.kicad_mod"
        dst.write_text(txt)
        # Also create short alias LCSC_<code>.kicad_mod if file is long name, for backward compat with old PCB references
        short=LIBS_DIR / f"LCSC_{code}.kicad_mod"
        if dst.name != short.name:
            # If short doesn't exist, copy ; if exists but different, keep long version as short too? 
            # For simplicity, if short alias missing, copy long to short as well (keeping internal name as long? Actually short alias file should have footprint name == short file base)
            # We'll keep both: dst is long, short is copy with internal name overridden to short? 
            # To avoid confusion, create short alias with same content but footprint name changed to long? Keep long name inside short file as well so scale_libs:LCSC_Cxxxx_Lyyyy resolves
            # So just copy content
            if not short.exists():
                short.write_text(txt)
        print(f"[copy] {src.name} -> {dst.name}")

def ensure_pogo():
    pogo=LIBS_DIR/"Pogo_Pad_6mm.kicad_mod"
    content='(footprint "Pogo_Pad_6mm"\n\t(version 20260206)\n\t(generator "pcbnew")\n\t(generator_version "10.0")\n\t(layer "F.Cu")\n\t(descr "Pogo pad 6mm SMD ENIG circle 6mm F.Cu/F.Paste/F.Mask clearance 0.5 ENIG")\n\t(tags "pogo pad")\n\t(property "Reference" "E*"\n\t\t(at 0 3.5 0)\n\t\t(layer "F.SilkS")\n\t\t(uuid "11111111-1111-4a11-b111-111111111111")\n\t\t(effects (font (size 1 1) (thickness 0.15)))\n\t)\n\t(property "Value" "Pogo_Pad_6mm"\n\t\t(at 0 -3.5 0)\n\t\t(layer "F.Fab")\n\t\t(uuid "22222222-2222-4b22-b222-222222222222")\n\t\t(effects (font (size 1 1) (thickness 0.15)))\n\t)\n\t(attr smd)\n\t(fp_circle (center 0 0) (end 3 0) (stroke (width 0.12) (type default)) (layer "F.SilkS") (uuid "33333333-3333-4c33-b333-333333333333"))\n\t(fp_circle (center 0 0) (end 3.2 0) (stroke (width 0.05) (type default)) (layer "F.CrtYd") (uuid "44444444-4444-4d44-b444-444444444444"))\n\t(pad "1" smd circle (at 0 0) (size 6 6) (layers "F.Cu" "F.Paste" "F.Mask") (clearance 0.5) (uuid "55555555-5555-4e55-b555-555555555555"))\n\t(embedded_fonts no)\n)\n'
    pogo.write_text(content)
    print(f"[pogo] {pogo}")

def fmt_pin(x,y,angle,elec,name,num):
    return (
        f'\t\t\t(pin {elec} line\n'
        f'\t\t\t\t(at {x} {y} {angle})\n'
        f'\t\t\t\t(length 2.54)\n'
        f'\t\t\t\t(name "{name}"\n'
        f'\t\t\t\t\t(effects (font (size 0.7 0.7)))\n'
        f'\t\t\t\t)\n'
        f'\t\t\t\t(number "{num}"\n'
        f'\t\t\t\t\t(effects (font (size 0.7 0.7)))\n'
        f'\t\t\t\t)\n'
        f'\t\t\t)'
    )

def make_symbol(sym_name, ref, value, footprint, descr, rs, re_, pins):
    lines=[f'\t(symbol "{sym_name}"',
           f'\t\t(property "Reference" "{ref}" (at 0 10.16 0) (show_name no) (do_not_autoplace) (effects (font (size 1.27 1.27))))',
           f'\t\t(property "Value" "{value}" (at 0 -15.24 0) (show_name no) (do_not_autoplace) (effects (font (size 1.27 1.27))))',
           f'\t\t(property "Footprint" "{footprint}" (at 0 0 0) (show_name no) (do_not_autoplace) (hide yes) (effects (font (size 1.27 1.27))))',
           f'\t\t(property "Datasheet" "" (at 0 0 0) (show_name no) (do_not_autoplace) (hide yes) (effects (font (size 1.27 1.27))))',
           f'\t\t(property "Description" "{descr}" (at 0 0 0) (show_name no) (do_not_autoplace) (hide yes) (effects (font (size 1.27 1.27))))',
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

def generate_symbols():
    # Find long names for footprints
    # Search libs for each code
    def find_fp(code):
        # Prefer long name file
        candidates=list(LIBS_DIR.glob(f"LCSC_{code}*.kicad_mod"))
        if not candidates:
            return f"LCSC_{code}"
        # Pick first, read internal name
        txt=candidates[0].read_text()
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
    # NRF uses alphanumeric pad numbers matching AQFN73 footprint
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
    symbols=[]
    symbols.append(make_symbol("AFE4300PNR","U","AFE4300PNR",f"scale_libs:{afe_fp}","AFE4300 weight and BIA AFE",(-10.16,15.24),(10.16,-15.24),afe))
    symbols.append(make_symbol("ETA9741E8A","U","ETA9741E8A",f"scale_libs:{eta_fp}","ETA9741 power management",(-5.08,5.08),(5.08,-5.08),eta))
    symbols.append(make_symbol("NRF52840-QIAA-R","U","NRF52840-QIAA-R",f"scale_libs:{nrf_fp}","nRF52840 MCU",(-10.16,17.78),(10.16,-17.78),nrf))

    def make_pogo():
        return '\t(symbol "Pogo_Pad_6mm"\n\t\t(property "Reference" "E" (at 0 2.54 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Value" "Pogo_Pad_6mm" (at 0 -2.54 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Footprint" "scale_libs:Pogo_Pad_6mm" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))\n\t\t(symbol "Pogo_Pad_6mm_0_1"\n\t\t\t(circle (center 0 0) (radius 1.27) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t)\n\t\t(symbol "Pogo_Pad_6mm_1_1"\n'+fmt_pin(0,-2.54,90,"passive","1","1")+'\n\t\t)\n\t\t(embedded_fonts no)\n\t)'
    symbols.append(make_pogo())
    def make_pesd():
        return '\t(symbol "PESD5V0S1BL"\n\t\t(property "Reference" "D" (at 1.27 1.27 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Value" "PESD5V0S1BL" (at 1.27 -1.27 0) (effects (font (size 1.27 1.27))))\n\t\t(property "Footprint" "scale_libs:'+pesd_fp+'" (at 0 0 0) (hide yes) (effects (font (size 1.27 1.27))))\n\t\t(symbol "PESD5V0S1BL_0_1"\n\t\t\t(polyline (pts (xy -1.27 0) (xy 1.27 0)) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t\t(polyline (pts (xy 0 0) (xy 0 -1.27) (xy -1.27 -0.635) (xy 1.27 -0.635) (xy 0 -1.27)) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t)\n\t\t(symbol "PESD5V0S1BL_1_1"\n'+fmt_pin(0,2.54,270,"passive","1","1")+'\n'+fmt_pin(0,-2.54,90,"passive","2","2")+'\n\t\t)\n\t\t(embedded_fonts no)\n\t)'
    symbols.append(make_pesd())

    def make_passive(name, ref, rect=True):
        if rect:
            rect_s='\t\t\t(rectangle (start -1.016 -2.54) (end 1.016 2.54) (stroke (width 0.254) (type default)) (fill (type none)))'
        else:
            rect_s='\t\t\t(rectangle (start -1.397 -0.127) (end 1.397 0.127) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t\t(rectangle (start -1.397 -2.413) (end 1.397 -2.159) (stroke (width 0.254) (type default)) (fill (type none)))\n\t\t\t(polyline (pts (xy 0 0) (xy 0 -2.032)) (stroke (width 0.2032) (type default)) (fill (type none)))'
        return f'\t(symbol "{name}"\n\t\t(property "Reference" "{ref}" (at 2.032 0 90) (effects (font (size 1.27 1.27))))\n\t\t(property "Value" "{ref}" (at 0 0 90) (effects (font (size 1.27 1.27))))\n\t\t(property "Footprint" "" (at -1.778 0 90) (hide yes) (effects (font (size 1.27 1.27))))\n\t\t(symbol "{name}_0_1"\n{rect_s}\n\t\t)\n\t\t(symbol "{name}_1_1"\n{fmt_pin(0,3.81,270,"passive","","1")}\n{fmt_pin(0,-3.81,90,"passive","","2")}\n\t\t)\n\t\t(embedded_fonts no)\n\t)'

    symbols.append(make_passive("R_Small","R",True))
    symbols.append(make_passive("C_Small","C",False))
    symbols.append(make_passive("L_Small","L",True))

    def find_fp_code(code):
        cands=list(LIBS_DIR.glob(f"LCSC_{code}*.kicad_mod"))
        if cands:
            txt=cands[0].read_text()
            m=re.search(r'\(footprint\s+"([^"]+)"', txt)
            return m.group(1) if m else f"LCSC_{code}"
        return f"LCSC_{code}"

    usb = [
        (-7.62,5.08,0,"passive","VBUS","1"),
        (7.62,3.81,180,"power_in","GND","2"),
        (-7.62,2.54,0,"bidirectional","D+","3"),
        (7.62,1.27,180,"bidirectional","D-","4"),
        (-7.62,0.0,0,"passive","CC1","5"),
        (7.62,-1.27,180,"passive","CC2","6"),
        (-7.62,-2.54,0,"passive","SBU1","7"),
        (7.62,-3.81,180,"passive","SBU2","8"),
    ]
    symbols.append(make_symbol("TYPE-C-31-M-12","J","TYPE-C-31-M-12",f"scale_libs:{find_fp_code('C165948')}","USB-C",(-5.08,6.35),(5.08,-6.35),usb))
    lis=[
        (-7.62,3.81,0,"power_in","VDD","1"),
        (-7.62,1.27,0,"bidirectional","SDA","2"),
        (-7.62,-1.27,0,"input","SCL","3"),
        (-7.62,-3.81,0,"output","INT1","4"),
        (-7.62,-6.35,0,"power_in","GND","5"),
    ]
    symbols.append(make_symbol("LIS2DH12TR","U","LIS2DH12TR",f"scale_libs:{find_fp_code('C110926')}","Accel",(-5.08,5.08),(5.08,-5.08),lis))
    sw=[
        (-5.08,0,0,"passive","1","1"),
        (5.08,0,180,"passive","2","2"),
    ]
    symbols.append(make_symbol("TL3342","SW","TL3342",f"scale_libs:{find_fp_code('C318884')}","Tactile",(-2.54,1.27),(2.54,-1.27),sw))

    content='(kicad_symbol_lib\n\t(version 20251024)\n\t(generator "kicad_symbol_editor")\n\t(generator_version "10.0")\n'+"\n".join(symbols)+"\n)\n"
    # Safety checks per plan
    assert "{'LCSC':" not in content
    assert "[(11," not in content
    fp_count=content.count('Footprint')
    # expect one per outer symbol
    assert fp_count==len(symbols), f"fp prop mismatch {fp_count} vs {len(symbols)}"
    COMPACT_SYM.write_text(content)
    print(f"[gen] {COMPACT_SYM} outer={len(symbols)} pins={content.count('(pin ')} size={len(content)}")

def write_tables():
    FP_TABLE.write_text('(fp_lib_table\n\t(version 7)\n\t(lib (name "scale_libs") (type "KiCad") (uri "${KIPRJMOD}/libs") (options "") (descr "LCSC sourced footprints"))\n)\n')
    SYM_TABLE.write_text('(sym_lib_table\n\t(version 7)\n\t(lib (name "scale_libs") (type "KiCad") (uri "${KIPRJMOD}/libs/AFE4300PNR.kicad_sym") (options "") (descr ""))\n)\n')
    print(f"[gen] fp and sym lib tables v7")

def main():
    load_cache()
    tmp_legacy_root, legacy_pretty = generate_legacy_pretty()
    try:
        tmp_new_root, output_dir, upgraded = upgrade_pretty(legacy_pretty)
        try:
            copy_to_libs(output_dir, upgraded)
        finally:
            shutil.rmtree(tmp_new_root, ignore_errors=True)
    finally:
        shutil.rmtree(tmp_legacy_root, ignore_errors=True)
    ensure_pogo()
    generate_symbols()
    write_tables()
    print("[verify] libs")
    for p in sorted(LIBS_DIR.glob("*.kicad_mod")):
        txt=p.read_text()
        ok = txt.count('(footprint "')==1 and txt.count('(version 20260206)')==1 and '(module' not in txt
                fp_cnt = txt.count('(footprint "')
                ver_cnt = txt.count('(version 20260206)')
                print(f"  {p.name}: OK={ok} fp={fp_cnt} ver={ver_cnt}")

if __name__ == "__main__":
    main()