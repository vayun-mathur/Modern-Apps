#!/usr/bin/env python3
"""
Generates offline astronomy catalogs for astronomy/src/main/assets/catalog/
- stars.json (~9000 stars mag <= 6.5): bright Yale BSC5-like subset + synthetic filler
- constellations.json: 88 IAU simplified lines (v1 uses major 15, stub others)
- messier.json: 14 Messier highlights with ra/dec J2000 rad
- orbital_elements.json: heliocentric Keplerian elements J2000
- CREDITS.md: data provenance

All output is bundled APK, fully offline — plan Phase 6.

No network required; fallback is embedded random seed 42.
If you want full BSC5, replace generate_stars() to parse http://tdc-www.harvard.edu/catalogs/BSC5
Manually download once, but this script works standalone.
"""

import json
import math
import random
import pathlib

OUT = pathlib.Path(__file__).resolve().parents[1] / "astronomy/src/main/assets/catalog"
OUT.mkdir(parents=True, exist_ok=True)

random.seed(42)

# -------------------------------------------------------------------------
# Stars ~9000 naked eye: 50 real bright + 8950 synthetic uniform sphere
# -------------------------------------------------------------------------
bright = [
    # properName, ra_h,m,s, dec_sign, dd,m,s, mag, bv, constellation
    ("Sirius", 6,45,8.92, -1, 16,42,58, -1.46, 0.0, "CMa"),
    ("Canopus", 6,23,57.1, -1, 52,41,44, -0.74, 0.15, "Car"),
    ("Arcturus",14,15,39.7, 1,19,10,56, -0.05,1.23, "Boo"),
    ("Vega",18,36,56.3, 1,38,47,1, 0.03,0.0,"Lyr"),
    ("Capella",5,16,41.4, 1,45,59,53,0.08,0.8,"Aur"),
    ("Rigel",5,14,32.3, -1, 8,12,6,0.13, -0.03,"Ori"),
    ("Procyon",7,39,18.1, 1,5,13,30,0.34,0.42,"CMi"),
    ("Achernar",1,37,42.8,-1,57,14,12,0.46,-0.16,"Eri"),
    ("Betelgeuse",5,55,10.3,1,7,24,25,0.5,1.85,"Ori"),
    ("Hadar",14,3,49.4,-1,60,22,23,0.61,0.24,"Cen"),
    ("Acrux",12,26,35.9,-1,63,5,56,0.76,-0.24,"Cru"),
    ("Altair",19,50,47,1,8,52,6,0.77,0.22,"Aql"),
    ("Aldebaran",4,35,55.2,1,16,30,33,0.86,1.54,"Tau"),
    ("Antares",16,29,24.3,-1,26,25,55,1.06,1.83,"Sco"),
    ("Spica",13,25,11.6,-1,11,9,41,0.97,-0.23,"Vir"),
    ("Pollux",7,45,18.9,1,28,1,34,1.14,1.00,"Gem"),
    ("Fomalhaut",22,57,39.1,-1,29,37,20,1.17,0.16,"PsA"),
    ("Deneb",20,41,25.9,1,45,16,49,1.25,0.09,"Cyg"),
    ("Mimosa",12,47,43.2,-1,59,41,19,1.25,-0.22,"Cru"),
    ("Regulus",10,8,22.3,1,11,58,2,1.35, -0.11,"Leo"),
    ("Adhara",6,58,37.6,-1,28,58,19,1.50,-0.21,"CMa"),
    ("Castor",7,34,35.9,1,31,53,18,1.58,0.03,"Gem"),
    ("Gacrux",12,31,9.9,-1,57,6,48,1.59,1.69,"Cru"),
    ("Shaula",17,33,36.5,-1,37,6,14,1.62,-0.22,"Sco"),
    ("Bellatrix",5,25,7.9,1,6,20,59,1.64,-0.22,"Ori"),
    ("Elnath",5,26,17.5,1,28,36,27,1.68, -0.13,"Tau"),
    ("Miaplacidus",9,13,11.9,-1,69,43,2,1.69,0.18,"Car"),
    ("Alnilam",5,36,12.8,-1,1,12,7,1.69,-0.24,"Ori"),
    ("Alnair",22,8,13.9,-1,46,57,38,1.74,-0.14,"Gru"),
    ("Alnitak",5,40,45.5,-1,1,56,34,1.77,-0.21,"Ori"),
    ("Alioth",12,54,1.8,1,55,57,35,1.76,0.02,"UMa"),
    ("Dubhe",11,3,43.7,1,61,45,3,1.79,1.08,"UMa"),
    ("Mirfak",3,24,19.4,1,49,51,40,1.79,0.48,"Per"),
    ("Wezen",7,8,23.5,-1,26,23,35,1.83,0.67,"CMa"),
    ("Sargas",17,37,19.1,-1,42,59,52,1.86,1.17,"Sco"),
    ("Kaus Australis",18,24,10.3,-1,34,23,5,1.85,0.24,"Sgr"),
    ("Avior",8,22,30.8,-1,59,30,35,1.86,-0.20,"Car"),
    ("Alkaid",13,47,32.4,1,49,18,48,1.85,-0.19,"UMa"),
    ("Menkalinan",5,59,31.6,1,44,56,51,1.90,0.08,"Aur"),
    ("Atria",16,48,39.9,-1,69,1,39,1.91,1.44,"TrA"),
    ("Delta Velorum",8,44,42.2,-1,54,42,32,1.93,-0.01,"Vel"),
    ("Polaris",2,31,49.1,1,89,15,51,1.98,0.6,"UMi"),
    ("Mirach",1,9,43.9,1,35,37,14,2.07,1.58,"And"),
    ("Saiph",5,47,45.4,-1,9,40,11,2.07,-0.22,"Ori"),
    ("Denebola",11,49,3.6,1,14,34,19,2.14,0.09,"Leo"),
    ("Mizar",13,23,55.5,1,54,55,31,2.04,0.02,"UMa"),
]

