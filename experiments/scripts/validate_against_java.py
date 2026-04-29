"""Sanity-check the Python port against the known Java baseline on the M1 instance.

Expected (per backend/scripts/results/PILLAR_A_REPORT.md, no CST on main):
  A_MTS                                    infeasible (BLK_001 cannot fit)
  A_SPT                                    infeasible (BLK_017 cannot fit)
  C_ENHANCED w=0    γ=0    no-CST          feasible, makespan = 19
  C_ENHANCED w=0.25 γ=0    no-CST          feasible, makespan = 20
  C_ENHANCED w=0.5  γ=0    no-CST          infeasible
  C_ENHANCED w=0.75 γ=0    no-CST          infeasible
  C_ENHANCED w=1.0  γ=0    no-CST          infeasible
  C_ENHANCED w=0.5  γ=0.5  with-CST        feasible (per CST motivation)
  B_CPSAT @ 600 s                          OPTIMAL = 18 days in <1 s
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

    report("A_MTS", schedule_mts(blocks, schedule), "expect: INFEASIBLE")
    report("A_SPT", schedule_spt(blocks, schedule), "expect: INFEASIBLE")

    for w in (0.0, 0.25, 0.5, 0.75, 1.0):
        r = schedule_enhanced(blocks, schedule, weight=w, gamma=0.0)
        expect = "Y@19" if w == 0.0 else "Y@20" if w == 0.25 else "INFEASIBLE"
        report(f"C_ENHANCED w={w} no-CST", r, f"expect: {expect}")

    for w in (0.0, 0.25, 0.5, 0.75, 1.0):
        r = schedule_enhanced(blocks, schedule, weight=w, gamma=0.5)
        report(f"C_ENHANCED w={w} CST(γ=0.5)", r)

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
