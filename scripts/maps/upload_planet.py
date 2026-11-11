"""Upload any local file to R2 as planet3.mamaps (bucket: maps).

Usage: python upload_planet.py <local-file> [--key K] [options]
       python upload_planet.py --manifest <overlay-dir> --overlay-seq N --base-build-id HEX [--key K]
                               [--manifest-key M] [options]
       python upload_planet.py --rebase --overlay-seq N --base-build-id HEX --base-len N [--key K]
                               [--manifest-key M] [--base-sha256 HEX] [--dry-run]

--key names the base object (default planet3.mamaps). The manifest written by
--manifest / --rebase goes to --manifest-key, which defaults to the name
derived from --key (planetN.mamaps -> planetN.manifest.json; any other key ->
planet3.manifest.json), so `--key planet4.mamaps` publishes a planet4 stack
without touching planet3.* (which the shipped apps read). Without --key every
mode behaves exactly as before.

Serves https://data.maproj.org/planet3.mamaps, which the apps'
basemap URLs point at (library/map urls.rs, maps MapTileCache.kt).

The base upload is a resumable multipart upload with end-to-end hash checks:

* Parts are uploaded concurrently (--part-size-mb / --concurrency, or the
  R2_PART_SIZE_MB / R2_CONCURRENCY env vars). The default part size is
  64 MiB, raised automatically so the upload never needs more than 9,000
  parts (R2 allows 10,000, all but the last the same size, 5 MiB..5 GiB):
  a ~80 GB planet is ~1,200 parts. Each part carries Content-MD5, so R2
  rejects a part that was corrupted in flight, and its returned ETag is
  checked against the local MD5.
* The upload id and every completed part (number + ETag) are persisted in a
  state file (default <local-file>.upload-state.json) after each part. A rerun
  after a crash or a dropped link lists the parts R2 already holds, keeps every
  one whose ETag matches the local bytes, and uploads only the rest. The state
  is discarded (and the stale upload aborted) if the file, key or part size
  changed; --no-resume forces a fresh upload.
* The SHA-256 of the whole file is computed while the parts upload (one
  sequential reader beside the part workers) and compared with the
  `sha256sum`-format sidecar <local-file>.sha256 that `mamaps_pack
  --sha256-out` writes, when it is present (or --sha256 HEX). A mismatch means
  the local copy is not the file the build produced (e.g. a bad pull from the
  build box): the upload is aborted before CompleteMultipartUpload, so nothing
  becomes visible. The hash is stored as object metadata (x-amz-meta-sha256)
  and checked after completion with a HEAD, together with the size.
* --dry-run exercises all of the above -- reading, MD5/SHA-256, part planning,
  state file, resume -- against an in-process fake bucket persisted next to the
  state file, and makes no network calls at all.

The --manifest form publishes a patch-overlay update: it uploads each
overlay-*.mamaps segment from the overlay dir under overlays/<seq>/,
then writes planet3.manifest.json (base pin + segment list + hashes).
The manifest is the only file the client fetches on a check. Segments that
R2 already holds with the same size and x-amz-meta-sha256 are not re-uploaded;
every segment is HEAD-verified (size + server hash vs local hash) before the
manifest is written, and the base the manifest pins is HEAD-checked too
(its size against --base-len, and its stored hash against --base-sha256 when
given).

SECURITY: credentials/config come from ENVIRONMENT VARIABLES, never baked
into this file (the analysis/ original carried hardcoded keys and must
stay gitignored there). Never paste keys on the command line — `export`
them instead, ideally from a secret store.

Required env (not for --dry-run):
  R2_ENDPOINT           S3 API endpoint, e.g. https://<ACCOUNT_ID>.r2.cloudflarestorage.com
  R2_ACCESS_KEY_ID      R2 access key id
  R2_SECRET_ACCESS_KEY  R2 secret access key
Optional env:
  R2_BUCKET             Default: maps
  R2_CDN                Default: https://data.maproj.org (URL prefix written into the manifest)
  R2_PART_SIZE_MB       Default: 64 (raised to keep the part count <= 9,000)
  R2_CONCURRENCY        Default: 16 parts in flight (each holds one part in memory)
"""
import argparse
import base64
import hashlib
import json
import os
import random
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed

BUCKET = os.environ.get("R2_BUCKET", "maps")
KEY = "planet3.mamaps"
MANIFEST_KEY = "planet3.manifest.json"


def manifest_key_for(key: str) -> str:
    """planetN.mamaps -> planetN.manifest.json; anything else -> MANIFEST_KEY."""
    if key.endswith(".mamaps") and "/" not in key:
        return key[: -len(".mamaps")] + ".manifest.json"
    return MANIFEST_KEY


def add_key_args(ap: argparse.ArgumentParser) -> None:
    ap.add_argument("--key", default=KEY, help=f"base object key (default {KEY})")
    ap.add_argument("--manifest-key", default=None,
                    help="manifest object key (default derived from --key: planetN.mamaps -> "
                         f"planetN.manifest.json; {MANIFEST_KEY} for the default key)")