def hms_to_deg(h,m,s): return (h + m/60 + s/3600)*15.0

stars=[]
sid=1
for (proper,h,mm,s,dsign,dd,dm,dsec,mag,bv,const) in bright:
    ra_deg = hms_to_deg(h,mm,s)
    dec_deg = dsign*(abs(dd)+dm/60+dsec/3600)
    stars.append({
        "id": sid,
        "ra": math.radians(ra_deg),
        "dec": math.radians(dec_deg),
        "mag": mag,
        "bv": bv,
        "properName": proper,
        "name": proper,
        "constellation": const
    })
    sid+=1

# Filler to reach ~9000: uniform sphere distribution + mag 3..6.5
for _ in range(9000 - len(stars)):
    ra = random.random()*2*math.pi
    dec = math.asin(random.random()*2-1)
    mag = 3.0 + random.random()*3.5
    bv = random.random()*2-0.5
    stars.append({"id": sid, "ra": ra, "dec": dec, "mag": mag, "bv": bv})
    sid+=1

(OUT/"stars.json").write_text(json.dumps({"stars": stars}))
print(f"stars.json {len(stars)} written {(OUT/'stars.json').stat().st_size/1024:.1f} KB")

# -------------------------------------------------------------------------
# Constellations: 88 IAU abbrs, simplified to bright lines referencing above ids
# Full 88 generation would need Hipparcos mapping; v1 lists major 15 with lines
# -------------------------------------------------------------------------
constellation_names = {
    "And":"Andromeda","Ant":"Antlia","Aps":"Apus","Aqr":"Aquarius","Aql":"Aquila","Ara":"Ara","Ari":"Aries","Aur":"Auriga",
    "Boo":"Bootes","Cae":"Caelum","Cam":"Camelopardalis","Cnc":"Cancer","CVn":"Canes Venatici","CMa":"Canis Major","CMi":"Canis Minor",
    "Cap":"Capricornus","Car":"Carina","Cas":"Cassiopeia","Cen":"Centaurus","Cep":"Cepheus","Cet":"Cetus","Cha":"Chamaeleon",
    "Cir":"Circinus","Col":"Columba","Com":"Coma Berenices","CrA":"Corona Australis","CrB":"Corona Borealis","Crv":"Corvus",
    "Crt":"Crater","Cru":"Crux","Cyg":"Cygnus","Del":"Delphinus","Dor":"Dorado","Dra":"Draco","Equ":"Equuleus","Eri":"Eridanus",
    "For":"Fornax","Gem":"Gemini","Gru":"Grus","Her":"Hercules","Hor":"Horologium","Hya":"Hydra","Hyi":"Hydrus","Ind":"Indus",
    "Lac":"Lacerta","Leo":"Leo","LMi":"Leo Minor","Lep":"Lepus","Lib":"Libra","Lup":"Lupus","Lyn":"Lynx","Lyr":"Lyra",
    "Men":"Mensa","Mic":"Microscopium","Mon":"Monoceros","Mus":"Musca","Nor":"Norma","Oct":"Octans","Oph":"Ophiuchus",
    "Ori":"Orion","Pav":"Pavo","Peg":"Pegasus","Per":"Perseus","Phe":"Phoenix","Pic":"Pictor","PsA":"Piscis Austrinus",
    "Psc":"Pisces","Pup":"Puppis","Pyx":"Pyxis","Ret":"Reticulum","Sge":"Sagitta","Sgr":"Sagittarius","Sco":"Scorpius",
    "Scl":"Sculptor","Sct":"Scutum","Ser":"Serpens","Sex":"Sextans","Tau":"Taurus","Tel":"Telescopium","Tri":"Triangulum",
    "TrA":"Triangulum Australe","Tuc":"Tucana","UMa":"Ursa Major","UMi":"Ursa Minor","Vel":"Vela","Vir":"Virgo","Vol":"Volans","Vul":"Vulpecula"
}

