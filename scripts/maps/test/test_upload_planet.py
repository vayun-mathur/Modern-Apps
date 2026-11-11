"""Unit tests for scripts/maps/upload_planet.py, against its in-process FakeS3.

No network and no boto3 needed:

    python3 -m unittest scripts/maps/test/test_upload_planet.py
"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import random
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("upload_planet", os.path.join(HERE, "..", "upload_planet.py"))
up = importlib.util.module_from_spec(spec)
spec.loader.exec_module(up)

MIB = up.MIB
up.RETRY_BASE_S = 0.0   # the fake's dropped link fails every retry; don't sleep through them


def load(path):
    with open(path) as f:
        return json.load(f)


def save(path, obj):
    with open(path, "w") as f:
        json.dump(obj, f)


def quiet(fn, *a, **kw):
    with contextlib.redirect_stdout(io.StringIO()) as out:
        rc = fn(*a, **kw)
    return rc, out.getvalue()


class Files(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        self.src = os.path.join(self.dir, "world.mamaps")
        # 23 MiB of non-repeating bytes: five 5 MiB parts, the last 3 MiB.
        data = random.Random(23).randbytes(23 * MIB)
        with open(self.src, "wb") as f:
            f.write(data)
        self.sha = hashlib.sha256(data).hexdigest()
        self.state = self.src + ".upload-state.json"
        self.bucket_file = self.state + ".dryrun-bucket.json"

    def tearDown(self):
        self.tmp.cleanup()

    def upload(self, s3, **kw):
        kw.setdefault("expected_sha", None)
        return quiet(up.multipart_upload, s3, self.src, "maps", "planet3.mamaps", 5, 3, self.state, **kw)

    def parts_sent(self, s3):
        return sorted(n for op, n in s3.calls if op == "part")


class PartPlanning(unittest.TestCase):
    def test_planet_sized_file_uses_64_mib_parts(self):
        size = 81_331_811_296
        part = up.plan_part_size(size, 64)
        self.assertEqual(part, 64 * MIB)
        self.assertEqual(-(-size // part), 1212)

    def test_part_size_rises_to_stay_under_the_part_budget(self):
        size = 2 * 10**12
        part = up.plan_part_size(size, 64)
        self.assertLessEqual(-(-size // part), up.TARGET_MAX_PARTS)
        self.assertEqual(part % MIB, 0)

    def test_requests_below_the_s3_floor_are_raised(self):
        self.assertEqual(up.plan_part_size(100, 1), up.MIN_PART)


class Sidecar(Files):
    def write(self, text):
        with open(self.src + ".sha256", "w") as f:
            f.write(text)

    def test_sha256sum_line_is_read(self):
        self.write(f"{self.sha}  world.mamaps\n")
        self.assertEqual(up.read_sidecar(self.src + ".sha256", "world.mamaps"), self.sha)

    def test_absent_sidecar_is_none(self):
        self.assertIsNone(up.read_sidecar(self.src + ".sha256", "world.mamaps"))

    def test_a_sidecar_for_another_file_is_refused(self):
        self.write(f"{self.sha}  other.mamaps\n")
        with self.assertRaises(SystemExit):
            up.read_sidecar(self.src + ".sha256", "world.mamaps")

    def test_a_malformed_sidecar_is_refused(self):
        self.write("not-a-hash  world.mamaps\n")
        with self.assertRaises(SystemExit):
            up.read_sidecar(self.src + ".sha256", "world.mamaps")


class Upload(Files):
    def test_upload_stores_and_verifies_the_hash(self):
        s3 = up.FakeS3()
        rc, out = self.upload(s3, expected_sha=self.sha)
        self.assertEqual(rc, 0, out)
        head = s3.head_object(Bucket="maps", Key="planet3.mamaps")
        self.assertEqual(head["ContentLength"], os.path.getsize(self.src))
        self.assertEqual(head["Metadata"]["sha256"], self.sha)
        self.assertEqual(self.parts_sent(s3), [1, 2, 3, 4, 5])
        self.assertFalse(os.path.exists(self.state), "state is removed after a verified upload")

    def test_without_a_sidecar_the_hash_is_computed_first(self):
        s3 = up.FakeS3()
        rc, out = self.upload(s3)
        self.assertEqual(rc, 0, out)
        self.assertEqual(s3.head_object(Bucket="maps", Key="planet3.mamaps")["Metadata"]["sha256"], self.sha)

    def test_a_local_copy_that_does_not_match_the_build_is_never_completed(self):
        s3 = up.FakeS3()
        rc, out = self.upload(s3, expected_sha="0" * 64)
        self.assertEqual(rc, 3, out)
        self.assertIn("HASH MISMATCH", out)
        self.assertIn(("abort", "planet3.mamaps"), s3.calls)
        self.assertNotIn(("complete", "planet3.mamaps"), s3.calls)
        self.assertIsNone(up.head_or_none(s3, "maps", "planet3.mamaps"))


class Resume(Files):
    def test_an_interrupted_upload_resumes_with_only_the_missing_parts(self):
        rc, out = self.upload(up.FakeS3(self.bucket_file, fail_after_parts=2), expected_sha=self.sha)
        self.assertEqual(rc, 1, out)
        self.assertIn("rerun the same command to resume", out)
        st = load(self.state)
        self.assertEqual(len(st["parts"]), 2)
        s3 = up.FakeS3(self.bucket_file)
        rc, out = self.upload(s3, expected_sha=self.sha)
        self.assertEqual(rc, 0, out)
        self.assertIn("resuming upload", out)
        self.assertEqual(len(self.parts_sent(s3)), 3, "only the three missing parts are sent")
        self.assertEqual(s3.head_object(Bucket="maps", Key="planet3.mamaps")["Metadata"]["sha256"], self.sha)

    def test_a_part_the_state_file_missed_is_recovered_from_the_server(self):
        self.upload(up.FakeS3(self.bucket_file, fail_after_parts=3), expected_sha=self.sha)
        st = load(self.state)
        dropped = sorted(st["parts"])[0]
        del st["parts"][dropped]       # crash after the upload, before the save
        save(self.state, st)
        s3 = up.FakeS3(self.bucket_file)
        rc, out = self.upload(s3, expected_sha=self.sha)
        self.assertEqual(rc, 0, out)
        self.assertNotIn(int(dropped), self.parts_sent(s3), "re-hashed locally, kept, not re-sent")
        self.assertEqual(len(self.parts_sent(s3)), 2)

    def test_a_server_part_with_the_wrong_bytes_is_re_sent(self):
        self.upload(up.FakeS3(self.bucket_file, fail_after_parts=2), expected_sha=self.sha)
        bucket = load(self.bucket_file)
        uid = next(iter(bucket["uploads"]))
        bad = sorted(bucket["uploads"][uid]["parts"])[0]
        bucket["uploads"][uid]["parts"][bad][1] = '"deadbeef"'
        save(self.bucket_file, bucket)
        s3 = up.FakeS3(self.bucket_file)
        rc, out = self.upload(s3, expected_sha=self.sha)
        self.assertEqual(rc, 0, out)
        self.assertIn(int(bad), self.parts_sent(s3))

    def test_a_changed_file_aborts_the_stale_upload_and_starts_over(self):
        self.upload(up.FakeS3(self.bucket_file, fail_after_parts=2), expected_sha=self.sha)
        os.utime(self.src, ns=(1, 1))
        s3 = up.FakeS3(self.bucket_file)
        rc, out = self.upload(s3, expected_sha=self.sha)
        self.assertEqual(rc, 0, out)
        self.assertIn(("abort", "planet3.mamaps"), s3.calls)
        self.assertEqual(self.parts_sent(s3), [1, 2, 3, 4, 5])

    def test_an_expired_upload_starts_over(self):
        self.upload(up.FakeS3(self.bucket_file, fail_after_parts=2), expected_sha=self.sha)
        bucket = load(self.bucket_file)
        bucket["uploads"] = {}
        save(self.bucket_file, bucket)
        s3 = up.FakeS3(self.bucket_file)
        rc, out = self.upload(s3, expected_sha=self.sha)
        self.assertEqual(rc, 0, out)
        self.assertIn("no longer exists", out)
        self.assertEqual(self.parts_sent(s3), [1, 2, 3, 4, 5])

    def test_a_crash_after_complete_is_recognised_as_done(self):
        s3 = up.FakeS3(self.bucket_file)
        self.assertEqual(self.upload(s3, expected_sha=self.sha)[0], 0)
        # Put back a state file for the (now completed) upload id.
        save(self.state, {"bucket": "maps", "key": "planet3.mamaps", "size": os.path.getsize(self.src),
                          "mtime_ns": os.stat(self.src).st_mtime_ns, "part_size": 5 * MIB,
                          "upload_id": "dryrun-gone", "sha256": self.sha, "parts": {}})
        s3 = up.FakeS3(self.bucket_file)
        rc, out = self.upload(s3, expected_sha=self.sha)
        self.assertEqual(rc, 0, out)
        self.assertIn("already completed", out)
        self.assertEqual(self.parts_sent(s3), [])

    def test_no_resume_starts_over(self):
        self.upload(up.FakeS3(self.bucket_file, fail_after_parts=2), expected_sha=self.sha)
        s3 = up.FakeS3(self.bucket_file)
        rc, out = self.upload(s3, expected_sha=self.sha, resume=False)
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.parts_sent(s3), [1, 2, 3, 4, 5])


class Cli(Files):
    def test_dry_run_reads_the_sidecar_and_makes_no_client(self):
        with open(self.src + ".sha256", "w") as f:
            f.write(f"{self.sha}  world.mamaps\n")
        env = {k: os.environ.pop(k) for k in ("R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY") if k in os.environ}
        try:
            rc, out = quiet(up.upload_base, [self.src, "--dry-run", "--part-size-mb", "5", "--concurrency", "4"])
        finally:
            os.environ.update(env)
        self.assertEqual(rc, 0, out)
        self.assertIn("DRY RUN", out)
        self.assertIn(f"remote sha256 {self.sha}", out)


class Manifest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        save(os.path.join(self.dir, "overlay.json"), {"overlay_build_id": "0x1234", "tombstones": []})
        self.segs = {}
        for i in (1, 2):
            p = os.path.join(self.dir, f"overlay-{i:04d}.mamaps")
            body = bytes([i]) * (1000 * i)
            with open(p, "wb") as f:
                f.write(body)
            self.segs[f"overlays/0007/overlay-{i:04d}.mamaps"] = hashlib.sha256(body).hexdigest()
        os.makedirs(os.path.join(self.dir, ".upload-state"))
        self.bucket_file = os.path.join(self.dir, ".upload-state", "manifest.dryrun-bucket.json")
        save(self.bucket_file, {"uploads": {}, "objects": {
            "maps/planet3.mamaps": {"size": 5000, "meta": {"sha256": "ab" * 32}, "etag": '"x"'}}})

    def tearDown(self):
        self.tmp.cleanup()

    def run_manifest(self, *extra):
        return quiet(up.publish_manifest, [self.dir, "--overlay-seq", "7", "--base-build-id", "0xbeef",
                                           "--base-len", "5000", "--dry-run", *extra])

    def bucket(self):
        return load(self.bucket_file)["objects"]

    def test_segments_upload_with_their_hash_and_the_manifest_lists_them(self):
        rc, out = self.run_manifest("--base-sha256", "ab" * 32)
        self.assertEqual(rc, 0, out)
        objs = self.bucket()
        for key, sha in self.segs.items():
            self.assertEqual(objs[f"maps/{key}"]["meta"]["sha256"], sha)
        self.assertIn("maps/planet3.manifest.json", objs)

    def test_a_rerun_keeps_segments_the_server_already_holds(self):
        self.run_manifest()
        rc, out = self.run_manifest()
        self.assertEqual(rc, 0, out)
        self.assertEqual(out.count("kept s3://"), 2)
        self.assertNotIn("uploading", out)

    def test_a_server_segment_with_another_hash_is_replaced(self):
        self.run_manifest()
        b = load(self.bucket_file)
        b["objects"]["maps/overlays/0007/overlay-0001.mamaps"]["meta"]["sha256"] = "00" * 32
        save(self.bucket_file, b)
        rc, out = self.run_manifest()
        self.assertEqual(rc, 0, out)
        self.assertIn("replacing s3://maps/overlays/0007/overlay-0001.mamaps", out)
        self.assertEqual(self.bucket()["maps/overlays/0007/overlay-0001.mamaps"]["meta"]["sha256"],
                         self.segs["overlays/0007/overlay-0001.mamaps"])

    def test_a_base_with_the_wrong_length_or_hash_blocks_the_manifest(self):
        rc, out = quiet(up.publish_manifest, [self.dir, "--overlay-seq", "7", "--base-build-id", "0xbeef",
                                              "--base-len", "4999", "--dry-run"])
        self.assertEqual(rc, 2, out)
        self.assertIn("BASE MISMATCH", out)
        rc, out = self.run_manifest("--base-sha256", "cd" * 32)
        self.assertEqual(rc, 2, out)
        self.assertNotIn("maps/planet3.manifest.json", self.bucket())


class ManifestKey(unittest.TestCase):
    """--key / --manifest-key: a planetN stack never touches planet3.*."""

    def test_the_manifest_key_follows_the_base_key(self):
        self.assertEqual(up.manifest_key_for("planet3.mamaps"), "planet3.manifest.json")
        self.assertEqual(up.manifest_key_for("planet4.mamaps"), "planet4.manifest.json")
        self.assertEqual(up.manifest_key_for("staging/x.bin"), up.MANIFEST_KEY)

    def test_overlay_manifest_for_planet4_pins_planet4_and_leaves_planet3_alone(self):
        m = Manifest("test_segments_upload_with_their_hash_and_the_manifest_lists_them")
        m.setUp()
        try:
            b = load(m.bucket_file)
            b["objects"]["maps/planet4.mamaps"] = {"size": 5000, "meta": {"sha256": "ef" * 32}, "etag": '"y"'}
            save(m.bucket_file, b)
            rc, out = m.run_manifest("--key", "planet4.mamaps", "--base-sha256", "ef" * 32)
            self.assertEqual(rc, 0, out)
            objs = m.bucket()
            self.assertIn("maps/planet4.manifest.json", objs)
            self.assertNotIn("maps/planet3.manifest.json", objs)
            # an explicit --manifest-key wins
            rc, out = m.run_manifest("--key", "planet4.mamaps", "--manifest-key", "beta.manifest.json")
            self.assertEqual(rc, 0, out)
            self.assertIn("maps/beta.manifest.json", m.bucket())
        finally:
            m.tearDown()

    def rebase(self, s3, *extra):
        bodies = {}
        real_put = s3.put_object

        def put(Bucket, Key, Body, **kw):
            bodies[Key] = json.loads(bytes(Body))
            return real_put(Bucket=Bucket, Key=Key, Body=Body, **kw)
        s3.put_object = put
        orig = up.make_client
        up.make_client = lambda *_a, **_k: s3
        try:
            rc, out = quiet(up.publish_rebase, ["--overlay-seq", "0", "--base-build-id", "0x2ab5",
                                                "--base-len", "5000", *extra])
        finally:
            up.make_client = orig
        return rc, out, bodies

    def test_rebase_writes_an_empty_stack_for_the_named_base(self):
        s3 = up.FakeS3()
        s3.objects["maps/planet4.mamaps"] = {"size": 5000, "meta": {"sha256": "ef" * 32}, "etag": '"y"'}
        rc, out, bodies = self.rebase(s3, "--key", "planet4.mamaps", "--base-sha256", "ef" * 32)
        self.assertEqual(rc, 0, out)
        self.assertEqual(list(bodies), ["planet4.manifest.json"])
        m = bodies["planet4.manifest.json"]
        self.assertEqual(m["base"], {"url": f"{up.CDN}/planet4.mamaps", "build_id": "0x2ab5", "len": 5000})
        self.assertEqual((m["segments"], m["overlay_seq"]), ([], 0))

    def test_rebase_refuses_a_missing_or_different_base(self):
        rc, out, bodies = self.rebase(up.FakeS3(), "--key", "planet4.mamaps")
        self.assertEqual((rc, bodies), (2, {}), out)
        s3 = up.FakeS3()
        s3.objects["maps/planet4.mamaps"] = {"size": 4999, "meta": {"sha256": "ef" * 32}, "etag": '"y"'}
        rc, out, bodies = self.rebase(s3, "--key", "planet4.mamaps")
        self.assertEqual((rc, bodies), (2, {}), out)
        self.assertIn("BASE MISMATCH", out)
        s3.objects["maps/planet4.mamaps"]["size"] = 5000
        rc, out, bodies = self.rebase(s3, "--key", "planet4.mamaps", "--base-sha256", "00" * 32)
        self.assertEqual((rc, bodies), (2, {}), out)

    def test_without_key_the_defaults_are_unchanged(self):
        s3 = up.FakeS3()
        s3.objects["maps/planet3.mamaps"] = {"size": 5000, "meta": {}, "etag": '"z"'}
        rc, out, bodies = self.rebase(s3)
        self.assertEqual(rc, 0, out)
        self.assertEqual(list(bodies), ["planet3.manifest.json"])
        self.assertEqual(bodies["planet3.manifest.json"]["base"]["url"], f"{up.CDN}/planet3.mamaps")


if __name__ == "__main__":
    unittest.main()
