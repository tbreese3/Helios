#!/usr/bin/env python3
"""
Compare HQBenchmark.perftNodes in two JMH CSV result files.
Fail (exit-1) if PR nodes/s < 98 % of base.
"""

import csv, sys
from pathlib import Path

def nps(csv_path: Path) -> float:
    with csv_path.open(newline="") as fh:
        for row in csv.DictReader(fh):
            if row["Benchmark"].endswith("perftNodes"):
                score  = float(row["Score"])           # calls / s
            elif row["Benchmark"].endswith("perftNodes:nodes"):
                nodes  = float(row["Score"])           # nodes / call
    return score * nodes

def human(n: float) -> str:
    for unit in ("", "k", "M", "G"):
        if n < 1000:
            return f"{n:,.1f}{unit}"
        n /= 1000.0
    return f"{n:,.1f}T"

def main(base_csv: str, pr_csv: str) -> None:
    nps_base = nps(Path(base_csv))
    nps_pr   = nps(Path(pr_csv))
    ratio    = nps_pr / nps_base if nps_base else 0.0

    print(f"Base nodes/s : {human(nps_base)}")
    print(f"PR   nodes/s : {human(nps_pr)}")
    print(f"Speed ratio  : {ratio:,.2f}x ({'faster' if ratio>1 else 'slower'})")

    if ratio < 0.98:
        print("[FAIL] PR is slower than base – failing the build.")
        sys.exit(1)
    print("[PASS] PR is as fast or faster than base.")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: compare_jmh.py <base.csv> <pr.csv>")
    main(sys.argv[1], sys.argv[2])