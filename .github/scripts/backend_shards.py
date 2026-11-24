#!/usr/bin/env python3
"""Split the backend's integration tests into shards, and check the shards ran every test once.

Usage: backend_shards.py select <k>/<n>
           reads `cargo test -- --list --format terse` on stdin and prints every nth test
           name, starting at the kth
       backend_shards.py check <dir> <engine>=<shards>...
           <dir>/backend-rs-<engine>-<k>/tests.txt is the `cargo test` output of shard k
"""

import re
import sys
from collections import Counter
from pathlib import Path

LISTED = re.compile(r"^(\S+): test$", re.MULTILINE)
RAN = re.compile(r"^test (\S+)(?: - should panic)? \.\.\. (?:ok|ignored)", re.MULTILINE)
RESULT = re.compile(r"^test result: (\S+)\. (\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out", re.MULTILINE)


def select(listing, shard):
    """libtest lists the names sorted, so each shard gets a slice of every module."""
    k, n = (int(part) for part in shard.split("/"))
    if not 1 <= k <= n:
        raise ValueError(f"shard {shard}: expected k/n with 1 <= k <= n")
    return LISTED.findall(listing)[k - 1 :: n]


def legs(arguments):
    """`postgres=3 sqlite=2` as {engine: shards}."""
    expected = {}
    for argument in arguments:
        engine, _, shards = argument.partition("=")
        if not engine or engine in expected or not shards.isdigit() or int(shards) < 1:
            raise ValueError(f"{argument}: expected <engine>=<shards>, each engine once, at least one shard")
        expected[engine] = int(shards)
    return expected


def problems(reports, expected):
    """`expected` maps an engine to its number of shards. A shard's result line also counts the
    tests it left out, so every shard states how many tests the binary holds."""
    reports = Path(reports)
    wanted = {f"backend-rs-{engine}-{k}" for engine, shards in expected.items() for k in range(1, shards + 1)}
    present = {path.name for path in reports.iterdir()} if reports.is_dir() else set()
    found = [f"unexpected output: {name}" for name in sorted(present - wanted)]
    for engine, shards in sorted(expected.items()):
        ran, totals, before = Counter(), set(), len(found)
        for name in (f"backend-rs-{engine}-{k}" for k in range(1, shards + 1)):
            if not (reports / name / "tests.txt").is_file():
                found.append(f"no output for {name}")
                continue
            log = (reports / name / "tests.txt").read_text(encoding="utf-8", errors="replace")
            names, statuses = RAN.findall(log), [result[0] for result in RESULT.findall(log)]
            if statuses != ["ok"]:
                found.append(f"{name}: expected one `test result: ok` line, found {statuses}")
                continue
            passed, failed, ignored, measured, filtered = map(int, RESULT.search(log).groups()[1:])
            if len(names) != passed + ignored:
                found.append(f"{name}: {len(names)} test lines for {passed + ignored} tests in the result")
            totals.add(passed + failed + ignored + measured + filtered)
            ran.update(names)
        twice = sorted(name for name, count in ran.items() if count > 1)
        if twice:
            found.append(f"{engine}: ran in two shards: " + ", ".join(twice))
        if len(totals) > 1:
            found.append(f"{engine}: the shards disagree on the number of tests: {sorted(totals)}")
        elif totals and (len(ran) != max(totals) or not max(totals)):
            found.append(f"{engine}: {len(ran)} of {max(totals)} tests ran")
        elif len(found) == before:
            print(f"{engine}: {max(totals)} tests, each ran once in {shards} shards")
    return found


def main(argv):
    try:
        if len(argv) == 2 and argv[0] == "select":
            names = select(sys.stdin.read(), argv[1])
            if names:
                print(" ".join(names))
                return 0
            found = ["no test to select: the listing is empty"]
        elif len(argv) > 2 and argv[0] == "check":
            found = problems(argv[1], legs(argv[2:]))
        else:
            print(__doc__, file=sys.stderr)
            return 2
    except ValueError as error:
        found = [str(error)]
    for problem in found:
        print(f"::error::{problem}", file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
