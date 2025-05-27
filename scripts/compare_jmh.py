#!/usr/bin/env python3
import sys, re, pathlib
def read_score(path):
    txt = pathlib.Path(path).read_text()
    m = re.search(r'HQBenchmark.totalNodes.*?(\d+(?:\.\d+)?)\s+ops/s', txt)
    return float(m.group(1)) if m else None
base, pr = map(read_score, sys.argv[1:3])
diff = 100.0 * (pr - base) / base
sign = "↑" if diff > 0 else "↓"
print(f"base : {base:.0f} ops/s")
print(f"pr   : {pr :.0f} ops/s  ({sign}{abs(diff):.1f} %)")
sys.exit(0)