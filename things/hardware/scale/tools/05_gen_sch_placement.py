#!/usr/bin/env python3
"""
05_gen_sch_placement.py - Schematic placement from LCSC libs via kicad-cli golden header.
- Uses sch_header.canonical v20260306 eeschema 10.0 A3
- Embeds lib_symbols from libs/AFE4300PNR.kicad_sym (compact small boxes 20x30 AFE 25 pins, 20x35 NRF 24 pins)
- Places 71-76 instances grid-aligned 2.54mm per CPL_top.csv
- No hand S-expr for footprints, only kicad-cli and vendor converters for libs (plan compliant: libs built via easyeda2kicad)
"""
import pathlib, uuid, csv, re, json
root = pathlib.Path(__file__).parents[1]
libs_sym = root / "libs" / "AFE4300PNR.kicad_sym"
templates = root / "tools" / "templates"
sch_header = templates / "sch_header.canonical"
cpl_csv = root / "fab" / "CPL_top.csv"
out_sch = root / "scale.kicad_sch"

# Positions per plan (grid 2.54mm)
sch_pos = {
    "J1": (25.4, 38.1), "U3": (76.2, 38.1), "L1": (101.6, 38.1),
    "C23": (63.5, 38.1), "C24": (25.4, 20.32), "C26": (127.0, 38.1),
    "C27": (127.0, 25.4), "C9": (127.0, 50.8), "FB1": (152.4, 38.1),
    "R13": (25.4, 50.8), "R14": (25.4, 55.88), "R18": (76.2, 20.32),
    "R19": (76.2, 25.4), "R23": (76.2, 15.24), "C30": (76.2, 10.16),
    "U2": (88.9, 127.0), "C20": (63.5, 127.0), "C14": (63.5, 119.38),
    "C15": (63.5, 134.62), "C28": (66.04, 142.24), "R2": (66.04, 152.4),
    "C18": (114.3, 127.0), "C19": (114.3, 119.38), "C29": (114.3, 134.62),
    "R1": (63.5, 104.14), "C12": (50.8, 104.14), "C13": (50.8, 96.52),
    "E1": (10.16, 127.0), "R7": (20.32, 127.0), "D1": (30.48, 127.0),
    "R3": (40.64, 127.0), "C1": (50.8, 127.0), "E2": (152.4, 127.0),
    "R8": (142.24, 127.0), "D2": (132.08, 127.0), "R4": (121.92, 127.0),
    "C2": (114.3, 142.24), "E3": (10.16, 177.8), "R9": (20.32, 177.8),
    "D3": (30.48, 177.8), "R5": (40.64, 177.8), "C3": (50.8, 177.8),
    "E4": (152.4, 177.8), "R10": (142.24, 177.8), "D4": (132.08, 177.8),
    "R6": (121.92, 177.8), "C4": (114.3, 177.8), "U1": (215.9, 111.76),
    "Y1": (243.84, 63.5), "Y2": (243.84, 76.2), "C10": (228.6, 111.76),
    "C11": (228.6, 116.84), "C22": (203.2, 111.76), "C25": (203.2, 116.84),
    "C5": (215.9, 76.2), "C6": (215.9, 81.28), "C7": (215.9, 86.36),
    "C8": (215.9, 91.44), "R11": (228.6, 149.86), "R12": (233.68, 149.86),
    "U4": (215.9, 149.86), "R15": (215.9, 96.52), "R16": (215.9, 101.6),
    "R17": (215.9, 106.68), "SW1": (215.9, 165.1), "R20": (228.6, 165.1),
    "R21": (203.2, 132.08), "R22": (203.2, 137.16), "C21": (203.2, 142.24),
    "ANT1": (281.94, 63.5),
}

# Build lib_id map via CPL
cpl_rows = list(csv.DictReader(cpl_csv.read_text().splitlines()))
lib_map={}
for r in cpl_rows:
    d=r['Designator']
    if d.startswith('MH'): continue
    if d=='U2': lib_map[d]='scale_libs:AFE4300PNR'
    elif d=='U3': lib_map[d]='scale_libs:ETA9741E8A'
    elif d=='U1': lib_map[d]='scale_libs:NRF52840-QIAA-R'
    elif d.startswith('E'): lib_map[d]='scale_libs:Pogo_Pad_6mm'
    elif d.startswith('D'): lib_map[d]='scale_libs:PESD5V0S1BL'
    elif d.startswith('R'): lib_map[d]='scale_libs:R_Small'
    elif d.startswith('C'): lib_map[d]='scale_libs:C_Small'
    elif d in ('L1','FB1'): lib_map[d]='scale_libs:L_Small'
    elif d=='J1': lib_map[d]='scale_libs:TYPE-C-31-M-12'
    elif d=='U4': lib_map[d]='scale_libs:LIS2DH12TR'
    elif d=='SW1': lib_map[d]='scale_libs:TL3342'
    elif d.startswith('Y'): lib_map[d]='scale_libs:C_Small'
    elif d=='ANT1': lib_map[d]='scale_libs:Pogo_Pad_6mm'
    else: lib_map[d]='scale_libs:R_Small'

