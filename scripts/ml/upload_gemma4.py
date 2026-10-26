"""Upload the Gemma 4 maml release set to R2 under models/gemma4/ and verify it over HTTPS.

Usage: python scripts/ml/upload_gemma4.py <release-dir>
       python scripts/ml/upload_gemma4.py --find-bucket

<release-dir> holds the six FILES plus SHA256SUMS (`<sha256>  <size>  <name>` per line). The
bucket is the one that already serves https://data.maproj.org/models/, found by locating
LEGACY_KEY on each bucket the credentials can list (override with R2_MODELS_BUCKET). An object
that already exists with the same size and sha256 is skipped; one that differs is refused, so
nothing else on the bucket is overwritten. Nothing is ever deleted.

After upload each file is checked through the CDN: HEAD returns 200 with the right
Content-Length, a Range request returns 206, and files up to VERIFY_FULL_BYTES are downloaded
whole and hashed.

Required env (never put keys on the command line):
  R2_ENDPOINT           S3 API endpoint, e.g. https://<ACCOUNT_ID>.r2.cloudflarestorage.com
  R2_ACCESS_KEY_ID      R2 access key id
  R2_SECRET_ACCESS_KEY  R2 secret access key
Optional env:
  R2_MODELS_BUCKET      Skip the bucket search
  R2_CDN                Default: https://data.maproj.org
"""
import hashlib
import os
import sys
import threading
import urllib.request

import boto3
from boto3.s3.transfer import TransferConfig
from botocore.exceptions import ClientError

FILES = [
    "gemma4_text.maml",
    "gemma4_embed.maml",
    "gemma4_head.maml",
    "gemma4_vision.maml",
    "gemma4_audio.maml",
    "gemma4_tokenizer.spm1",
]
PREFIX = "models/gemma4/"
LEGACY_KEY = "models/gemma-4-E2B-it.litertlm"
CDN = os.environ.get("R2_CDN", "https://data.maproj.org")
PART_SIZE = 100 * 1024 * 1024
VERIFY_FULL_BYTES = 500 * 1024 * 1024
# The CDN answers Python-urllib's default User-Agent with 403.
USER_AGENT = {"User-Agent": "modern-apps-upload-gemma4/1"}


def client():
    missing = [v for v in ("R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY")
               if not os.environ.get(v)]
    if missing:
        raise SystemExit(f"missing env: {', '.join(missing)} (see module docstring)")
    return boto3.client(
        "s3",
        endpoint_url=os.environ["R2_ENDPOINT"],
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
    )


def head(s3, bucket, key):
    try:
        return s3.head_object(Bucket=bucket, Key=key)
    except ClientError as e:
        if e.response.get("Error", {}).get("Code") in ("404", "NoSuchKey", "NotFound"):
            return None
        raise


def find_bucket(s3):
    pinned = os.environ.get("R2_MODELS_BUCKET")
    if pinned:
        if head(s3, pinned, LEGACY_KEY) is None:
            raise SystemExit(f"R2_MODELS_BUCKET={pinned} holds no {LEGACY_KEY}")
        return pinned
    names = [b["Name"] for b in s3.list_buckets().get("Buckets", [])]
    found = []
    for name in names:
        try:
            meta = head(s3, name, LEGACY_KEY)
        except ClientError as e:
            print(f"  {name}: {e.response.get('Error', {}).get('Code')}", flush=True)
            continue
        print(f"  {name}: {'has' if meta else 'no'} {LEGACY_KEY}"
              + (f" ({meta['ContentLength']} bytes)" if meta else ""), flush=True)
        if meta:
            found.append(name)
    if len(found) != 1:
        raise SystemExit(f"{len(found)} buckets hold {LEGACY_KEY} ({found}); set R2_MODELS_BUCKET")
    return found[0]


def read_sums(release):
    sums = {}
    with open(os.path.join(release, "SHA256SUMS")) as f:
        for line in f:
            digest, size, name = line.split()
            sums[name] = (digest, int(size))
    missing = [n for n in FILES if n not in sums]
    if missing:
        raise SystemExit(f"SHA256SUMS lacks {missing}")
    return sums


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def upload(s3, bucket, release, sums):
    config = TransferConfig(multipart_threshold=PART_SIZE, multipart_chunksize=PART_SIZE,
                            max_concurrency=10, use_threads=True)
    for name in FILES:
        local = os.path.join(release, name)
        digest, size = sums[name]
        if os.path.getsize(local) != size:
            raise SystemExit(f"{local}: {os.path.getsize(local)} bytes, SHA256SUMS says {size}")
        key = PREFIX + name
        existing = head(s3, bucket, key)
        if existing is not None:
            same = (existing["ContentLength"] == size
                    and existing.get("Metadata", {}).get("sha256") == digest)
            if same:
                print(f"skip {key}: already {size} bytes, sha256 {digest}", flush=True)
                continue
            raise SystemExit(f"{key} exists with different content; refusing to overwrite")
        print(f"uploading {local} ({size / 1e6:.1f} MB) to s3://{bucket}/{key}", flush=True)
        lock, done, mark = threading.Lock(), [0], [0.0]

        def progress(n, size=size):
            with lock:
                done[0] += n
                if done[0] - mark[0] >= 5e8 or done[0] >= size:
                    mark[0] = done[0]
                    print(f"  {done[0] / 1e6:.0f}/{size / 1e6:.0f} MB", flush=True)

        s3.upload_file(local, bucket, key, Config=config, Callback=progress,
                       ExtraArgs={"ContentType": "application/octet-stream",
                                  "Metadata": {"sha256": digest}})
        remote = s3.head_object(Bucket=bucket, Key=key)["ContentLength"]
        if remote != size:
            raise SystemExit(f"{key}: remote {remote} bytes, local {size}")


def verify_https(sums):
    ok = True
    for name in FILES:
        digest, size = sums[name]
        url = f"{CDN}/{PREFIX}{name}"
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD", headers=USER_AGENT)) as r:
            length = int(r.headers.get("Content-Length", -1))
            head_ok = r.status == 200 and length == size
        ranged = urllib.request.Request(url, headers={**USER_AGENT, "Range": "bytes=0-15"})
        with urllib.request.urlopen(ranged) as r:
            body = r.read()
            range_ok = r.status == 206 and len(body) == 16
        full = "skipped (large)"
        if size <= VERIFY_FULL_BYTES:
            h = hashlib.sha256()
            with urllib.request.urlopen(urllib.request.Request(url, headers=USER_AGENT)) as r:
                while chunk := r.read(1 << 20):
                    h.update(chunk)
            full = "sha256 ok" if h.hexdigest() == digest else f"SHA256 MISMATCH {h.hexdigest()}"
            ok &= h.hexdigest() == digest
        ok &= head_ok and range_ok
        print(f"{url}: HEAD {'ok' if head_ok else f'BAD ({length})'}, "
              f"range {'206' if range_ok else 'BAD'}, {full}", flush=True)
    return ok


def main():
    if len(sys.argv) == 2 and sys.argv[1] == "--find-bucket":
        print(find_bucket(client()), flush=True)
        return 0
    if len(sys.argv) != 2:
        print(__doc__.splitlines()[2], flush=True)
        return 2
    release = sys.argv[1]
    sums = read_sums(release)
    s3 = client()
    bucket = find_bucket(s3)
    print(f"bucket {bucket} serves {CDN}/models/", flush=True)
    upload(s3, bucket, release, sums)
    return 0 if verify_https(sums) else 1


if __name__ == "__main__":
    sys.exit(main())
