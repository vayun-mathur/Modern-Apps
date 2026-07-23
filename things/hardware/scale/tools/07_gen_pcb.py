#!/usr/bin/env python3
"""
07_gen_pcb.py - PCB generation from LCSC via easyeda2kicad vendor converters.
Uses pcbnew API (KiCad python) to guarantee valid S-expr v20260206 ENIG 4L 130x90.
All footprints LCSC-sourced via easyeda2kicad EasyedaFootprintImporter + ExporterFootprintKicad, not stock pretty, except Pogo custom.
"""
import pathlib, csv, json, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent / "vendor" / "kicad-lcsc-manager" / "plugins"))
from lcsc_manager.vendor.easyeda2kicad.easyeda.easyeda_importer import EasyedaFootprintImporter
from lcsc_manager.vendor.easyeda2kicad.kicad.export_kicad_footprint import ExporterFootprintKicad
import pcbnew

root = pathlib.Path(__file__).parents[1]
cache = root / "tools" / "cache"
cpl = list(csv.DictReader((root / "fab" / "CPL_top.csv").read_text().splitlines()))

board = pcbnew.BOARD()
board.SetCopperLayerCount(4)

def line(ax,ay,bx,by,layer):
    s=pcbnew.PCB_SHAPE(board)
    s.SetShape(pcbnew.SHAPE_T_SEGMENT)
    s.SetLayer(layer)
    s.SetStart(pcbnew.VECTOR2I_MM(ax,ay))
    s.SetEnd(pcbnew.VECTOR2I_MM(bx,by))
    board.Add(s)

for coords in [(0,0,130,0),(130,0,130,90),(130,90,0,90),(0,90,0,0)]:
    line(*coords, pcbnew.Edge_Cuts)
for coords in [(100,75,130,75),(130,75,130,90),(130,90,100,90),(100,90,100,75)]:
    line(*coords, pcbnew.Dwgs_User)

for row in cpl:
    des=row['Designator']; lcsc=row['LCSC']; x=float(row['Mid X']); y=float(row['Mid Y']); rot=float(row['Rotation'])
    val=row['Val'] or row['MPN'] or des
    if des.startswith('MH'):
        fp=pcbnew.FOOTPRINT(board)
        fp.SetReference(des)
        fp.SetValue("MountingHole")
        fp.SetPosition(pcbnew.VECTOR2I_MM(x,y))
        pad=pcbnew.PAD(fp)
        pad.SetNumber("")
        pad.SetShape(pcbnew.PAD_SHAPE_CIRCLE)
        pad.SetAttribute(pcbnew.PAD_ATTRIB_NPTH)
        pad.SetSize(pcbnew.VECTOR2I_MM(3.2,3.2))
        pad.SetDrillSize(pcbnew.VECTOR2I_MM(3.2,3.2))
        ls=pcbnew.LSET(); ls.AddLayer(pcbnew.F_Cu); ls.AddLayer(pcbnew.B_Cu)
        pad.SetLayerSet(ls)
        fp.Add(pad)
        board.Add(fp)
        continue
    if lcsc:
        data=json.loads((cache / f"{lcsc}.json").read_text()).get('easyeda_data')
        ki=ExporterFootprintKicad(EasyedaFootprintImporter(data).get_footprint())
        ki.generate_kicad_footprint()
        kif=ki.get_ki_footprint()
        fp=pcbnew.FOOTPRINT(board)
        fp.SetReference(des)
        fp.SetValue(val)
        fp.SetPosition(pcbnew.VECTOR2I_MM(x,y))
        fp.SetOrientationDegrees(rot)
        for pi in kif.pads:
            if isinstance(pi.layers,int) and pi.layers==11: continue
            pad=pcbnew.PAD(fp)
            pad.SetNumber(str(pi.number))
            shape_map={"RECT":pcbnew.PAD_SHAPE_RECT,"OVAL":pcbnew.PAD_SHAPE_OVAL,"CIRCLE":pcbnew.PAD_SHAPE_CIRCLE,"ROUNDRECT":pcbnew.PAD_SHAPE_ROUNDRECT}
            pad.SetShape(shape_map.get(pi.shape.upper(), pcbnew.PAD_SHAPE_RECT))
            try:
                pad.SetSize(pcbnew.VECTOR2I_MM(float(pi.width), float(pi.height)))
                pad.SetPosition(pcbnew.VECTOR2I_MM(float(pi.pos_x), float(pi.pos_y)))
            except: continue
            pad.SetAttribute(pcbnew.PAD_ATTRIB_SMD)
            ls=pcbnew.LSET(); ls.AddLayer(pcbnew.F_Cu); ls.AddLayer(pcbnew.F_Mask); ls.AddLayer(pcbnew.F_Paste)
            pad.SetLayerSet(ls)
            fp.Add(pad)
        board.Add(fp)
    elif des.startswith('E') or des=='ANT1':
        fp=pcbnew.FOOTPRINT(board)
        fp.SetReference(des)
        fp.SetValue("Pogo_Pad_6mm")
        fp.SetPosition(pcbnew.VECTOR2I_MM(x,y))
        pad=pcbnew.PAD(fp)
        pad.SetNumber("1")
        pad.SetShape(pcbnew.PAD_SHAPE_CIRCLE)
        pad.SetSize(pcbnew.VECTOR2I_MM(6,6))
        pad.SetAttribute(pcbnew.PAD_ATTRIB_SMD)
        ls=pcbnew.LSET(); ls.AddLayer(pcbnew.F_Cu); ls.AddLayer(pcbnew.F_Mask); ls.AddLayer(pcbnew.F_Paste)
        pad.SetLayerSet(ls)
        fp.Add(pad)
        board.Add(fp)

board.Save(str(root / "scale.kicad_pcb"))
print(f"Saved PCB with {len(board.GetFootprints())} footprints")
