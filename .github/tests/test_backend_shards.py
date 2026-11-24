"""The backend shards: every listed test lands in one shard, and the check fails unless each ran once."""

import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("backend_shards", ROOT / ".github/scripts/backend_shards.py")
backend_shards = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(backend_shards)

NAMES = [f"module_{i % 4}::case_{i:02}" for i in range(11)]
LISTING = "".join(f"{name}: test\n" for name in NAMES) + "\n11 tests, 0 benchmarks\n"


class SelectTest(unittest.TestCase):
    def test_the_shards_partition_the_listing(self):
        for total in range(1, 6):
            with self.subTest(total=total):
                shards = [backend_shards.select(LISTING, f"{k}/{total}") for k in range(1, total + 1)]
                self.assertEqual(sorted(name for shard in shards for name in shard), sorted(NAMES))
                self.assertLessEqual(max(map(len, shards)) - min(map(len, shards)), 1)

    def test_rejects_a_shard_outside_its_total(self):
        for shard in ("0/3", "4/3"):
            with self.subTest(shard=shard), self.assertRaises(ValueError):
                backend_shards.select(LISTING, shard)

    def test_an_empty_listing_is_an_error(self):
        # A failed build lists nothing: `--exact` with no name would run every test.
        with mock.patch("sys.stdin", io.StringIO("error: could not compile `picsou`\n")):
            self.assertEqual(backend_shards.main(["select", "1/2"]), 1)


class CheckTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.reports = Path(self._tmp.name)

    def report(self, folder, names, total=len(NAMES)):
        """What `cargo test` prints for one shard: build lines, one line per test, the result."""
        lines = ["   Compiling picsou v0.1.0", f"running {len(names)} tests"]
        lines += [f"test {name} ... ok" for name in names]
        lines += ["", f"test result: ok. {len(names)} passed; 0 failed; 0 ignored; 0 measured; {total - len(names)} filtered out; finished in 1.00s"]
        path = self.reports / folder / "tests.txt"
        path.parent.mkdir(parents=True)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def run_check(self):
        return backend_shards.main(["check", str(self.reports)])

    def test_passes_when_each_engine_ran_every_test_once(self):
        for engine, total in (("postgres", 3), ("sqlite", 2)):
            for k in range(1, total + 1):
                self.report(f"backend-rs-{engine}-{k}", backend_shards.select(LISTING, f"{k}/{total}"))
        self.assertEqual(self.run_check(), 0)

    def test_fails_when_a_shard_is_missing(self):
        self.report("backend-rs-postgres-1", NAMES[0::2])
        self.assertEqual(self.run_check(), 1)

    def test_fails_when_a_test_ran_in_two_shards(self):
        self.report("backend-rs-postgres-1", NAMES[0::2] + NAMES[1:2])
        self.report("backend-rs-postgres-2", NAMES[1::2])
        self.assertEqual(self.run_check(), 1)

    def test_fails_when_the_shards_come_from_different_binaries(self):
        self.report("backend-rs-sqlite-1", NAMES[0::2])
        self.report("backend-rs-sqlite-2", NAMES[1::2], total=len(NAMES) + 1)
        self.assertEqual(self.run_check(), 1)

    def test_counts_an_ignored_test_and_one_that_must_panic(self):
        path = self.reports / "backend-rs-sqlite-1" / "tests.txt"
        path.parent.mkdir(parents=True)
        path.write_text(
            "test a::slow ... ignored\ntest a::guard - should panic ... ok\n"
            "test result: ok. 1 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out; finished in 0.10s\n",
            encoding="utf-8",
        )
        self.assertEqual(self.run_check(), 0)

    def test_fails_without_any_report(self):
        self.assertEqual(self.run_check(), 1)


if __name__ == "__main__":
    unittest.main()
