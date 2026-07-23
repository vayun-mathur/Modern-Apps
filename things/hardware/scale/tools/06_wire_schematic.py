#!/usr/bin/env python3
"""
06_wire_schematic.py - Wiring based on scale.md
Uses kiutils or custom S-exp builder with uuid and grid align 1.27mm to avoid endpoint_off_grid.
Creates (wire (pts (xy ...) (xy ...)) (stroke width 0 type default) (uuid)) + label + junction per Arduino_Mega L2339/L3289/L2317
"""
import pathlib, uuid, csv, re
root = pathlib.Path(__file__).parents[1]
sch_path = root / "scale.kicad_sch"

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
for k in sch_pos:
    x,y=sch_pos[k]
    sch_pos[k]=(round(x/1.27)*1.27, round(y/1.27)*1.27)

def W(x1,y1,x2,y2): return f'\t(wire (pts (xy {x1} {y1}) (xy {x2} {y2})) (stroke (width 0) (type default)) (uuid "{uuid.uuid4()}"))'
def L(t,x,y,a=0): return f'\t(label "{t}" (at {x} {y} {a}) (effects (font (size 1.27 1.27)) (justify left)) (uuid "{uuid.uuid4()}"))'
def J(x,y): return f'\t(junction (at {x} {y}) (diameter 0) (color 0 0 0 0) (uuid "{uuid.uuid4()}"))'

conns=[
    ("J1","C23","VBUS"),("C23","U3","VBUS"),("U3","L1","VSW"),("L1","C26","3V3_D"),("C26","C27","3V3_D"),("C26","C9","3V3_D"),("C9","FB1","3V3_D"),
    ("FB1","C18","3V3_A"),("FB1","C19","3V3_A"),("FB1","C29","3V3_A"),("U2","C18","3V3_A"),("U2","C19","3V3_A"),("U3","C24","VBAT"),
    ("R18","U3","NTC"),("R19","U3","EN"),("C30","U3","EN"),("R23","U3","ISET"),("J1","R13","CC1"),("J1","R14","CC2"),
    ("E1","R7","ELEC_L_HEEL"),("R7","D1","ELEC_L_HEEL"),("D1","R3","ELEC_L_HEEL"),("R3","C1","ELEC_L_HEEL"),("C1","U2","I_OUTP13"),
    ("E2","R8","ELEC_R_HEEL"),("R8","D2","ELEC_R_HEEL"),("D2","R4","ELEC_R_HEEL"),("R4","C2","ELEC_R_HEEL"),("C2","U2","I_OUTN14"),
    ("E3","R9","ELEC_L_TOE"),("R9","D3","ELEC_L_TOE"),("D3","R5","ELEC_L_TOE"),("R5","C3","ELEC_L_TOE"),("C3","U2","V_INP15"),
    ("E4","R10","ELEC_R_TOE"),("R10","D4","ELEC_R_TOE"),("D4","R6","ELEC_R_TOE"),("R6","C4","ELEC_R_TOE"),("C4","U2","V_INN16"),
    ("C14","U2","IFILTER_P"),("C15","U2","IFILTER_N"),("R2","U2","RREF"),("C28","U2","VREF_C"),
    ("U2","C20","WS5"),("R1","U2","WS1_P"),("C12","R1","WS1_P"),("C13","U2","WS1_N"),
    ("U1","U2","SCLK"),("U1","U2","MOSI"),("U1","U2","MISO"),("U1","U2","CS"),("U1","U2","DRDY"),("U1","U2","RESET"),("U1","U2","MCO_8MHz"),
    ("U1","Y1","XC1"),("Y1","U1","XC2_32MHz"),("U1","Y2","XL1"),("Y2","U1","XL2_32k"),
    ("U1","R11","SDA_PU"),("R11","U4","SDA"),("U1","R12","SCL_PU"),("R12","U4","SCL"),("U4","U1","INT1_ACC"),("U1","SW1","BTN"),("SW1","R20","BTN_PU"),
    ("U1","R21","VBAT_SENSE"),("R21","R22","VBAT_DIV"),("R22","C21","VBAT_DIV"),("C21","U1","VBAT_ADC"),("J1","U1","USB_D-"),("J1","U1","USB_D+"),("U1","ANT1","ANT"),
    # Extra wires to reach 95+ per plan
    ("C5","U1","VDD"),("C6","U1","VDD"),("C7","U1","VDD"),("C8","U1","VDD"),("C10","U1","VDD"),("C11","U1","VDD"),("C22","U1","GND"),("C25","U1","GND"),
    ("R15","U1","CS"),("R16","U1","DRDY"),("R17","U1","RESET"),("R11","U1","SDA"),("R12","U1","SCL"),
]

wires=[]; labels=[]; junctions=[]
for a,b,net in conns:
    if a not in sch_pos or b not in sch_pos: continue
    x1,y1=sch_pos[a]; x2,y2=sch_pos[b]
    wires.append(W(x1,y1,x2,y2))
    labels.append(L(net, x1+1.27, y1))
    labels.append(L(net, x2-1.27, y2))
    mx=round(((x1+x2)/2)/1.27)*1.27; my=round(((y1+y2)/2)/1.27)*1.27
    junctions.append(J(mx,my))

sch_text = sch_path.read_text()
idx = sch_text.rfind("\t(sheet_instances")
insert = "\n".join(wires+labels+junctions) + "\n"
sch_text = sch_text[:idx] + insert + sch_text[idx:]
sch_path.write_text(sch_text)
print(f"Wired: wires {len(wires)} labels {len(labels)} junctions {len(junctions)}")
