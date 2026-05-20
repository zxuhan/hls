"""CP-SAT time-budget sweep.

For one instance, run B_CPSAT at a configurable list of wall-clock budgets
and record makespan / runtime / best-bound / internal-gap at each budget.
This script DOES NOT re-run heuristic baselines — they are sub-second by
construction and a single existing measurement (from multi_instance_run.py)
covers them. The output CSV is intentionally narrow and is intended to be
plotted as a CPSAT-only Pareto curve, with the heuristic shown as a flat
horizontal reference line in the figure.

For each (instance, budget) pair the script does ``--runs-per-budget``
independent CPSAT runs (default 1) so variance can be characterised at
short budgets if desired.

Usage:
    python scripts/cpsat_budget_sweep.py --data data_two.xlsx --instance-id MOD_A
    python scripts/cpsat_budget_sweep.py --data data_three.xlsx --instance-id MOD_B
    python scripts/cpsat_budget_sweep.py --data data_four.xlsx --instance-id MOD_C
    python scripts/cpsat_budget_sweep.py --data data_five.xlsx --instance-id MOD_D

Default budgets: 0.1, 1, 10, 60, 600 seconds. Override with
``--budgets 0.1,0.5,1,5,10,60,600``.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from algorithms import Shift, ShiftDay, load_instance, schedule_cpsat

from _common import RESULTS_DIR, resolve_data


COLUMNS = [
    "timestamp",
    "instance_id",
    "budget_s",
    "run_idx",
    "horizon_days",
    "shift_hours",
    "shift_fte",
    "success",
    "error_message",
    "makespan_days",
    "runtime_ms",
    "best_bound_days",
    "internal_gap",
    "proved_optimal",
]


def make_shift_schedule(n_days: int, start_hour: int, duration_hours: int, fte: int) -> list[ShiftDay]:
    start_hh = (start_hour - 1) * 2
    end_hh = start_hh + duration_hours * 2
    shift = Shift(start_half_hour=start_hh, end_half_hour=end_hh, fte=fte)
    return [ShiftDay(shifts=(shift,)) for _ in range(n_days)]


def row_template(args, budget_s: float, run_idx: int) -> dict[str, object]:
    return {
        "timestamp": dt.datetime.now().isoformat(timespec="seconds"),
        "instance_id": args.instance_id,
        "budget_s": budget_s,
        "run_idx": run_idx,
        "horizon_days": args.horizon_days,
        "shift_hours": args.shift_hours,
        "shift_fte": args.shift_fte,
        "success": "",
        "error_message": "",
        "makespan_days": "",
        "runtime_ms": "",
        "best_bound_days": "",
        "internal_gap": "",
        "proved_optimal": "",
    }


def fill_result(row: dict[str, object], result) -> None:
    row["success"] = "Y" if result.success else "N"
    row["runtime_ms"] = result.runtime_ms
    if result.success:
        row["makespan_days"] = result.makespan
        if result.best_bound is not None:
            row["best_bound_days"] = result.best_bound
        if result.optimality_gap is not None:
            row["internal_gap"] = round(result.optimality_gap, 6)
            row["proved_optimal"] = "Y" if result.optimality_gap == 0.0 else "N"
    else:
        row["error_message"] = (result.error_message or "")[:300]
        row["proved_optimal"] = "N"


def _print_outcome(label: str, res) -> None:
    rt_s = res.runtime_ms / 1000.0
    if res.success:
        extra = ""
        if res.best_bound is not None:
            extra = f"  bound={res.best_bound}  gap={res.optimality_gap:.4f}"
        print(f"  OK  makespan={res.makespan}d  runtime={rt_s:.3f}s{extra}", flush=True)
    else:
        print(f"  INFEASIBLE  runtime={rt_s:.3f}s  err={(res.error_message or '')[:120]}",
              flush=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data", required=True)
    ap.add_argument("--instance-id", required=True)
    ap.add_argument("--horizon-days", type=int, default=30)
    ap.add_argument("--shift-hours", type=int, default=14)
    ap.add_argument("--shift-fte", type=int, default=6)
    ap.add_argument("--shift-start-hour", type=int, default=1,
                    help="1-indexed start hour. 1 = first hour of the day.")
    ap.add_argument("--budgets", type=str, default="0.1,1,10,60,600",
                    help="Comma-separated CPSAT budgets in seconds.")
    ap.add_argument("--runs-per-budget", type=int, default=1,
                    help="Independent CPSAT runs per budget. 1 is enough for "
                         "the headline Pareto; >1 quantifies solver variance.")
    ap.add_argument("--output", default=None,
                    help="Override CSV path. "
                         "Default: results/cpsat_budget_<instance>.csv")
    args = ap.parse_args()

    blocks = load_instance(resolve_data(args.data))
    schedule = make_shift_schedule(args.horizon_days, args.shift_start_hour,
                                   args.shift_hours, args.shift_fte)
    budgets = [float(x) for x in args.budgets.split(",") if x.strip()]

    print(f"Instance {args.instance_id}: {len(blocks)} blocks; "
          f"{args.horizon_days}d × {args.shift_hours}h × {args.shift_fte} FTE; "
          f"budgets = {budgets}; runs/budget = {args.runs_per_budget}",
          flush=True)

    rows: list[dict[str, object]] = []
    for budget_s in budgets:
        for r_idx in range(1, args.runs_per_budget + 1):
            label = f"B_CPSAT @ {budget_s}s  run {r_idx}/{args.runs_per_budget}"
            print(f"\n[{label}]", flush=True)
            row = row_template(args, budget_s, r_idx)
            res = schedule_cpsat(blocks, schedule, time_limit_seconds=budget_s)
            fill_result(row, res)
            _print_outcome(label, res)
            rows.append(row)

    out_path = (Path(args.output) if args.output
                else RESULTS_DIR / f"cpsat_budget_{args.instance_id}.csv")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"\nWrote {len(rows)} rows -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
