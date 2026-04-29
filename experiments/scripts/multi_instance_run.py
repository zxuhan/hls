"""Run the full RQ1 measurement on one instance.

For each instance the script produces 15 rows:

  * A_MTS                                     — 1 run
  * A_SPT                                     — 1 run
  * C_ENHANCED γ=0.0  w∈{0, 0.25, 0.5, 0.75, 1.0}  — 5 runs (CST off)
  * C_ENHANCED γ=0.5  w∈{0, 0.25, 0.5, 0.75, 1.0}  — 5 runs (CST on)
  * B_CPSAT @ 600 s                            — 3 runs (variance check)

Per-row metrics: success / error_message / makespan / runtime_ms /
best_bound / optimality_gap. The optimality gap relative to CPSAT's
proved optimum is computed downstream by aggregate_results.py once all
rows are in.

Usage:
    python scripts/multi_instance_run.py --data data_one.xlsx --instance-id MOD_M1
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import sys
from dataclasses import asdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from algorithms import (
    Shift, ShiftDay, load_instance,
    schedule_mts, schedule_spt, schedule_enhanced, schedule_cpsat,
)

from _common import RESULTS_DIR, resolve_data


COLUMNS = [
    "timestamp",
    "instance_id",
    "algorithm",
    "weight",
    "gamma",
    "run_idx",
    "horizon_days",
    "shift_hours",
    "shift_fte",
    "cpsat_time_limit_s",
    "success",
    "error_message",
    "makespan_days",
    "runtime_ms",
    "best_bound_days",
    "optimality_gap",
]


def make_shift_schedule(n_days: int, start_hour: int, duration_hours: int, fte: int) -> list[ShiftDay]:
    start_hh = (start_hour - 1) * 2
    end_hh = start_hh + duration_hours * 2
    shift = Shift(start_half_hour=start_hh, end_half_hour=end_hh, fte=fte)
    return [ShiftDay(shifts=(shift,)) for _ in range(n_days)]


def row_template(args, algorithm: str, weight: float | None, gamma: float | None,
                 run_idx: int, cpsat_limit_s: int | None) -> dict[str, object]:
    return {
        "timestamp": dt.datetime.now().isoformat(timespec="seconds"),
        "instance_id": args.instance_id,
        "algorithm": algorithm,
        "weight": "" if weight is None else weight,
        "gamma": "" if gamma is None else gamma,
        "run_idx": run_idx,
        "horizon_days": args.horizon_days,
        "shift_hours": args.shift_hours,
        "shift_fte": args.shift_fte,
        "cpsat_time_limit_s": "" if cpsat_limit_s is None else cpsat_limit_s,
        "success": "",
        "error_message": "",
        "makespan_days": "",
        "runtime_ms": "",
        "best_bound_days": "",
        "optimality_gap": "",
    }


def fill_result(row: dict[str, object], result) -> None:
    row["success"] = "Y" if result.success else "N"
    row["runtime_ms"] = result.runtime_ms
    if result.success:
        row["makespan_days"] = result.makespan
        if result.best_bound is not None:
            row["best_bound_days"] = result.best_bound
        if result.optimality_gap is not None:
            row["optimality_gap"] = round(result.optimality_gap, 6)
    else:
        row["error_message"] = (result.error_message or "")[:300]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data", required=True)
    ap.add_argument("--instance-id", required=True)
    ap.add_argument("--horizon-days", type=int, default=30)
    ap.add_argument("--shift-hours", type=int, default=14)
    ap.add_argument("--shift-fte", type=int, default=6)
    ap.add_argument("--shift-start-hour", type=int, default=1,
                    help="1-indexed start hour. 1 = first hour of the day.")
    ap.add_argument("--cpsat-time-limit-s", type=int, default=600)
    ap.add_argument("--cpsat-runs", type=int, default=3)
    ap.add_argument("--weights", type=str, default="0,0.25,0.5,0.75,1.0",
                    help="Comma-separated C_ENHANCED weights.")
    ap.add_argument("--gammas", type=str, default="0,0.5",
                    help="Comma-separated CST gamma values. 0 disables CST.")
    ap.add_argument("--output", default=None,
                    help="Override CSV path. Default: results/run_<instance>_<ts>.csv")
    args = ap.parse_args()

    blocks = load_instance(resolve_data(args.data))
    schedule = make_shift_schedule(args.horizon_days, args.shift_start_hour,
                                   args.shift_hours, args.shift_fte)
    weights = [float(x) for x in args.weights.split(",") if x.strip()]
    gammas = [float(x) for x in args.gammas.split(",") if x.strip()]

    print(f"Instance {args.instance_id}: {len(blocks)} blocks; "
          f"{args.horizon_days}d × {args.shift_hours}h × {args.shift_fte} FTE",
          flush=True)
    rows: list[dict[str, object]] = []

    # ── A_MTS ──
    print("\n[A_MTS]", flush=True)
    row = row_template(args, "A_MTS", None, None, 1, None)
    res = schedule_mts(blocks, schedule)
    fill_result(row, res)
    _print_outcome("A_MTS", res)
    rows.append(row)

    # ── A_SPT ──
    print("\n[A_SPT]", flush=True)
    row = row_template(args, "A_SPT", None, None, 1, None)
    res = schedule_spt(blocks, schedule)
    fill_result(row, res)
    _print_outcome("A_SPT", res)
    rows.append(row)

    # ── C_ENHANCED grid ──
    for gamma in gammas:
        for w in weights:
            label = f"C_ENHANCED w={w} γ={gamma}"
            print(f"\n[{label}]", flush=True)
            row = row_template(args, "C_ENHANCED", w, gamma, 1, None)
            res = schedule_enhanced(blocks, schedule, weight=w, gamma=gamma)
            fill_result(row, res)
            _print_outcome(label, res)
            rows.append(row)

    # ── B_CPSAT × cpsat_runs ──
    for r_idx in range(1, args.cpsat_runs + 1):
        label = f"B_CPSAT run {r_idx}/{args.cpsat_runs}"
        print(f"\n[{label}]", flush=True)
        row = row_template(args, "B_CPSAT", None, None, r_idx, args.cpsat_time_limit_s)
        res = schedule_cpsat(blocks, schedule, time_limit_seconds=args.cpsat_time_limit_s)
        fill_result(row, res)
        _print_outcome(label, res)
        rows.append(row)

    # ── Write ──
    out_path = (Path(args.output) if args.output
                else RESULTS_DIR / f"run_{args.instance_id}.csv")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"\nWrote {len(rows)} rows -> {out_path}")
    return 0


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


if __name__ == "__main__":
    sys.exit(main())
