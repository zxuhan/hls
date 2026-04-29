"""Compute structural features of one instance and append to ``results/instance_features.csv``.

Features cover block, FTE, axis, DAG, zone, and tool dimensions. They serve
two purposes:
  1. Populating the per-instance description table in §IV of the thesis.
  2. (Optionally) feeding the RQ2 failure-prediction classifier.

Usage:
    python scripts/instance_features.py --data data_one.xlsx --instance-id MOD_M1
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from algorithms import load_instance
from algorithms.precedence import build_successor_map, topological_sort

from _common import RESULTS_DIR, resolve_data


FEATURES = [
    "instance_id",
    "block_count",
    "total_work_h",
    "max_block_dur_h",
    "mean_block_dur_h",
    "max_axes_per_block",
    "mean_axes_per_block",
    "count_axis_rich_blocks",      # >= 3 declared axes
    "fte_peak",                    # max single-block FTE requirement
    "fte_total_h",                 # sum over blocks of fte * dur_h
    "count_fte_heavy_blocks",      # >= 4 FTE
    "dag_node_count",
    "dag_edge_count",
    "dag_depth",                   # longest path in nodes
    "dag_width",                   # max antichain size (approximated by max layer)
    "precedence_density",          # edges / (n*(n-1)/2)
    "zone_count",
    "tool_count_unique",
    "tool_count_exclusive",
]


def compute_features(instance_id: str, blocks) -> dict[str, str | int | float]:
    n = len(blocks)
    durations_h = [b.duration_half_hours / 2 for b in blocks]
    axes_counts = [len(b.position_axes) for b in blocks]

    succ = build_successor_map(blocks)
    edge_count = sum(len(s) for s in succ.values())

    # DAG depth + width via layered topological ordering (Kahn-style)
    in_deg: dict[str, int] = {b.id: 0 for b in blocks}
    block_ids = set(in_deg)
    for b in blocks:
        for p in b.predecessor_block_ids:
            if p in block_ids:
                in_deg[b.id] += 1
    layers: list[list[str]] = []
    current = [bid for bid, d in in_deg.items() if d == 0]
    seen_in_layers: set[str] = set()
    in_deg_work = dict(in_deg)
    while current:
        layers.append(current)
        seen_in_layers.update(current)
        nxt: list[str] = []
        for bid in current:
            for s in succ.get(bid, ()):
                in_deg_work[s] -= 1
                if in_deg_work[s] == 0:
                    nxt.append(s)
        current = nxt
    dag_depth = len(layers)
    dag_width = max((len(layer) for layer in layers), default=0)

    zones = set()
    tool_names: set[str] = set()
    exclusive_tool_names: set[str] = set()
    for b in blocks:
        zones.update(b.occupied_zones)
        if b.required_tool is not None:
            tool_names.add(b.required_tool.tool_name)
            if b.required_tool.exclusive:
                exclusive_tool_names.add(b.required_tool.tool_name)

    max_pairs = n * (n - 1) // 2

    return {
        "instance_id": instance_id,
        "block_count": n,
        "total_work_h": round(sum(durations_h), 1),
        "max_block_dur_h": max(durations_h, default=0),
        "mean_block_dur_h": round(sum(durations_h) / n, 2) if n else 0,
        "max_axes_per_block": max(axes_counts, default=0),
        "mean_axes_per_block": round(sum(axes_counts) / n, 2) if n else 0,
        "count_axis_rich_blocks": sum(1 for c in axes_counts if c >= 3),
        "fte_peak": max((b.fte_requirement for b in blocks), default=0),
        "fte_total_h": round(sum(b.fte_requirement * b.duration_half_hours / 2 for b in blocks), 1),
        "count_fte_heavy_blocks": sum(1 for b in blocks if b.fte_requirement >= 4),
        "dag_node_count": n,
        "dag_edge_count": edge_count,
        "dag_depth": dag_depth,
        "dag_width": dag_width,
        "precedence_density": round(edge_count / max_pairs, 4) if max_pairs else 0,
        "zone_count": len(zones),
        "tool_count_unique": len(tool_names),
        "tool_count_exclusive": len(exclusive_tool_names),
    }


def upsert_row(path: Path, row: dict[str, object]) -> None:
    """Append-or-replace by ``instance_id``. Single CSV across all instances."""
    rows: list[dict[str, str]] = []
    if path.exists():
        with path.open(newline="") as f:
            reader = csv.DictReader(f)
            rows = [r for r in reader if r.get("instance_id") != row["instance_id"]]
    rows.append({k: str(v) for k, v in row.items()})
    rows.sort(key=lambda r: r.get("instance_id", ""))
    with path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FEATURES)
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data", required=True,
                    help="Excel file (bare name → looked up in data/).")
    ap.add_argument("--instance-id", required=True,
                    help="Stable id, e.g. MOD_M1, MOD_A, MOD_B.")
    ap.add_argument("--output", default=None,
                    help="Override CSV path. Default: results/instance_features.csv")
    args = ap.parse_args()

    blocks = load_instance(resolve_data(args.data))
    feats = compute_features(args.instance_id, blocks)
    out_path = Path(args.output) if args.output else (RESULTS_DIR / "instance_features.csv")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    upsert_row(out_path, feats)
    print(f"Wrote features for {args.instance_id} to {out_path}")
    print(f"  block_count={feats['block_count']}  total_work_h={feats['total_work_h']}  "
          f"dag_depth={feats['dag_depth']}  axis-rich={feats['count_axis_rich_blocks']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
