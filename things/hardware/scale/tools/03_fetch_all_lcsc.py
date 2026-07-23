#!/usr/bin/env python3
"""
tools/03_fetch_all_lcsc.py
Fetches all 26 LCSC codes via LCSCAPIClient (KiCad python path) and caches to tools/cache/{code}.json
Converts using FootprintConverter but post-processes module->footprint conversion including version header via kicad-cli fp upgrade.

Uses KiCad's Python at /Applications/KiCad/KiCad.app/Contents/Frameworks/Python.framework/Versions/Current/bin/python3
If run with system python, adds vendor to sys.path.
"""
import json, sys, time, pathlib, os, tempfile, shutil, subprocess, re

BASE = pathlib.Path(__file__).parent
CACHE_DIR = BASE / "cache"
LIBS_DIR = BASE / "libs"
BOM_PATH = BASE / "bom.json"
KICAD_CLI = pathlib.Path("/Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli")
KICAD_PY = pathlib.Path("/Applications/KiCad/KiCad.app/Contents/Frameworks/Python.framework/Versions/Current/bin/python3")

VENDOR_PLUGIN = BASE / "vendor" / "kicad-lcsc-manager" / "plugins"
sys.path.insert(0, str(VENDOR_PLUGIN))

CACHE_DIR.mkdir(parents=True, exist_ok=True)
LIBS_DIR.mkdir(parents=True, exist_ok=True)

