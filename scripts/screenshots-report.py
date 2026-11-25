#!/usr/bin/env python3
"""Collect Compose screenshot (metadata) results and publish them to
https://git.maproj.org/screenshots (see location_share_server
handlers/screenshots).

Modes:
  (default)  Collect the goldens already in metadata_data/photos/<module>/
             (e.g. after a `./gradlew :<module>:metadata` sweep someone ran).
             A module with >=1 capture is `passed`; otherwise `failed`
             with first_error "no captures collected".
  --run      First run `./gradlew :<gradle-path>:metadata` per module,
             recording exit status + first error line, counting goldens
             whose hash changed, then collect. Slow (full sweep).

Upload (default, disable with --no-upload):
  PUT  {base}/c_password123/screenshots/gold/<run-id>/<module>/<bucket>/<file>
  POST {base}/c_password123/screenshots/runs   (the manifest)
The /c_password123 prefix is the server's existing unguessable admin
prefix — the same auth the tests/dependencies admin endpoints use; there
is no public write endpoint. Override base with --base or
SCREENSHOTS_BASE (e.g. http://127.0.0.1:8080 for a local server).

Also always writes the manifest to --out (default /tmp/screenshots-run.json)
so the dataset can be inspected/re-uploaded without re-collecting.
"""
import argparse, hashlib, json, os, re, secrets, struct, subprocess, sys, time, urllib.request
from pathlib import Path

BUCKETS = ["phone", "tablet", "large-tablet", "tv", "wear"]
ROOT = Path(__file__).resolve().parent.parent

def png_size(p: Path):
    try:
        with p.open("rb") as f:
            head = f.read(24)
        if head[:8] == b"\x89PNG\r\n\x1a\n":
            w, h = struct.unpack(">II", head[16:24])
            return w, h
    except OSError:
        pass
    return 0, 0

def modules():
    """(module-key, gradle-path, dir) for apps applying preview-metadata."""
    out = []
    for bf in sorted(ROOT.rglob("build.gradle.kts")):
        rel = bf.parent.relative_to(ROOT)
        if any(part in ("build", ".git", "analysis", "personal") for part in rel.parts):
            continue
        try:
            text = bf.read_text(errors="ignore")
        except OSError:
            continue
        if 'id("common-conventions-preview-metadata")' not in text:
            continue
        key = str(rel).replace("/", "-")
        gradle = ":" + str(rel).replace("/", ":")
        out.append((key, gradle, bf.parent))
    return out

def hashes(module_key):
    base = ROOT / "metadata_data" / "photos" / module_key
    h = {}
    if base.is_dir():
        for p in base.rglob("*.png"):
            h[str(p.relative_to(base))] = hashlib.sha256(p.read_bytes()).hexdigest()
    return h

def collect(module_key, status, first_error, regenerated):
    base = ROOT / "metadata_data" / "photos" / module_key
    captures = []
    if base.is_dir():
        for bucket in BUCKETS:
            d = base / bucket
            if not d.is_dir():
                continue
            for p in sorted(d.glob("*.png")):
                w, ht = png_size(p)
                captures.append({"bucket": bucket, "file": p.name, "width": w, "height": ht, "bytes": p.stat().st_size})
        # Non-bucket goldens (e.g. camera.png) are not store captures; ignore.
    if status is None:
        status = "passed" if captures else "failed"
        if not captures and not first_error:
            first_error = "no captures collected"
    return {"module": module_key, "gradle_path": "", "status": status,
            "first_error": first_error, "captures": captures, "regenerated": regenerated}

def first_error_line(output):
    for line in output.splitlines():
        s = line.strip()
        if re.search(r"(FAILED|FAILURE|Exception|error:|BUILD FAILED)", s):
            return s[:500]
    tail = [l.strip() for l in output.splitlines() if l.strip()]
    return (tail[-1][:500] if tail else "gradle metadata failed")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="store_true", help="run :<module>:metadata per module first")
    ap.add_argument("--no-upload", action="store_true")
    ap.add_argument("--base", default=os.environ.get("SCREENSHOTS_BASE", "https://git.maproj.org"))
    ap.add_argument("--out", default="/tmp/screenshots-run.json")
    ap.add_argument("--only", default="", help="comma-separated module keys")
    args = ap.parse_args()

    mods = modules()
    if args.only:
        want = set(args.only.split(","))
        mods = [m for m in mods if m[0] in want]
    sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    subject = subprocess.run(["git", "log", "-1", "--format=%s"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    run_id = secrets.token_hex(8)
    results = []
    for key, gradle, _dir in mods:
        status, err, regen = None, None, 0
        if args.run:
            before = hashes(key)
            proc = subprocess.run(["./gradlew", "--console=plain", f"{gradle}:metadata"],
                                  cwd=ROOT, capture_output=True, text=True)
            after = hashes(key)
            regen = sum(1 for k, v in after.items() if before.get(k) != v) + sum(1 for k in before if k not in after)
            if proc.returncode != 0:
                status, err = "failed", first_error_line((proc.stdout or "") + (proc.stderr or ""))
            else:
                status = "passed"
            print(f"{key}: {status} regenerated={regen}", flush=True)
        else:
            # Changed vs HEAD right now (0 on a clean tree).
            dirty = subprocess.run(["git", "status", "--porcelain", "--", f"metadata_data/photos/{key}"],
                                   cwd=ROOT, capture_output=True, text=True).stdout.strip()
            regen = len([l for l in dirty.splitlines() if l.strip()]) if dirty else 0
        r = collect(key, status, err, regen)
        r["gradle_path"] = gradle
        results.append(r)

    run = {"id": run_id, "sha": sha, "subject": subject, "timestamp": int(time.time()), "modules": results}
    Path(args.out).write_text(json.dumps(run, indent=1))
    passed = sum(1 for r in results if r["status"] == "passed")
    print(f"run {run_id} sha={sha[:9]} modules={len(results)} passed={passed} failed={len(results)-passed} -> {args.out}")
    if args.no_upload:
        return 0

    base = args.base.rstrip("/")
    host_header = os.environ.get("SCREENSHOTS_HOST", "")
    def req(method, url, data=None, ctype=None):
        r = urllib.request.Request(url, data=data, method=method)
        if host_header:
            r.add_header("Host", host_header)
        if ctype:
            r.add_header("Content-Type", ctype)
        with urllib.request.urlopen(r, timeout=120) as resp:
            return resp.status, resp.read()
    n = 0
    for r in results:
        for c in r["captures"]:
            p = ROOT / "metadata_data" / "photos" / r["module"] / c["bucket"] / c["file"]
            url = f"{base}/c_password123/screenshots/gold/{run_id}/{r['module']}/{c['bucket']}/{c['file']}"
            req("PUT", url, data=p.read_bytes(), ctype="image/png")
            n += 1
    status, _ = req("POST", f"{base}/c_password123/screenshots/runs",
                    data=json.dumps(run).encode(), ctype="application/json")
    print(f"uploaded {n} goldens, manifest POST -> {status}; page: {base}/screenshots")
    return 0

if __name__ == "__main__":
    sys.exit(main())