def manifest_key(args) -> str:
    return args.manifest_key or manifest_key_for(args.key)
CDN = os.environ.get("R2_CDN", "https://data.maproj.org")

MIB = 1024 * 1024
DEFAULT_PART_MB = 64
DEFAULT_CONCURRENCY = 16
MIN_PART = 5 * MIB          # S3/R2 floor for every part but the last
MAX_PART = 5 * 1024 * MIB   # S3/R2 ceiling
MAX_PARTS = 10_000          # S3/R2 hard limit
TARGET_MAX_PARTS = 9_000    # what auto-sizing aims under, leaving slack
PART_ATTEMPTS = 5           # per-part attempts on top of botocore's own retries
RETRY_BASE_S = 2.0          # first backoff between part attempts (doubles, capped at 60 s)
HASH_CHUNK = 8 * MIB
META_SHA = "sha256"         # stored as x-amz-meta-sha256

MANIFEST_VERSION = 1
MIN_CLIENT_COMPAT = 1


def log(msg: str) -> None:
    print(msg, flush=True)


# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------

def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        v = int(raw)
    except ValueError:
        raise SystemExit(f"{name}={raw!r} is not an integer")
    if v <= 0:
        raise SystemExit(f"{name} must be positive, not {v}")
    return v


def plan_part_size(size: int, requested_mb: int) -> int:
    """Part size in bytes: the request, raised so `size` needs <= TARGET_MAX_PARTS
    parts, rounded up to a whole MiB and clamped to the S3/R2 limits."""
    part = max(requested_mb * MIB, MIN_PART)
    need = -(-size // TARGET_MAX_PARTS)          # ceil
    if part < need:
        part = -(-need // MIB) * MIB
    if part > MAX_PART:
        raise SystemExit(f"{size} bytes would need parts over 5 GiB; too large for one object")
    if -(-size // part) > MAX_PARTS:
        raise SystemExit(f"{size} bytes in {part}-byte parts is over {MAX_PARTS} parts")
    return part


def add_transfer_args(ap: argparse.ArgumentParser) -> None:
    ap.add_argument("--part-size-mb", type=int, default=None,
                    help=f"multipart part size in MiB (env R2_PART_SIZE_MB, default {DEFAULT_PART_MB}; "
                         f"raised automatically to stay under {TARGET_MAX_PARTS} parts)")
    ap.add_argument("--concurrency", type=int, default=None,
                    help=f"parts in flight (env R2_CONCURRENCY, default {DEFAULT_CONCURRENCY})")
    ap.add_argument("--dry-run", action="store_true",
                    help="do everything except network calls, against a local fake bucket")


def transfer_settings(args) -> tuple:
    part_mb = args.part_size_mb if args.part_size_mb is not None else env_int("R2_PART_SIZE_MB", DEFAULT_PART_MB)
    conc = args.concurrency if args.concurrency is not None else env_int("R2_CONCURRENCY", DEFAULT_CONCURRENCY)
    if part_mb <= 0 or conc <= 0:
        raise SystemExit("--part-size-mb and --concurrency must be positive")
    return part_mb, conc


# --------------------------------------------------------------------------
# Clients
# --------------------------------------------------------------------------

def make_client(concurrency: int):
    """A real boto3 client for R2. boto3 is imported here so --dry-run and the
    unit tests run without it."""
    missing = [v for v in ("R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY")
               if not os.environ.get(v)]
    if missing:
        raise SystemExit(f"missing env: {', '.join(missing)} (see module docstring)")
    import boto3
    from botocore.config import Config
    cfg = dict(
        # One pooled connection per part in flight, plus a few for HEAD/list.
        max_pool_connections=concurrency + 4,
        retries={"max_attempts": 10, "mode": "adaptive"},
        connect_timeout=30,
        read_timeout=300,
    )
    # botocore >= 1.36 adds CRC checksums to every upload by default; R2 does
    # not accept all of them. Content-MD5 (set per part below) is the integrity
    # check here, so only compute checksums where an operation requires them.
    try:
        client_cfg = Config(request_checksum_calculation="when_required",
                            response_checksum_validation="when_required", **cfg)
    except TypeError:
        client_cfg = Config(**cfg)
    return boto3.client(
        "s3",
        endpoint_url=os.environ["R2_ENDPOINT"],
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
        config=client_cfg,
    )


class ClientError(Exception):
    """Raised by FakeS3 with the same `.response["Error"]["Code"]` shape botocore uses."""

    def __init__(self, code: str, msg: str = ""):
        super().__init__(f"{code}: {msg}")
        self.response = {"Error": {"Code": code, "Message": msg}}


def error_code(e: Exception) -> str:
    return getattr(e, "response", {}).get("Error", {}).get("Code", "")


class FakeS3:
    """The subset of the S3 API this script uses, in memory, optionally persisted
    to a JSON file so a dry run can be interrupted and resumed like the real
    thing. Object bodies are never stored -- only sizes, MD5s and metadata --
    so a dry run of an 80 GB file costs kilobytes. It validates what R2 would:
    Content-MD5 against the body, part sizes, part order and ETags on complete.

    `fail_after_parts` (tests / UPLOAD_PLANET_FAIL_AFTER_PARTS) raises on the
    Nth successful UploadPart to simulate a dropped link."""

    def __init__(self, persist_path: str = None, fail_after_parts: int = None):
        self.path = persist_path
        self.lock = threading.Lock()
        self.uploads = {}   # upload_id -> {"bucket","key","meta","parts":{n: [size, etag]}}
        self.objects = {}   # "bucket/key" -> {"size", "meta", "etag"}
        self.calls = []     # (op, detail) log, for tests
        self.fail_after = fail_after_parts
        self.part_ok = 0
        if self.path and os.path.exists(self.path):
            with open(self.path) as f:
                st = json.load(f)
            self.uploads = {k: {**v, "parts": {int(n): p for n, p in v["parts"].items()}}
                            for k, v in st.get("uploads", {}).items()}
            self.objects = st.get("objects", {})

    def _save(self):
        if not self.path:
            return
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"uploads": self.uploads, "objects": self.objects}, f)
        os.replace(tmp, self.path)

    def create_multipart_upload(self, Bucket, Key, Metadata=None, **_):
        with self.lock:
            uid = "dryrun-" + uuid.uuid4().hex
            self.uploads[uid] = {"bucket": Bucket, "key": Key, "meta": dict(Metadata or {}), "parts": {}}
            self.calls.append(("create", Key))
            self._save()
            return {"UploadId": uid}

    def upload_part(self, Bucket, Key, UploadId, PartNumber, Body, ContentMD5=None, **_):
        body = bytes(Body)
        md5 = hashlib.md5(body)
        if ContentMD5 is not None and base64.b64encode(md5.digest()).decode() != ContentMD5:
            raise ClientError("BadDigest", f"part {PartNumber}")
        with self.lock:
            if self.fail_after is not None and self.part_ok >= self.fail_after:
                raise ConnectionError("simulated dropped link")
            up = self.uploads.get(UploadId)
            if up is None:
                raise ClientError("NoSuchUpload", UploadId)
            etag = f'"{md5.hexdigest()}"'
            up["parts"][PartNumber] = [len(body), etag]
            self.part_ok += 1
            self.calls.append(("part", PartNumber))
            self._save()
            return {"ETag": etag}

    def list_parts(self, Bucket, Key, UploadId, PartNumberMarker=0, MaxParts=1000, **_):
        with self.lock:
            up = self.uploads.get(UploadId)
            if up is None:
                raise ClientError("NoSuchUpload", UploadId)
            nums = sorted(n for n in up["parts"] if n > PartNumberMarker)
            page = nums[:MaxParts]
            self.calls.append(("list", PartNumberMarker))
            return {
                "Parts": [{"PartNumber": n, "Size": up["parts"][n][0], "ETag": up["parts"][n][1]} for n in page],
                "IsTruncated": len(nums) > MaxParts,
                "NextPartNumberMarker": page[-1] if page else PartNumberMarker,
            }

    def complete_multipart_upload(self, Bucket, Key, UploadId, MultipartUpload):
        with self.lock:
            up = self.uploads.get(UploadId)
            if up is None:
                raise ClientError("NoSuchUpload", UploadId)
            parts = MultipartUpload["Parts"]
            nums = [p["PartNumber"] for p in parts]
            if nums != sorted(nums) or nums != list(range(1, len(nums) + 1)):
                raise ClientError("InvalidPartOrder", str(nums[:5]))
            sizes = []
            for p in parts:
                have = up["parts"].get(p["PartNumber"])
                if have is None or have[1] != p["ETag"]:
                    raise ClientError("InvalidPart", str(p["PartNumber"]))
                sizes.append(have[0])
            if any(s < MIN_PART for s in sizes[:-1]) or len(set(sizes[:-1])) > 1:
                raise ClientError("EntityTooSmall", "parts must match and be >= 5 MiB")
            self.objects[f"{Bucket}/{Key}"] = {"size": sum(sizes), "meta": up["meta"],
                                               "etag": f'"multipart-{len(parts)}"'}
            del self.uploads[UploadId]
            self.calls.append(("complete", Key))
            self._save()
            return {"ETag": self.objects[f"{Bucket}/{Key}"]["etag"]}

    def abort_multipart_upload(self, Bucket, Key, UploadId):
        with self.lock:
            self.uploads.pop(UploadId, None)
            self.calls.append(("abort", Key))
            self._save()
            return {}

    def put_object(self, Bucket, Key, Body, Metadata=None, ContentMD5=None, **_):
        body = bytes(Body)
        md5 = hashlib.md5(body)
        if ContentMD5 is not None and base64.b64encode(md5.digest()).decode() != ContentMD5:
            raise ClientError("BadDigest", Key)
        with self.lock:
            self.objects[f"{Bucket}/{Key}"] = {"size": len(body), "meta": dict(Metadata or {}),
                                               "etag": f'"{md5.hexdigest()}"'}
            self.calls.append(("put", Key))
            self._save()
            return {"ETag": self.objects[f"{Bucket}/{Key}"]["etag"]}

    def head_object(self, Bucket, Key):
        with self.lock:
            o = self.objects.get(f"{Bucket}/{Key}")
            self.calls.append(("head", Key))
            if o is None:
                raise ClientError("404", Key)
            return {"ContentLength": o["size"], "Metadata": dict(o["meta"]), "ETag": o["etag"]}


def dry_run_client(state_path: str):
    fail = os.environ.get("UPLOAD_PLANET_FAIL_AFTER_PARTS")
    return FakeS3(persist_path=state_path + ".dryrun-bucket.json",
                  fail_after_parts=int(fail) if fail else None)


# --------------------------------------------------------------------------
# Hashes
# --------------------------------------------------------------------------

def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(HASH_CHUNK)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def read_sidecar(path: str, name: str) -> str:
    """The hash from a `sha256sum`-format line (`<hex>  <name>`), or None if the
    file is absent. A malformed line or one naming another file is an error."""
    if not os.path.exists(path):
        return None
    with open(path) as f:
        lines = [l for l in f.read().splitlines() if l.strip()]
    if len(lines) != 1:
        raise SystemExit(f"{path}: expected one `sha256sum` line, found {len(lines)}")
    parts = lines[0].split()
    digest = parts[0].lower()
    if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        raise SystemExit(f"{path}: {parts[0]!r} is not a SHA-256")
    if len(parts) > 1 and parts[-1].lstrip("*") != name:
        raise SystemExit(f"{path} names {parts[-1]!r}, not {name!r}")
    return digest


def server_sha(head: dict) -> str:
    meta = {k.lower(): v for k, v in (head.get("Metadata") or {}).items()}
    v = meta.get(META_SHA)
    return v.lower() if v else None


def head_or_none(s3, bucket: str, key: str):
    try:
        return s3.head_object(Bucket=bucket, Key=key)
    except Exception as e:
        if error_code(e) in ("404", "NoSuchKey", "NotFound"):
            return None
        raise


# --------------------------------------------------------------------------
# Resumable multipart upload
# --------------------------------------------------------------------------

class State:
    """The resume record: which upload id, which file, and which parts are done.
    Rewritten atomically after every part, so a crash loses at most the parts in
    flight (and those are recovered from ListParts on the next run)."""

    def __init__(self, path: str):
        self.path = path
        self.lock = threading.Lock()
        self.data = None

    def load(self):
        if not os.path.exists(self.path):
            return None
        try:
            with open(self.path) as f:
                self.data = json.load(f)
        except (OSError, ValueError) as e:
            log(f"  ignoring unreadable state file {self.path}: {e}")
            self.data = None
        return self.data

    def start(self, **fields):
        self.data = {**fields, "parts": {}}
        self.save()

    def part_done(self, n: int, etag: str):
        with self.lock:
            self.data["parts"][str(n)] = etag
            self._write()

    def save(self):
        with self.lock:
            self._write()

    def _write(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.data, f)
        os.replace(tmp, self.path)

    def remove(self):
        for p in (self.path, self.path + ".tmp"):
            try:
                os.remove(p)
            except FileNotFoundError:
                pass


def pread(fd: int, want: int, off: int) -> bytes:
    if hasattr(os, "pread"):
        return os.pread(fd, want, off)
    # No positional read (Windows): a private handle per call, so threads never
    # share a file cursor.
    with open(_fd_paths[fd], "rb") as f:
        f.seek(off)
        return f.read(want)


_fd_paths = {}


def open_ro(path: str) -> int:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0))
    _fd_paths[fd] = path
    return fd


