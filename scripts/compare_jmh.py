#!/usr/bin/env python3
"""
Compare HQBenchmark.perftNodes results in two JMH output files.
Fails (exit 1) when PR nodes/s < 98 % of base.
"""

import sys
from pathlib import Path

def grab(f: Path, want_nodes: bool) -> float:
    """
    Return the numeric field just before 'ops/s' (score) or '#' (nodes).
    want_nodes = False → pick HQBenchmark.perftNodes line
    want_nodes = True  → pick HQBenchmark.perftNodes:nodes line
    """
    tag = "perftNodes:nodes" if want_nodes else "perftNodes"
    for line in f.read_text().splitlines():
        if tag in line and "thrpt" in line:
            toks = line.split()
            try:
                anchor = "#" if want_nodes else "ops/s"
                idx = toks.index(anchor)
                return float(toks[idx - 1])
            except (ValueError, IndexError):
                pass   # malformed, keep scanning
    raise ValueError(f"{tag} numbers not found in {f}")

def extract(path: Path) -> tuple[float, float]:
    return grab(path, False), grab(path, True)

def main(base, pr) -> None:
    score_b, nodes_b = extract(base)
    score_p, nodes_p = extract(pr)

    nps_base = score_b * nodes_b
    nps_pr   = score_p * nodes_p
    ratio    = nps_pr / nps_base if nps_base else 0

    print(f"Base nodes/s : {nps_base:,.0f}")
    print(f"PR   nodes/s : {nps_pr:,.0f}")
    print(f"Speed ratio  : {ratio:,.2f}× ({'faster' if ratio>1 else 'slower'})")

    # fail if PR is more than 2 % slower
    if ratio < 0.98:
        print("❌  PR is slower than base – failing the build.")
        sys.exit(1)
    print("✅  PR is as fast or faster than base.")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: compare_jmh.py <base.txt> <pr.txt>")
    main(Path(sys.argv[1]), Path(sys.argv[2]))
