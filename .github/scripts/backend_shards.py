#!/usr/bin/env python3
"""Split the backend's integration tests into shards, and check the shards ran every test once.

Usage: backend_shards.py select <k>/<n>   reads `cargo test -- --list --format terse` on stdin and
                                          prints every nth test name, starting at the kth
       backend_shards.py check <dir>      <dir>/backend-rs-<engine>-<k>/tests.txt is one shard's
                                          `cargo test` output
"""

import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

LISTED = re.compile(r"^(\S+): test$", re.MULTILINE)
RAN = re.compile(r"^test (\S+)(?: - should panic)? \.\.\. (?:ok|ignored)", re.MULTILINE)
RESULT = re.compile(r"^test result: \S+ (\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out", re.MULTILINE)
REPORT = re.compile(r"^backend-rs-(.+)-\d+$")


def select(listing, shard):
    """libtest lists the names sorted, so each shard gets a slice of every module."""
    k, n = (int(part) for part in shard.split("/"))
    if not 1 <= k <= n:
        raise ValueError(f"shard {shard}: expected k/n with 1 <= k <= n")
    return LISTED.findall(listing)[k - 1 :: n]


def problems(reports):
    """Per engine: no test in two shards, and as many tests as the binary holds."""
    engines = defaultdict(list)
    for report in sorted(Path(reports).glob("*/tests.txt")):
        engine = REPORT.match(report.parent.name)
        if engine:
            engines[engine.group(1)].append(report.read_text(encoding="utf-8", errors="replace"))
    found = [] if engines else [f"no shard report under {reports}"]
    for engine, logs in sorted(engines.items()):
        ran = Counter(name for log in logs for name in RAN.findall(log))
        # A shard's result line counts the tests it left out too: the sum is the binary's total.
        totals = {sum(map(int, result)) for log in logs for result in RESULT.findall(log)}
        twice = sorted(name for name, count in ran.items() if count > 1)
        if twice:
            found.append(f"{engine}: ran in two shards: " + ", ".join(twice))
        if len(totals) != 1:
            found.append(f"{engine}: the shards disagree on the number of tests: {sorted(totals)}")
        elif len(ran) != (total := totals.pop()):
            found.append(f"{engine}: {len(ran)} of {total} tests ran")
        else:
            print(f"{engine}: {total} tests, each ran once in {len(logs)} shards")
    return found


def main(argv):
    if len(argv) == 2 and argv[0] == "select":
        names = select(sys.stdin.read(), argv[1])
        if not names:
            print("::error::no test to select: the listing is empty", file=sys.stderr)
            return 1
        print(" ".join(names))
        return 0
    if len(argv) == 2 and argv[0] == "check":
        found = problems(argv[1])
        for problem in found:
            print(f"::error::{problem}", file=sys.stderr)
        return 1 if found else 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