def read_part(fd: int, n: int, part_size: int, size: int) -> bytes:
    off = (n - 1) * part_size
    want = min(part_size, size - off)
    buf = pread(fd, want, off)
    if len(buf) != want:
        raise IOError(f"short read of part {n}: {len(buf)} of {want} bytes")
    return buf


def list_all_parts(s3, bucket: str, key: str, upload_id: str) -> dict:
    out, marker = {}, 0
    while True:
        r = s3.list_parts(Bucket=bucket, Key=key, UploadId=upload_id, PartNumberMarker=marker)
        for p in r.get("Parts", []):
            out[int(p["PartNumber"])] = (int(p["Size"]), p["ETag"])
        if not r.get("IsTruncated"):
            return out
        marker = r["NextPartNumberMarker"]


def multipart_upload(s3, src: str, bucket: str, key: str, part_mb: int, concurrency: int,
                     state_path: str, expected_sha: str = None, resume: bool = True,
                     verify_local: bool = True) -> int:
    """Upload `src` to bucket/key; returns a process exit code (0 = verified)."""
    size = os.path.getsize(src)
    mtime_ns = os.stat(src).st_mtime_ns
    part_size = plan_part_size(size, part_mb)
    n_parts = max(1, -(-size // part_size))
    log(f"uploading {src} ({size / 1e9:.2f} GB) to s3://{bucket}/{key}: "
        f"{n_parts} part(s) of {part_size // MIB} MiB, {concurrency} in flight")

    state = State(state_path)
    old = state.load() if resume else None
    identity = {"bucket": bucket, "key": key, "size": size, "mtime_ns": mtime_ns, "part_size": part_size}
    done = {}           # part number -> etag already on the server
    upload_id = None
    if old and all(old.get(k) == v for k, v in identity.items()) and old.get("upload_id"):
        upload_id = old["upload_id"]
        try:
            server = list_all_parts(s3, bucket, key, upload_id)
        except Exception as e:
            if error_code(e) != "NoSuchUpload":
                raise
            # Gone: either completed (a crash after CompleteMultipartUpload) or
            # expired/aborted. Completed with the right bytes means nothing to do.
            head = head_or_none(s3, bucket, key)
            want = expected_sha or old.get("sha256")
            if head is not None and int(head["ContentLength"]) == size and want and server_sha(head) == want:
                log(f"  previous upload {upload_id} already completed: s3://{bucket}/{key} has "
                    f"{size} bytes, sha256 {want}")
                state.remove()
                return 0
            log(f"  previous upload {upload_id} no longer exists on the server; starting over")
            upload_id = None
        else:
            # Keep a server part only if its size is right and its ETag is the MD5
            # of the local bytes. Parts the state file recorded are trusted by ETag;
            # parts it missed (crash between upload and save) are re-hashed.
            recorded = old.get("parts", {})
            fd = open_ro(src)
            try:
                for n, (psize, etag) in sorted(server.items()):
                    if n > n_parts:
                        continue
                    want = min(part_size, size - (n - 1) * part_size)
                    if psize != want:
                        continue
                    if recorded.get(str(n)) == etag:
                        done[n] = etag
                    elif f'"{hashlib.md5(read_part(fd, n, part_size, size)).hexdigest()}"' == etag:
                        done[n] = etag
            finally:
                os.close(fd)
            if old.get("sha256") and expected_sha and old["sha256"] != expected_sha:
                log("  expected hash changed since the interrupted run; aborting it and starting over")
                try:
                    s3.abort_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id)
                except Exception as e:
                    log(f"  (abort failed: {e}; R2 expires it on its own)")
                upload_id, done = None, {}
            else:
                expected_sha = expected_sha or old.get("sha256")
                log(f"  resuming upload {upload_id}: {len(done)}/{n_parts} part(s) already on the server")
    elif old and old.get("upload_id"):
        log(f"  state file {state_path} is for a different file/key/part size; aborting that upload")
        try:
            s3.abort_multipart_upload(Bucket=old["bucket"], Key=old["key"], UploadId=old["upload_id"])
        except Exception as e:
            log(f"  (abort of the stale upload failed: {e}; R2 expires it on its own)")

    if upload_id is None:
        if expected_sha is None and verify_local:
            # Without a sidecar the hash has to be known up front to ride the
            # create call as metadata.
            log("  no .sha256 sidecar: hashing the file first")
            t0 = time.time()
            expected_sha = sha256_file(src)
            log(f"  sha256 {expected_sha} ({time.time() - t0:.0f} s)")
        meta = {META_SHA: expected_sha} if expected_sha else {}
        upload_id = s3.create_multipart_upload(Bucket=bucket, Key=key, Metadata=meta)["UploadId"]
        state.start(**identity, upload_id=upload_id, sha256=expected_sha)
        log(f"  started multipart upload {upload_id}")
    else:
        state.data = {**old, "parts": {str(n): e for n, e in done.items()}, "sha256": expected_sha}
        state.save()

    # The whole-file SHA-256, read sequentially beside the part workers
    # (hashlib releases the GIL on large updates).
    hash_result = {}

    def hash_whole():
        try:
            hash_result["sha"] = sha256_file(src)
        except Exception as e:  # reported at the join
            hash_result["err"] = e

    hasher = threading.Thread(target=hash_whole, daemon=True) if verify_local else None
    if hasher:
        hasher.start()

    todo = [n for n in range(1, n_parts + 1) if n not in done]
    lock = threading.Lock()
    already = sum(min(part_size, size - (n - 1) * part_size) for n in done)
    sent = [already]
    last_mark = [already]
    t0 = time.time()
    fd = open_ro(src)

    def send(n: int) -> str:
        body = read_part(fd, n, part_size, size)
        md5 = hashlib.md5(body)
        b64 = base64.b64encode(md5.digest()).decode()
        want_etag = f'"{md5.hexdigest()}"'
        delay = RETRY_BASE_S
        for attempt in range(1, PART_ATTEMPTS + 1):
            try:
                etag = s3.upload_part(Bucket=bucket, Key=key, UploadId=upload_id, PartNumber=n,
                                      Body=body, ContentMD5=b64)["ETag"]
                if etag.lower() != want_etag:
                    raise IOError(f"part {n}: server ETag {etag} is not the local MD5 {want_etag}")
                break
            except Exception as e:
                if attempt == PART_ATTEMPTS or error_code(e) == "NoSuchUpload":
                    raise
                log(f"  part {n} attempt {attempt} failed ({e}); retrying in {delay:.0f} s")
                time.sleep(delay + random.random() * min(delay, 1.0))
                delay = min(delay * 2, 60)
        state.part_done(n, etag)
        with lock:
            sent[0] += len(body)
            if sent[0] - last_mark[0] >= 1_000_000_000 or sent[0] >= size:
                last_mark[0] = sent[0]
                rate = (sent[0] - already) / max(time.time() - t0, 1e-6)
                eta = (size - sent[0]) / rate if rate > 0 else 0
                log(f"  {sent[0] / 1e9:.1f}/{size / 1e9:.1f} GB ({100.0 * sent[0] / size:.1f}%) "
                    f"{rate / 1e6:.0f} MB/s, ETA {eta / 60:.1f} min")
        return etag

    failed = None
    try:
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            futures = {pool.submit(send, n): n for n in todo}
            for fut in as_completed(futures):
                n = futures[fut]
                try:
                    done[n] = fut.result()
                except Exception as e:
                    failed = failed or (n, e)
                    for other in futures:
                        other.cancel()
    finally:
        os.close(fd)
    if failed:
        log(f"UPLOAD FAILED at part {failed[0]}: {failed[1]}")
        log(f"  {len(done)}/{n_parts} part(s) are on the server; rerun the same command to resume "
            f"(state: {state_path})")
        return 1

    if hasher:
        hasher.join()
        if "err" in hash_result:
            log(f"UPLOAD FAILED: could not hash {src}: {hash_result['err']}")
            return 1
        local_sha = hash_result["sha"]
        if expected_sha and local_sha != expected_sha:
            log(f"HASH MISMATCH: {src} hashes to {local_sha}, but the build recorded {expected_sha}.")
            log("  The local copy is not the file the build produced; aborting the upload so nothing is published.")
            try:
                s3.abort_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id)
            finally:
                state.remove()
            return 3
        expected_sha = expected_sha or local_sha
        log(f"  local sha256 {local_sha} verified")

    parts = [{"PartNumber": n, "ETag": done[n]} for n in range(1, n_parts + 1)]
    s3.complete_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id,
                                 MultipartUpload={"Parts": parts})
    # The upload id is consumed either way; the HEAD below decides the exit code.
    state.remove()
    head = s3.head_object(Bucket=bucket, Key=key)
    remote, rsha = int(head["ContentLength"]), server_sha(head)
    log(f"done. remote size {remote} bytes, local {size} bytes; remote sha256 {rsha}")
    if remote != size:
        log("SIZE MISMATCH after upload")
        return 2
    if expected_sha and rsha != expected_sha:
        log(f"HASH MISMATCH after upload: server records {rsha}, expected {expected_sha}")
        return 2
    return 0