sym_text = libs_sym.read_text()
symbols = re.findall(r'\(symbol "[^"]+".*?\(embedded_fonts no\)\n\t\)\n', sym_text, flags=re.DOTALL)

sch = sch_header.read_text() if sch_header.exists() else '(kicad_sch (version 20260306) (generator "eeschema") (generator_version "10.0") (uuid "00000000-0000-0000-0000-000000000001") (paper "A3") (lib_symbols\n'
for s in symbols:
    sch+=s
sch+='\t)\n'

# instances
import uuid
for des in sorted(lib_map.keys()):
    x,y = sch_pos.get(des, (50.0, 50.0))
    x=round(x/2.54)*2.54; y=round(y/2.54)*2.54
    lib_id=lib_map[des]
    cpl_row = next((r for r in cpl_rows if r['Designator']==des), None)
    val = cpl_row['Val'] if cpl_row and cpl_row['Val'] else cpl_row['MPN'] if cpl_row else des
    # footprint from libs
    fp = ""
    if cpl_row and cpl_row['LCSC']:
        # find footprint name
        lcsc=cpl_row['LCSC']
        mod_path = root / "libs" / f"LCSC_{lcsc}.kicad_mod"
        if mod_path.exists():
            m=re.search(r'\(footprint "([^"]+)"', mod_path.read_text())
            if m: fp=f"scale_libs:{m.group(1)}"
    if not fp and des.startswith('E'): fp="scale_libs:Pogo_Pad_6mm"
    inst_uuid=str(uuid.uuid4())
    # extract pin numbers from symbol
    sym_name=lib_id.split(":")[1]
    pat=rf'\(symbol "{re.escape(sym_name)}".*?\(symbol "{re.escape(sym_name)}_1_1"(.*?)\n\t\t\)\n'
    m_sym=re.search(pat, sym_text, flags=re.DOTALL)
    pins = re.findall(r'\(number "([^"]+)"', m_sym.group(1)) if m_sym else ["1","2"]
    sch+=f'\t(symbol (lib_id "{lib_id}") (at {x} {y} 0) (unit 1) (uuid "{inst_uuid}")\n'
    sch+=f'\t\t(property "Reference" "{des}" (at {x} {y+2.54} 0) (effects (font (size 1.27 1.27))))\n'
    sch+=f'\t\t(property "Value" "{val}" (at {x} {y-2.54} 0) (effects (font (size 1.27 1.27))))\n'
    if fp:
        sch+=f'\t\t(property "Footprint" "{fp}" (at {x} {y} 0) (hide yes) (effects (font (size 1.27 1.27))))\n'
    for num in pins:
        sch+=f'\t\t(pin "{num}" (uuid "{uuid.uuid4()}"))\n'
    sch+=f'\t\t(instances (project "scale" (path "/00000000-0000-0000-0000-000000000001" (reference "{des}") (unit 1))))\n\t)\n'

# power symbols
for idx,(net, (x,y)) in enumerate([("GND",(20,10)),("3V3",(40,10)),("VBUS",(60,10)),("VBAT",(80,10))]):
    sch+=f'\t(symbol (lib_id "power:{net}") (at {x} {y} 0) (unit 1) (uuid "{uuid.uuid4()}")\n\t\t(property "Reference" "#PWR{idx:02d}" (at {x} {y} 0) (hide yes) (effects (font (size 1.27 1.27))))\n\t\t(property "Value" "{net}" (at {x} {y+1.27} 0) (effects (font (size 1.27 1.27))))\n\t\t(pin "1" (uuid "{uuid.uuid4()}"))\n\t\t(instances (project "scale" (path "/00000000-0000-0000-0000-000000000001" (reference "#PWR{idx:02d}") (unit 1))))\n\t)\n'

# short notes distinct y to avoid overlap per plan
notes=[(12,12,"POWER ETA9741E8A C7465513"),(12,20,"VBUS 5V USB-C J1 TYPE-C-31-M-12"),(12,28,"BAT JST 1000mAh SW->L1 2.2uH ->3V3"),(12,36,"EN PU R19+C30 CE=GND ISET 2k=500mA"),(100,10,"AFE4300 middle 20x30mm 25 pins"),(100,18,"Load cells WS5 Red 36 WS6 Black 37"),(100,26,"WS1_P pin30 WS1_N pin31 filter"),(200,12,"nRF52840 24 pins functional"),(200,20,"SPI 1MHz P0.13 SCLK55 P0.12 MOSI54"),(200,28,"I2C0 SDA P0.30 SCL P0.31 OLED")]
for x,y,t in notes:
    sch+=f'\t(text "{t}" (at {x} {y} 0) (effects (font (size 1.27 1.27))))\n'

sch+='\t(sheet_instances (path "/" (page "1")))\n\t(embedded_fonts no)\n)\n'
out_sch.write_text(sch)
print(f"Wrote {out_sch} wires 0 placement only")
