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


def problems(reports, expected):
    """`expected` maps an engine to its number of shards. A shard's result line also counts the
    tests it left out, so every shard states how many tests the binary holds."""
    reports = Path(reports)
    wanted = {f"backend-rs-{engine}-{k}" for engine, shards in expected.items() for k in range(1, shards + 1)}
    present = {path.parent.name for path in reports.glob("*/tests.txt")}
    found = [f"no output for {name}" for name in sorted(wanted - present)]
    found += [f"unexpected output: {name}" for name in sorted(present - wanted)]
    for engine, shards in sorted(expected.items()):
        ran, totals = Counter(), set()
        for name in sorted(wanted & present):
            if name.rsplit("-", 1)[0] != f"backend-rs-{engine}":
                continue
            log = (reports / name / "tests.txt").read_text(encoding="utf-8", errors="replace")
            names, results = RAN.findall(log), RESULT.findall(log)
            if len(results) != 1 or results[0][0] != "ok":
                found.append(f"{name}: expected one `test result: ok` line, found {len(results)}")
                continue
            passed, failed, ignored, measured, filtered = map(int, results[0][1:])
            if len(names) != passed + ignored:
                found.append(f"{name}: {len(names)} test lines for {passed + ignored} tests in the result")
            totals.add(passed + failed + ignored + measured + filtered)
            ran.update(names)
        twice = sorted(name for name, count in ran.items() if count > 1)
        if twice:
            found.append(f"{engine}: ran in two shards: " + ", ".join(twice))
        if len(totals) > 1:
            found.append(f"{engine}: the shards disagree on the number of tests: {sorted(totals)}")
        elif totals:
            total = totals.pop()
            if len(ran) != total or not total:
                found.append(f"{engine}: {len(ran)} of {total} tests ran")
            elif not twice:
                print(f"{engine}: {total} tests, each ran once in {shards} shards")
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
            found = problems(argv[1], {engine: int(shards) for engine, shards in (arg.split("=") for arg in argv[2:])})
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