def fetch_all():
    # If executed via system python, try import
    try:
        from lcsc_manager.api.lcsc_api import LCSCAPIClient, LCSCRateLimitError
    except Exception as e:
        print(f"[error] cannot import LCSCAPIClient: {e}")
        print("Trying KiCad Python...")
        # Re-exec via KiCad Python
        if KICAD_PY.exists() and pathlib.Path(sys.executable) != KICAD_PY:
            cmd = [str(KICAD_PY), str(__file__)] + sys.argv[1:]
            os.execv(str(KICAD_PY), cmd)
        else:
            raise
    from lcsc_manager.converters.footprint_converter import FootprintConverter

    bom = json.loads(BOM_PATH.read_text())
    codes = [entry['lcsc'] for entry in bom]
    print(f"[bom] {len(codes)} unique LCSC codes: {codes}")

    client = LCSCAPIClient()
    conv = FootprintConverter()

    # Temporary legacy pretty dir
    tmp_legacy = pathlib.Path(tempfile.mkdtemp(prefix="legacy_"))
    tmp_new = pathlib.Path(tempfile.mkdtemp(prefix="new_"))

    pretty_legacy = tmp_legacy / "scale_libs.pretty"
    pretty_legacy.mkdir(parents=True, exist_ok=True)

    print(f"[tmp] legacy={pretty_legacy} new={tmp_new}")

    for code in codes:
        cache_file = CACHE_DIR / f"{code}.json"
        # Fetch if missing or force?
        if not cache_file.exists():
            print(f"[fetch] {code} missing cache, fetching...")
            try:
                comp = client.search_component(code)
                if not comp:
                    print(f"[miss] {code} not found via direct search, trying JLCPCB search")
                    results = client.search_jlcpcb(code, page=1, page_size=5)
                    for r in results:
                        lcsc_num = r.get('lcsc',{}).get('number')
                        if lcsc_num:
                            comp = client.search_component(lcsc_num)
                            if comp:
                                break
                if comp:
                    cache_file.write_text(json.dumps(comp, indent=2))
                    print(f"[cached] {code} -> {cache_file}")
                    time.sleep(2)
                else:
                    print(f"[failed] {code} not found")
                    continue
            except Exception as e:
                print(f"[error] {code} fetch failed: {e}")
                continue
        else:
            print(f"[cache-hit] {code}")

        # Load and convert
        try:
            comp = json.loads(cache_file.read_text())
            easyeda_data = comp.get('easyeda_data')
            if not easyeda_data:
                print(f"[skip-convert] {code} no easyeda_data")
                continue
            comp_info = {"lcsc_id": comp['lcsc_id'], "package": comp['package']}
            legacy_text = conv.convert(easyeda_data, comp_info)
            # Write to legacy pretty
            out_legacy = pretty_legacy / f"LCSC_{code}.kicad_mod"
            out_legacy.write_text(legacy_text)
            print(f"[legacy-wrote] {code} -> {out_legacy.name} len={len(legacy_text)}")
        except Exception as e:
            print(f"[error] {code} convert failed: {e}")
            import traceback; traceback.print_exc()

    # Run kicad-cli fp upgrade to convert legacy module -> footprint v20260206
    if KICAD_CLI.exists():
        # Clean tmp_new and create output dir
        if tmp_new.exists():
            shutil.rmtree(tmp_new)
        tmp_new.mkdir(parents=True, exist_ok=True)
        cmd = [str(KICAD_CLI), "fp", "upgrade", str(pretty_legacy), "--output", str(tmp_new), "--force"]
        print(f"[fp-upgrade] {' '.join(cmd)}")
        result = subprocess.run(cmd, capture_output=True, text=True)
        print(result.stdout[-500:])
        print(result.stderr[-500:])
        if result.returncode != 0:
            print(f"[warn] fp upgrade failed rc={result.returncode}")
        # List files
        new_files = list(tmp_new.glob("*.kicad_mod"))
        print(f"[fp-upgrade] produced {len(new_files)} files: {[f.name for f in new_files[:10]]}")
    else:
        print(f"[warn] kicad-cli not found at {KICAD_CLI}, skipping fp upgrade, using manual post-process")
        tmp_new = pretty_legacy

    # Post-process: copy to libs/ with final cleanup, ETA mapping, ensure single headers
    def remove_model_blocks(s: str) -> str:
        res=[]; i=0
        while i < len(s):
            nxt=s.find("(model", i)
            if nxt==-1:
                res.append(s[i:]); break
            res.append(s[i:nxt])
            depth=0; j=nxt
            while j < len(s):
                if s[j]=="(": depth+=1
                elif s[j]==")":
                    depth-=1
                    if depth==0:
                        j+=1; break
                j+=1
            i=j
        return "".join(res)

    # ETA pad map helper
    def eta_map(ax, ay):
        tbl = {
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
        for (cx, cy), num in tbl.items():
            if abs(ax - cx) < 0.05 and abs(ay - cy) < 0.05:
                return num
        return None

    for src in tmp_new.glob("*.kicad_mod"):
        raw = src.read_text()
        # Strip model blocks
        txt = remove_model_blocks(raw)
        # Ensure footprint name starts with LCSC_
        m = re.search(r'\(footprint\s+"([^"]+)"', txt)
        if not m:
            print(f"[skip] {src.name} no footprint")
            continue
        fp_name = m.group(1)
        # If name doesn't start with LCSC_, prefix with LCSC_ + code?
        # Try infer LCSC code from file name
        lcsc_code_match = re.search(r'LCSC_(C\d+)', src.name)
        lcsc_code = lcsc_code_match.group(1) if lcsc_code_match else "CXXXX"
        if not fp_name.startswith("LCSC_"):
            fp_name = f"LCSC_{lcsc_code}_{fp_name}"
            txt = txt.replace(m.group(0), f'(footprint "{fp_name}"', 1)

        # For C7465513 ETA9741 remap pad numbers in new multi-line format:
        if lcsc_code == "C7465513":
            # New format pads are like:
            # (pad "5" smd rect
            #   (at 1.905 -2.682)
            # So need stateful parse
            lines = txt.splitlines()
            new_lines=[]
            current_pad_num=None
            current_pad_at=None
            # We'll parse accumulating pad block
            pad_block=[]
            in_pad=False
            pad_num=None
            pad_at=None
            # Simpler: regex over entire file for (pad "X" ... (at ax ay) -> remap
            # Use finditer for pad...
            def repl_pad(match):
                old_num = match.group(1)
                ax = float(match.group(2))
                ay = float(match.group(3))
                rest = match.group(4)
                nn = eta_map(ax, ay)
                if nn and nn != old_num:
                    return f'(pad "{nn}" smd rect\n\t\t(at {ax:.3f} {ay:.3f}{rest}'
                else:
                    # keep but ensure format is generic? we want keep original rest but ensure number quoted
                    return match.group(0)

            # This pattern matches old single-line legacy which after upgrade becomes multi-line with newline before at
            # Let's handle both single and multi via two passes
            # Pass 1: multi-line where (pad "X" ... newline (at ax ay)
            # We'll replace using regex across file
            pattern_multi = re.compile(r'\(pad\s+"([^"]+)"\s+smd\s+rect\s*\n\s*\(at\s+([-\d\.]+)\s+([-\d\.]+)(.*?)\)', re.S)
            # Actually after upgrade pads have extra newline etc, using function
            def repl_multi(m):
                old_num = m.group(1)
                ax = float(m.group(2)); ay = float(m.group(3))
                tail = m.group(4) # includes rest up to )
                nn = eta_map(ax, ay)
                if nn:
                    return f'(pad "{nn}" smd rect\n\t\t(at {ax:.3f} {ay:.3f}{tail})'
                return m.group(0)
            # For safety, do single-line legacy pattern earlier? new format is multi-line, so use multi
            txt = re.sub(r'\(pad\s+"([^"]+)"\s+smd\s+rect\s*\n\s*\(at\s+([-\d\.]+)\s+([-\d\.]+)', lambda mm: f'(pad "{eta_map(float(mm.group(2)), float(mm.group(3))) or mm.group(1)}" smd rect\n\t\t(at {float(mm.group(2)):.3f} {float(mm.group(3)):.3f}', txt)

            # Force oval->rect for ETA? Actually ETA pads are oval in legacy, but after upgrade they're oval? For ETA we want keep oval? Spec says QFN20 etc but we remap numbers, keep shape.
            # Simpler second pass for oval pads after upgrade:
            def repl_oval_eta(match):
                old_num = match.group(1)
                shape = match.group(2)
                ax = float(match.group(3)); ay = float(match.group(4))
                rest = match.group(5)
                nn = eta_map(ax, ay)
                if nn:
                    return f'(pad "{nn}" smd {shape}\n\t\t(at {ax:.3f} {ay:.3f}{rest}'
                return match.group(0)
            txt = re.sub(r'\(pad\s+"([^"]+)"\s+smd\s+(rect|oval)\s*\n\s*\(at\s+([-\d\.]+)\s+([-\d\.]+)(.*)', repl_oval_eta, txt, flags=re.S)

        # Ensure attr smd for most, but for C5832372 inductor keep smd (it was through_hole in legacy incorrectly)
        # Force smd if file is not Pogo
        if 'Pogo' not in fp_name:
            txt = re.sub(r'\(attr\s+through_hole\)', '(attr smd)', txt)

        # Ensure final file starts exactly with (footprint "..."\n\t(version 20260206)...
        # The upgraded files already have version etc, but may have extra whitespace – keep as is
        # Just ensure no (module, only one footprint
        if txt.count('(footprint "') != 1:
                fp_cnt = txt.count('(footprint "')
                ver_cnt = txt.count('(version 20260206)')
                print("  footprint count", fp_cnt, "ver", ver_cnt)

        # Write to libs/
        # Preserve package suffix from earlier legacy name? Use fp_name as generated
        # Map code to final libs file: LCSC_<code>.kicad_mod -> LCSC_<code>_<package>.kicad_mod? 
        # We have cache to get package
        cache_file = CACHE_DIR / f"{lcsc_code}.json"
        if cache_file.exists():
            comp = json.loads(cache_file.read_text())
            pkg = comp['package'].replace(" ", "_").replace(".", "_").replace("/", "_")
            final_name = f"LCSC_{lcsc_code}_{pkg}"
            # If upgraded name is LCSC_Cxxx (without package), rename
            if fp_name == f"LCSC_{lcsc_code}":
                txt = txt.replace(f'(footprint "{fp_name}"', f'(footprint "{final_name}"', 1)
                fp_name = final_name
            # else keep existing package suffix, but ensure file name matches footprint name?
            out_path = LIBS_DIR / f"LCSC_{lcsc_code}.kicad_mod"
        else:
            out_path = LIBS_DIR / src.name

        # For final libs, we want filename LCSC_<code>.kicad_mod per CPL mapping? Actually CPL expects mapping via file existence, footprint property uses scale_libs:FOO where FOO is footprint name inside file.
        # The plan says libs/ containing LCSC_*.kicad_mod converted to v20260206. So filename LCSC_Cxxxxx.kicad_mod is okay, but internal footprint name should be LCSC_Cxxxxx_<package>
        # Write to libs using code-based filename
        out_libs = LIBS_DIR / f"LCSC_{lcsc_code}.kicad_mod"
        out_libs.write_text(txt)
        print(f"[libs-wrote] {lcsc_code} -> {out_libs.name} fp_name={fp_name}")

    # Cleanup tmp
    shutil.rmtree(tmp_legacy, ignore_errors=True)
    shutil.rmtree(tmp_new, ignore_errors=True)
    print("[done] all 26 LCSC footprints processed to libs/")

if __name__ == "__main__":
    fetch_all()