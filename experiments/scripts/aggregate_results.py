"""Combine per-instance result CSVs into the H1a/H1b/H1c summary tables.

Reads every ``results/run_*.csv``, joins with ``results/instance_features.csv``
when present, computes per-instance optimality gaps against the best CPSAT
makespan, and writes:

  * ``results/aggregate_per_run.csv``      — every row from every instance + computed gap
  * ``results/aggregate_per_instance.csv`` — one row per (instance, algorithm-config)
  * ``results/h1_summary.csv``             — H1a / H1b / H1c verdicts across instances

A console report mirrors the CSV summary so the script is useful by itself.

Usage:
    python scripts/aggregate_results.py
    python scripts/aggregate_results.py --gap-threshold 0.15
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path
from statistics import mean, median
from typing import Iterable

ROOT = Path(__file__).resolve().parent.parent
RESULTS_DIR = ROOT / "results"


def _to_float(s: str | None) -> float | None:
    if s is None or s == "":
        return None
    try:
        return float(s)
    except ValueError:
        return None


def _to_int(s: str | None) -> int | None:
    f = _to_float(s)
    return int(f) if f is not None else None


def load_per_run() -> list[dict[str, str]]:
    out: list[dict[str, str]] = []
    for p in sorted(RESULTS_DIR.glob("run_*.csv")):
        with p.open(newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                out.append(row)
    return out


def cpsat_optimum_per_instance(rows: Iterable[dict[str, str]]) -> dict[str, int]:
    best: dict[str, int] = {}
    for r in rows:
        if r["algorithm"] != "B_CPSAT" or r["success"] != "Y":
            continue
        m = _to_int(r.get("makespan_days"))
        if m is None:
            continue
        cur = best.get(r["instance_id"])
        if cur is None or m < cur:
            best[r["instance_id"]] = m
    return best


def annotate_gap(rows: list[dict[str, str]], optimum: dict[str, int]) -> None:
    for r in rows:
        opt = optimum.get(r["instance_id"])
        if opt is None or r["success"] != "Y":
            r["gap_vs_cpsat"] = ""
            continue
        m = _to_int(r.get("makespan_days"))
        if m is None or opt <= 0:
            r["gap_vs_cpsat"] = ""
            continue
        r["gap_vs_cpsat"] = f"{(m - opt) / opt:.6f}"


def algo_label(r: dict[str, str]) -> str:
    """One label per (algorithm, weight, gamma) configuration."""
    a = r["algorithm"]
    if a == "C_ENHANCED":
        w = r.get("weight", "")
        g = r.get("gamma", "")
        return f"C_ENHANCED w={w} γ={g}"
    return a


def aggregate_per_instance(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    """Per (instance, config), average over runs (mostly relevant for CPSAT)."""
    bucket: dict[tuple[str, str], list[dict[str, str]]] = {}
    for r in rows:
        bucket.setdefault((r["instance_id"], algo_label(r)), []).append(r)
    out: list[dict[str, str]] = []
    for (instance, label), group in sorted(bucket.items()):
        success_runs = [r for r in group if r["success"] == "Y"]
        succ_rate = len(success_runs) / len(group) if group else 0
        if success_runs:
            mksp_vals = [_to_int(r["makespan_days"]) for r in success_runs]
            mksp_vals = [v for v in mksp_vals if v is not None]
            best_mksp = min(mksp_vals) if mksp_vals else ""
            mean_mksp = round(mean(mksp_vals), 3) if mksp_vals else ""
        else:
            best_mksp = mean_mksp = ""
        rt_vals = [_to_int(r["runtime_ms"]) for r in group]
        rt_vals = [v for v in rt_vals if v is not None]
        gap_vals = [_to_float(r.get("gap_vs_cpsat")) for r in success_runs]
        gap_vals = [v for v in gap_vals if v is not None]
        out.append({
            "instance_id": instance,
            "config": label,
            "n_runs": str(len(group)),
            "feasibility_rate": f"{succ_rate:.3f}",
            "best_makespan_days": str(best_mksp),
            "mean_makespan_days": str(mean_mksp),
            "mean_runtime_ms": str(round(mean(rt_vals), 1)) if rt_vals else "",
            "median_runtime_ms": str(round(median(rt_vals), 1)) if rt_vals else "",
            "mean_gap_vs_cpsat": f"{mean(gap_vals):.4f}" if gap_vals else "",
        })
    return out


def h1_summary(per_instance: list[dict[str, str]], gap_threshold: float) -> list[dict[str, str]]:
    """Hypothesis verdicts across the instance set."""
    by_config: dict[str, list[dict[str, str]]] = {}
    for r in per_instance:
        by_config.setdefault(r["config"], []).append(r)

    out: list[dict[str, str]] = []
    for config, rows in sorted(by_config.items()):
        n_inst = len(rows)
        succ_inst = sum(1 for r in rows if float(r["feasibility_rate"]) == 1.0)
        gaps = [float(r["mean_gap_vs_cpsat"]) for r in rows if r["mean_gap_vs_cpsat"]]
        within_threshold = sum(1 for g in gaps if g <= gap_threshold)
        out.append({
            "config": config,
            "n_instances": str(n_inst),
            "instances_feasible": str(succ_inst),
            "feasibility_rate": f"{succ_inst / n_inst:.3f}" if n_inst else "",
            "instances_with_gap": str(len(gaps)),
            "mean_gap": f"{mean(gaps):.4f}" if gaps else "",
            "median_gap": f"{median(gaps):.4f}" if gaps else "",
            f"within_{int(gap_threshold * 100)}pct_count": str(within_threshold),
            f"within_{int(gap_threshold * 100)}pct_rate": (
                f"{within_threshold / len(gaps):.3f}" if gaps else ""),
        })
    return out


def write_csv(path: Path, rows: list[dict[str, str]], columns: list[str]) -> None:
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    # encoding="utf-8" so γ in C_ENHANCED config labels round-trips on Windows,
    # whose default cp1252 codec can't encode it.
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=columns, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def print_h1_console(rows: list[dict[str, str]], gap_threshold: float) -> None:
    if not rows:
        print("(no aggregated rows)")
        return
    pct_col = f"within_{int(gap_threshold * 100)}pct_rate"
    print(f"\n=== Per-config summary across instances (gap threshold = {gap_threshold:.2f}) ===")
    print(f"{'config':<32} {'feas%':>6} {'mean_gap':>10} {'median_gap':>11} {'within%':>9}")
    print("-" * 75)
    for r in rows:
        feas_pct = float(r["feasibility_rate"]) * 100 if r["feasibility_rate"] else 0
        gap_str = r["mean_gap"] or "-"
        med_str = r["median_gap"] or "-"
        within_str = r[pct_col] if r[pct_col] else "-"
        print(f"{r['config']:<32} {feas_pct:>5.1f}% {gap_str:>10} {med_str:>11} {within_str:>9}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--gap-threshold", type=float, default=0.15,
                    help="H1b threshold; default 0.15 (15%%).")
    args = ap.parse_args()

    rows = load_per_run()
    if not rows:
        print(f"No run_*.csv found in {RESULTS_DIR}")
        return 1
    optimum = cpsat_optimum_per_instance(rows)
    annotate_gap(rows, optimum)

    per_run_cols = list(rows[0].keys())
    if "gap_vs_cpsat" not in per_run_cols:
        per_run_cols.append("gap_vs_cpsat")

    write_csv(RESULTS_DIR / "aggregate_per_run.csv", rows, per_run_cols)

    per_instance = aggregate_per_instance(rows)
    write_csv(RESULTS_DIR / "aggregate_per_instance.csv", per_instance,
              ["instance_id", "config", "n_runs", "feasibility_rate",
               "best_makespan_days", "mean_makespan_days",
               "mean_runtime_ms", "median_runtime_ms", "mean_gap_vs_cpsat"])

    summary = h1_summary(per_instance, args.gap_threshold)
    pct = int(args.gap_threshold * 100)
    write_csv(RESULTS_DIR / "h1_summary.csv", summary,
              ["config", "n_instances", "instances_feasible", "feasibility_rate",
               "instances_with_gap", "mean_gap", "median_gap",
               f"within_{pct}pct_count", f"within_{pct}pct_rate"])

    print(f"Loaded {len(rows)} runs across {len(set(r['instance_id'] for r in rows))} instances")
    print(f"CPSAT proven optima: {optimum}")
    print_h1_console(summary, args.gap_threshold)

    return 0


if __name__ == "__main__":
    sys.exit(main())