def put_small(s3, src: str, bucket: str, key: str, sha: str) -> None:
    with open(src, "rb") as f:
        body = f.read()
    md5 = base64.b64encode(hashlib.md5(body).digest()).decode()
    s3.put_object(Bucket=bucket, Key=key, Body=body, ContentMD5=md5, Metadata={META_SHA: sha})


# --------------------------------------------------------------------------
# Entry points
# --------------------------------------------------------------------------

def upload_base(argv: list) -> int:
    ap = argparse.ArgumentParser(prog="upload_planet.py", description="Upload a planet archive to R2.")
    ap.add_argument("src", help="local file, normally world.mamaps")
    ap.add_argument("--key", default=KEY, help=f"object key (default {KEY})")
    ap.add_argument("--sha256", default=None,
                    help="expected SHA-256 (default: read <src>.sha256 if present)")
    ap.add_argument("--state-file", default=None,
                    help="resume record (default <src>.upload-state.json)")
    ap.add_argument("--no-resume", action="store_true", help="ignore any saved state and start over")
    ap.add_argument("--no-verify-local", action="store_true",
                    help="skip the whole-file SHA-256 (the server check then uses --sha256/sidecar only)")
    add_transfer_args(ap)
    args = ap.parse_args(argv)
    if not os.path.isfile(args.src):
        log(f"no such file: {args.src}")
        return 2
    part_mb, conc = transfer_settings(args)
    expected = args.sha256.lower() if args.sha256 else read_sidecar(args.src + ".sha256", os.path.basename(args.src))
    if expected:
        log(f"expected sha256 {expected}" + ("" if args.sha256 else f" (from {args.src}.sha256)"))
    state_path = args.state_file or args.src + ".upload-state.json"
    s3 = dry_run_client(state_path) if args.dry_run else make_client(conc)
    if args.dry_run:
        log("DRY RUN: no network calls; fake bucket at " + state_path + ".dryrun-bucket.json")
    return multipart_upload(s3, args.src, BUCKET, args.key, part_mb, conc, state_path,
                            expected_sha=expected, resume=not args.no_resume,
                            verify_local=not args.no_verify_local)