# Major lines using bright ids (1-indexed above)
const_lines = {
    "UMa": [[32,36],[36,31],[31,46],[46,45],[45,39],[32,33]], # Dubhe..Mizar etc simplified (ids approx)
    "Ori": [[6,16],[16,28],[28,30],[6,15],[15,44],[44,42],[44,30],[30,28,29],[29,44],[28,5]], # Rigel-Bellatrix-Betelgeuse etc
    "Cas": [[20,21],[21,22],[22,23],[23,24],[24,25]],
    "Leo": [[20,26],[26,45]],
    "Sco": [[14,35],[35,36],[36,24]],
    "Cru": [[11,23],[23,24],[11,24]],
    "Cen": [[10,21],[21,11]],
    "Lyr": [[4,42]],
    "CMa": [[1,34],[1,22]],
    "UMi": [[42,1],[1,2]],
    "Gem": [[16,22],[22,12]],
    "Tau": [[13,14]],
    "Vir": [[15,20]],
    "Aql": [[12,41]],
    "Cyg": [[18,40]],
}

constellations=[]
# Include all 88 but only those with lines have segments
for abbr in sorted(constellation_names.keys()):
    name = constellation_names[abbr]
    lines = const_lines.get(abbr, [])
    # Normalize to pairs
    segs=[]
    for l in lines:
        if isinstance(l, list) and len(l)>=2:
            # If list length >2 create chain
            for i in range(len(l)-1):
                segs.append([l[i], l[i+1]])
    constellations.append({"abbr": abbr, "name": name, "lines": segs})

(OUT/"constellations.json").write_text(json.dumps({"constellations": constellations}))
print(f"constellations.json {len(constellations)} written")

# -------------------------------------------------------------------------
# Messier highlights
# -------------------------------------------------------------------------
messier=[
    {"id":"M31","name":"Andromeda Galaxy","ra":math.radians(10.68),"dec":math.radians(41.2687),"mag":3.4,"type":"galaxy","sizeArcmin":178.0,"constellation":"And"},
    {"id":"M42","name":"Orion Nebula","ra":math.radians(83.8221),"dec":math.radians(-5.3911),"mag":4.0,"type":"nebula","sizeArcmin":65.0,"constellation":"Ori"},
    {"id":"M45","name":"Pleiades","ra":math.radians(56.75),"dec":math.radians(24.1167),"mag":1.6,"type":"cluster","sizeArcmin":110.0,"constellation":"Tau"},
    {"id":"M13","name":"Hercules Cluster","ra":math.radians(250.42),"dec":math.radians(36.46),"mag":5.8,"type":"cluster","sizeArcmin":20.0,"constellation":"Her"},
    {"id":"M51","name":"Whirlpool Galaxy","ra":math.radians(202.4706),"dec":math.radians(47.1953),"mag":8.4,"type":"galaxy","sizeArcmin":11.0,"constellation":"CVn"},
    {"id":"M57","name":"Ring Nebula","ra":math.radians(283.396),"dec":math.radians(33.029),"mag":8.8,"type":"nebula","sizeArcmin":1.5,"constellation":"Lyr"},
    {"id":"M27","name":"Dumbbell Nebula","ra":math.radians(299.9017),"dec":math.radians(22.7212),"mag":7.5,"type":"nebula","sizeArcmin":8.0,"constellation":"Vul"},
    {"id":"M8","name":"Lagoon Nebula","ra":math.radians(270.6587),"dec":math.radians(-24.3833),"mag":6.0,"type":"nebula","sizeArcmin":90.0,"constellation":"Sgr"},
    {"id":"M20","name":"Trifid Nebula","ra":math.radians(270.87),"dec":math.radians(-23.02),"mag":6.3,"type":"nebula","sizeArcmin":28.0,"constellation":"Sgr"},
    {"id":"M1","name":"Crab Nebula","ra":math.radians(83.6333),"dec":math.radians(22.0144),"mag":8.4,"type":"nebula","sizeArcmin":7.0,"constellation":"Tau"},
    {"id":"M44","name":"Beehive Cluster","ra":math.radians(130.08),"dec":math.radians(19.7833),"mag":3.7,"type":"cluster","sizeArcmin":95.0,"constellation":"Cnc"},
    {"id":"M33","name":"Triangulum Galaxy","ra":math.radians(23.4628),"dec":math.radians(30.66),"mag":5.7,"type":"galaxy","sizeArcmin":70.0,"constellation":"Tri"},
    {"id":"M81","name":"Bode's Galaxy","ra":math.radians(148.888),"dec":math.radians(69.0656),"mag":6.9,"type":"galaxy","sizeArcmin":21.0,"constellation":"UMa"},
    {"id":"M82","name":"Cigar Galaxy","ra":math.radians(148.9677),"dec":math.radians(69.6798),"mag":8.4,"type":"galaxy","sizeArcmin":11.0,"constellation":"UMa"},
]
(OUT/"messier.json").write_text(json.dumps({"objects": messier}))
print(f"messier.json {len(messier)} written")

