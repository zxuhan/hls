"""Sanity-check the Python port against the Java baseline on MOD_M1.

Expected (corrected TDM convention, post-2026-04-30 fix; numbers from
`experiments/results/run_MOD_M1.csv`):
  A_MTS                                    feasible, makespan = 20
  A_SPT                                    feasible, makespan = 24
  C_ENHANCED w=0    γ=0    no-CST          feasible, makespan = 20
  C_ENHANCED w=0.25 γ=0    no-CST          feasible, makespan = 21
  C_ENHANCED w=0.5  γ=0    no-CST          feasible, makespan = 23
  C_ENHANCED w=0.75 γ=0    no-CST          feasible, makespan = 23
  C_ENHANCED w=1.0  γ=0    no-CST          feasible, makespan = 23
  C_ENHANCED w=0    γ=0.5  with-CST        feasible, makespan = 19  (best heur)
  C_ENHANCED w=0.25 γ=0.5  with-CST        feasible, makespan = 21
  C_ENHANCED w=0.5  γ=0.5  with-CST        feasible, makespan = 22
  C_ENHANCED w=0.75 γ=0.5  with-CST        feasible, makespan = 23
  C_ENHANCED w=1.0  γ=0.5  with-CST        feasible, makespan = 22
  B_CPSAT @ 600 s                          OPTIMAL = 18 days in ~1 s

Pre-fix (inverted-DAG) expectations are preserved in git history. See
`OBSERVATIONS.md` §1b for the convention change.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from algorithms import (
    Shift, ShiftDay, load_instance,
    schedule_mts, schedule_spt, schedule_enhanced, schedule_cpsat,
)


def make_shift_schedule(n_days: int = 30, start_hour: int = 1,
                        duration_hours: int = 14, fte: int = 6) -> list[ShiftDay]:
    """One shift per day, 1-indexed startHour. Internal: half-hours."""
    start_hh = (start_hour - 1) * 2
    end_hh = start_hh + duration_hours * 2
    shift = Shift(start_half_hour=start_hh, end_half_hour=end_hh, fte=fte)
    return [ShiftDay(shifts=(shift,)) for _ in range(n_days)]


def main() -> int:
    data = ROOT / "data" / "data_one.xlsx"
    blocks = load_instance(data)
    print(f"Loaded {len(blocks)} blocks from {data.name}", flush=True)

    schedule = make_shift_schedule(n_days=30, duration_hours=14, fte=6)

    print(f"\n{'algorithm':<32} {'success':<8} {'mksp':<6} {'rt(s)':<8} {'note'}")
    print("-" * 90)

    def report(label: str, res, note: str = "") -> None:
        ok = "Y" if res.success else "N"
        mksp = res.makespan if res.success else "-"
        rt = res.runtime_ms / 1000.0
        msg = note if not res.success else note
        if not res.success:
            err = res.error_message or ""
            msg = f"err={err[:80]}"
        print(f"{label:<32} {ok:<8} {str(mksp):<6} {rt:<8.3f} {msg}", flush=True)

    report("A_MTS", schedule_mts(blocks, schedule), "expect: Y@20")
    report("A_SPT", schedule_spt(blocks, schedule), "expect: Y@24")

    no_cst_expect = {0.0: "Y@20", 0.25: "Y@21", 0.5: "Y@23",
                     0.75: "Y@23", 1.0: "Y@23"}
    for w in (0.0, 0.25, 0.5, 0.75, 1.0):
        r = schedule_enhanced(blocks, schedule, weight=w, gamma=0.0)
        report(f"C_ENHANCED w={w} no-CST", r, f"expect: {no_cst_expect[w]}")

    cst_expect = {0.0: "Y@19", 0.25: "Y@21", 0.5: "Y@22",
                  0.75: "Y@23", 1.0: "Y@22"}
    for w in (0.0, 0.25, 0.5, 0.75, 1.0):
        r = schedule_enhanced(blocks, schedule, weight=w, gamma=0.5)
        report(f"C_ENHANCED w={w} CST(γ=0.5)", r, f"expect: {cst_expect[w]}")

    print("\n[B_CPSAT @ 600 s — expect OPTIMAL=18 in <1s]", flush=True)
    r = schedule_cpsat(blocks, schedule, time_limit_seconds=600)
    if r.success:
        print(f"  makespan={r.makespan}  bound={r.best_bound}  gap={r.optimality_gap:.4f}  "
              f"runtime={r.runtime_ms / 1000.0:.3f}s", flush=True)
    else:
        print(f"  FAIL: {r.error_message}", flush=True)

    return 0


if __name__ == "__main__":
    sys.exit(main())