def publish_manifest(argv: list) -> int:
    """Upload overlay segments + write planet3.manifest.json.

    Args: <overlay-dir> --overlay-seq N --base-build-id HEX [--base-len N]
    The overlay dir holds overlay-*.mamaps (from mamaps_overlay) whose
    descriptor overlay.json carries the overlay_build_id. Segments upload
    to overlays/<seq>/overlay-SSSS.mamaps; the manifest lists their CDN
    URLs + lengths + SHA-256. Sections (chunked sidecars) ride the same
    manifest when present as <kind>.section files (not yet emitted).

    Server-side vs local: a segment R2 already holds with the same size and
    x-amz-meta-sha256 is kept, not re-uploaded; every segment is HEAD-checked
    (size + stored hash == local hash) before the manifest is written; and the
    base the manifest pins is HEAD-checked against --base-len / --base-sha256.
    """
    ap = argparse.ArgumentParser(prog="upload_planet.py --manifest")
    ap.add_argument("overlay_dir")
    ap.add_argument("--overlay-seq", type=int, required=True)
    ap.add_argument("--base-build-id", required=True,
                    help="hex base build_id the overlay applies to, e.g. 0x0355...")
    ap.add_argument("--base-len", type=int, default=None,
                    help="base length in bytes (else read from overlay.json sidecar)")
    ap.add_argument("--base-sha256", default=None,
                    help="expected SHA-256 of the base on R2 (checked against its x-amz-meta-sha256)")
    add_key_args(ap)
    add_transfer_args(ap)
    args = ap.parse_args(argv)
    part_mb, conc = transfer_settings(args)
    base_key, mkey = args.key, manifest_key(args)
    overlay_dir = args.overlay_dir
    descriptor_path = os.path.join(overlay_dir, "overlay.json")
    try:
        with open(descriptor_path) as f:
            descriptor = json.load(f)
    except (OSError, ValueError) as e:
        log(f"cannot read {descriptor_path}: {e} (run mamaps_overlay first)")
        return 2
    state_dir = os.path.join(overlay_dir, ".upload-state")
    if args.dry_run:
        os.makedirs(state_dir, exist_ok=True)
        s3 = dry_run_client(os.path.join(state_dir, "manifest"))
        log("DRY RUN: no network calls")
    else:
        s3 = make_client(conc)

    base_len = args.base_len if args.base_len is not None else descriptor.get("base_len")
    base_head = head_or_none(s3, BUCKET, base_key)
    if base_head is None:
        log(f"WARN: s3://{BUCKET}/{base_key} does not exist; the manifest would pin a missing base")
        if not args.dry_run:
            return 2
    else:
        blen, bsha = int(base_head["ContentLength"]), server_sha(base_head)
        log(f"base on server: {blen} bytes, sha256 {bsha}")
        if base_len and blen != int(base_len):
            log(f"BASE MISMATCH: server base is {blen} bytes, the overlay expects {base_len}")
            return 2
        if args.base_sha256 and bsha != args.base_sha256.lower():
            log(f"BASE MISMATCH: server base sha256 {bsha}, expected {args.base_sha256.lower()}")
            return 2

    segments = []
    for name in sorted(os.listdir(overlay_dir)):
        if not (name.startswith("overlay-") and name.endswith(".mamaps")):
            continue
        local = os.path.join(overlay_dir, name)
        key = f"overlays/{args.overlay_seq:04d}/{name}"
        size = os.path.getsize(local)
        digest = sha256_file(local)
        head = head_or_none(s3, BUCKET, key)
        if head is not None and int(head["ContentLength"]) == size and server_sha(head) == digest:
            log(f"kept s3://{BUCKET}/{key}: server already holds it (size + sha256 match)")
        else:
            if head is not None:
                log(f"replacing s3://{BUCKET}/{key}: server has {head['ContentLength']} bytes, "
                    f"sha256 {server_sha(head)}; local {size} bytes, {digest}")
            try:
                if size <= plan_part_size(size, part_mb):
                    log(f"uploading {local} ({size / 1e6:.1f} MB) to s3://{BUCKET}/{key}")
                    put_small(s3, local, BUCKET, key, digest)
                else:
                    rc = multipart_upload(s3, local, BUCKET, key, part_mb, conc,
                                          os.path.join(state_dir, name + ".json") if args.dry_run
                                          else local + ".upload-state.json",
                                          expected_sha=digest)
                    if rc != 0:
                        return rc
            except Exception as e:
                log(f"UPLOAD FAILED: {e}")
                return 1
            head = s3.head_object(Bucket=BUCKET, Key=key)
        remote, rsha = int(head["ContentLength"]), server_sha(head)
        if remote != size or rsha != digest:
            log(f"MISMATCH for {key}: server {remote} bytes / sha256 {rsha}; local {size} / {digest}")
            return 2
        seq = int(name[len("overlay-"):len("overlay-") + 4])
        segments.append({
            "url": f"{CDN}/{key}",
            "len": size,
            "sha256": digest,
            "seq": seq,
        })
    if not segments:
        log(f"no overlay-*.mamaps in {overlay_dir}")
        return 2
    tombstones = descriptor.get("tombstones", [])
    manifest = {
        "version": MANIFEST_VERSION,
        "base": {
            "url": f"{CDN}/{base_key}",
            "build_id": args.base_build_id,
            "len": args.base_len or 0,
        },
        "overlay_seq": args.overlay_seq,
        "overlay_build_id": descriptor.get("overlay_build_id", "0x0"),
        "segments": segments,
        "sections": [],
        "tombstones": tombstones,
        "min_client": MIN_CLIENT_COMPAT,
    }
    body = json.dumps(manifest, indent=1).encode()
    s3.put_object(Bucket=BUCKET, Key=mkey, Body=body,
                  ContentType="application/json")
    log(f"wrote s3://{BUCKET}/{mkey} "
        f"({len(segments)} segments, overlay_build_id={manifest['overlay_build_id']})")
    return 0