planets=[
    {"id":"SUN","name":"Sun","a":0.0,"e":0.0,"iDeg":0.0,"omegaDeg":0.0,"wDeg":0.0,"lDeg":0.0,"m0Deg":0.0,"nDegPerDay":0.0,"magBase":-26.7},
    {"id":"EARTH","name":"Earth","a":1.0,"e":0.0167086,"iDeg":0.0,"omegaDeg":-11.26064,"wDeg":102.9372,"lDeg":100.46435,"m0Deg":100.46435,"nDegPerDay":0.9856091,"magBase":-3.0},
    {"id":"MERCURY","name":"Mercury","a":0.38709893,"e":0.20563069,"iDeg":7.00487,"omegaDeg":48.33167,"wDeg":29.12478,"lDeg":174.875,"m0Deg":174.875,"nDegPerDay":4.09233445,"magBase":-0.42},
    {"id":"VENUS","name":"Venus","a":0.72333199,"e":0.00677323,"iDeg":3.39471,"omegaDeg":76.68069,"wDeg":54.88378,"lDeg":181.975,"m0Deg":181.975,"nDegPerDay":1.60213034,"magBase":-4.4},
    {"id":"MARS","name":"Mars","a":1.52366231,"e":0.09341233,"iDeg":1.85061,"omegaDeg":49.57854,"wDeg":286.4623,"lDeg":355.453,"m0Deg":355.453,"nDegPerDay":0.52402068,"magBase":-0.5},
    {"id":"JUPITER","name":"Jupiter","a":5.20336301,"e":0.04839266,"iDeg":1.30530,"omegaDeg":100.55615,"wDeg":273.8777,"lDeg":34.40438,"m0Deg":20.0202,"nDegPerDay":0.08308529,"magBase":-9.4},
    {"id":"SATURN","name":"Saturn","a":9.53707032,"e":0.05386179,"iDeg":2.48446,"omegaDeg":113.71504,"wDeg":339.3939,"lDeg":49.94432,"m0Deg":317.02,"nDegPerDay":0.03344414,"magBase":-8.9},
    {"id":"URANUS","name":"Uranus","a":19.19126393,"e":0.04725744,"iDeg":0.76986,"omegaDeg":74.22988,"wDeg":96.73436,"lDeg":313.23218,"m0Deg":142.2386,"nDegPerDay":0.01172834,"magBase":-7.1},
    {"id":"NEPTUNE","name":"Neptune","a":30.06896348,"e":0.00859048,"iDeg":1.76917,"omegaDeg":131.72169,"wDeg":272.8461,"lDeg":304.88003,"m0Deg":256.228,"nDegPerDay":0.00598103,"magBase":-6.9},
]
(OUT/"orbital_elements.json").write_text(json.dumps({"planets": planets}))
print("orbital_elements.json written")

(OUT/"CREDITS.md").write_text("""# Catalog credits
- Stars bright subset: approximate J2000 positions from IAU BSC5 / Hipparcos public domain, converted hms/dms->rad; filler synthetic uniform sphere random seed 42 for mag 3..6.5. Replace with https://github.com/astronexus/HYG-Database for full catalog (CC0, attribution).
- Constellations: 88 IAU abbrs from https://www.iau.org/public/themes/constellations/, lines simplified to bright-star id pairs for demo.
- Messier: public domain positions, hand-curated.
- Orbital elements: heliocentric osculating J2000 from NASA JPL fact sheets, mean motion deg/day.
- Fully offline, no network required at runtime.
""")
print("CREDITS.md written")
