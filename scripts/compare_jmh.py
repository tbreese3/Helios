#!/usr/bin/env python3
"""
Compare JMH outputs for HQBenchmark.perftNodes and fail if the PR is slower.
Usage:  python compare_jmh.py base.txt pr.txt
"""

import re
import sys
from pathlib import Path

BENCH_RE      = re.compile(r'HQBenchmark\.perftNodes\s+\w+\s+\d+\s+([0-9.]+)')
BENCH_NODES_RE = re.compile(r'HQBenchmark\.perftNodes:nodes\s+\w+\s+\d+\s+([0-9.eE+-]+)')

def extract_nums(path: Path) -> tuple[float, float]:
    score = nodes = None
    for line in path.read_text().splitlines():
        m = BENCH_RE.search(line)
        if m:
            score = float(m.group(1))
            continue
        n = BENCH_NODES_RE.search(line)
        if n:
            nodes = float(n.group(1))
    if score is None or nodes is None:
        raise ValueError(f"Could not find benchmark numbers in {path}")
    return score, nodes

def main(base_path: str, pr_path: str) -> None:
    score_b, nodes_b = extract_nums(Path(base_path))
    score_p, nodes_p = extract_nums(Path(pr_path))

    nps_base = score_b * nodes_b
    nps_pr   = score_p * nodes_p

    ratio = nps_pr / nps_base if nps_base > 0 else float('inf')
    print(f"Base nodes/s : {nps_base:,.0f}")
    print(f"PR   nodes/s : {nps_pr:,.0f}")
    print(f"Speed ratio  : {ratio:,.2f}× ({'faster' if ratio>1 else 'slower'})")

    # Fail the job if the PR is slower (≤ 98 % of base)
    if ratio < 0.98:
        print("❌  PR is slower than base – failing the build.")
        sys.exit(1)
    else:
        print("✅  PR is as fast or faster than base.")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: compare_jmh.py <base.txt> <pr.txt>")
    main(sys.argv[1], sys.argv[2])