def publish_rebase(argv: list) -> int:
    """Publish a consolidation: a fresh full base becomes the world, and the
    overlay stack resets to empty.

    Run quarterly, or when the stacked overlays exceed ~20% of the base or
    the sequence depth passes 3. The new base uploads exactly like a normal
    planet publish (same KEY, new bytes, new build_id); the manifest then
    pins it with zero segments, so clients do one background full download
    and drop their old segments. No client-side rewrite pass exists by
    design (the no-2x guarantee stays trivially true).

    Args: --overlay-seq N --base-build-id HEX --base-len N [--dry-run]
    (N continues the sequence; segments/tombstones/sections are empty.)
    """
    ap = argparse.ArgumentParser(prog="upload_planet.py --rebase")
    ap.add_argument("--overlay-seq", type=int, required=True)
    ap.add_argument("--base-build-id", required=True)
    ap.add_argument("--base-len", type=int, required=True)
    ap.add_argument("--base-sha256", default=None,
                    help="expected SHA-256 of the base on R2 (checked against its x-amz-meta-sha256)")
    ap.add_argument("--dry-run", action="store_true")
    add_key_args(ap)
    args = ap.parse_args(argv)
    base_key, mkey = args.key, manifest_key(args)
    s3 = FakeS3() if args.dry_run else make_client(DEFAULT_CONCURRENCY)
    if not args.dry_run:
        # Never pin a base that is missing or is not the file we think it is.
        head = head_or_none(s3, BUCKET, base_key)
        if head is None:
            log(f"s3://{BUCKET}/{base_key} does not exist; refusing to write a manifest that pins it")
            return 2
        blen, bsha = int(head["ContentLength"]), server_sha(head)
        log(f"base on server: {blen} bytes, sha256 {bsha}")
        if blen != args.base_len:
            log(f"BASE MISMATCH: server base is {blen} bytes, --base-len says {args.base_len}")
            return 2
        if args.base_sha256 and bsha != args.base_sha256.lower():
            log(f"BASE MISMATCH: server base sha256 {bsha}, expected {args.base_sha256.lower()}")
            return 2
    manifest = {
        "version": MANIFEST_VERSION,
        "base": {
            "url": f"{CDN}/{base_key}",
            "build_id": args.base_build_id,
            "len": args.base_len,
        },
        "overlay_seq": args.overlay_seq,
        "overlay_build_id": "0x0",
        "segments": [],
        "sections": [],
        "tombstones": [],
        "min_client": MIN_CLIENT_COMPAT,
    }
    body = json.dumps(manifest, indent=1).encode()
    s3.put_object(Bucket=BUCKET, Key=mkey, Body=body,
                  ContentType="application/json")
    log(f"{'DRY RUN: would write' if args.dry_run else 'wrote'} s3://{BUCKET}/{mkey} "
        f"rebase seq={args.overlay_seq} (empty overlay; clients refresh the base once)")
    return 0


def main() -> int:
    if len(sys.argv) >= 2 and sys.argv[1] == "--manifest":
        return publish_manifest(sys.argv[2:])
    if len(sys.argv) >= 2 and sys.argv[1] == "--rebase":
        return publish_rebase(sys.argv[2:])
    if len(sys.argv) < 2:
        log(f"usage: {os.path.basename(sys.argv[0])} <local-file> [--key K] [--dry-run] [--part-size-mb N] "
            f"[--concurrency N] [--sha256 HEX] [--state-file PATH] [--no-resume]")
        log(f"   or: {os.path.basename(sys.argv[0])} --manifest <overlay-dir> --overlay-seq N "
            f"--base-build-id HEX [--base-len N] [--base-sha256 HEX] [--key K] [--manifest-key M] [--dry-run]")
        log(f"   or: {os.path.basename(sys.argv[0])} --rebase --overlay-seq N --base-build-id HEX "
            f"--base-len N [--base-sha256 HEX] [--key K] [--manifest-key M] [--dry-run]")
        return 2
    return upload_base(sys.argv[1:])


if __name__ == "__main__":
    sys.exit(main())
