"""Candidate C — domain-enhanced greedy.

Composite priority score::

    score(i) = (1 − w) · CPR_norm(i) + w · OPA(i) + γ · CST(i)

with ``w ∈ {0, 0.25, 0.5, 0.75, 1.0}`` (default 0.5) and
``γ ∈ [0, ...]`` controlling the constraint-tightness boost
(default 0.5; set to 0 to disable CST entirely).

Components:
  * **CPR (Critical Path Remaining)** — longest remaining duration to a
    terminal node (normalised to [0, 1]).
  * **OPA (Operational Position Affinity)** — continuous look-back: for
    each axis the block declares, compare against the value of the most
    recently started already-placed block that declares that axis
    (matches/unset → 1, mismatches → 0; mean across declared axes).
    Orientation-neutral blocks score 1.
  * **CST (Constraint Tightness)** — static per-block measure
    ``(axes/maxAxes + fte/maxFte + dur/maxDur) / 3``. Pulls axis-rich,
    FTE-heavy, long-duration sinks forward in the schedulable pool so
    their compatible windows haven't fragmented by the time they're ready.

Scores are used for ordering only, so the absence of normalisation on
the CST term is immaterial.
"""
from __future__ import annotations

import time
from typing import Sequence

from .models import Block, ScheduleResult, ShiftDay
from .placement import PlacementEngine
from .precedence import compute_critical_path_remaining, has_cycle


def _compute_cst(blocks: Sequence[Block]) -> dict[str, float]:
    """Per-block constraint tightness in [0, 1]; mean of three normalised features."""
    max_axes = max((len(b.position_axes) for b in blocks), default=0)
    max_fte = max((b.fte_requirement for b in blocks), default=0)
    max_dur = max((b.duration_half_hours for b in blocks), default=0)
    out: dict[str, float] = {}
    for b in blocks:
        a = (len(b.position_axes) / max_axes) if max_axes else 0.0
        f = (b.fte_requirement / max_fte) if max_fte else 0.0
        d = (b.duration_half_hours / max_dur) if max_dur else 0.0
        out[b.id] = (a + f + d) / 3.0
    return out


def _look_back_opa(block: Block, current_axis_value: dict[str, str]) -> float:
    """Continuous OPA in [0, 1]: fraction of declared axes that match the
    machine's most-recently-set value (or are unset). Neutral blocks → 1.0."""
    axes = block.position_axes
    if not axes:
        return 1.0
    matches = 0
    total = 0
    for axis, value in axes.items():
        total += 1
        current = current_axis_value.get(axis)
        if current is None or current == value:
            matches += 1
    return matches / total if total else 1.0


def schedule_enhanced(
    blocks: Sequence[Block],
    shift_schedule: Sequence[ShiftDay],
    weight: float = 0.5,
    gamma: float = 0.5,
) -> ScheduleResult:
    """Run Candidate C.

    Parameters
    ----------
    weight : float
        Convex-combination weight ``w`` for the OPA term.
        Setting ``w = 0`` makes the score CPR-only (plus CST);
        ``w = 1`` makes it OPA-only (plus CST).
    gamma : float
        Multiplier on the CST term. ``gamma = 0`` disables CST
        (used to test H1c). Default 0.5 matches the Java backend.
    """
    start_ms = time.perf_counter()

    if has_cycle(blocks):
        elapsed = int((time.perf_counter() - start_ms) * 1000)
        return ScheduleResult(False, "Precedence cycle detected", 0, [], elapsed)

    cpr = compute_critical_path_remaining(blocks)
    max_cpr = max(cpr.values()) if cpr else 1
    if max_cpr <= 0:
        max_cpr = 1
    cpr_norm = {bid: v / max_cpr for bid, v in cpr.items()}
    cst = _compute_cst(blocks) if gamma != 0.0 else {b.id: 0.0 for b in blocks}

    block_map = {b.id: b for b in blocks}
    block_ids = set(block_map)
    engine = PlacementEngine(shift_schedule)
    remaining = set(block_ids)

    current_axis_value: dict[str, str] = {}
    current_axis_latest_start: dict[str, int] = {}

    while remaining:
        schedulable: list[Block] = []
        for bid in remaining:
            block = block_map[bid]
            if all(
                pred not in block_ids or pred in engine.scheduled
                for pred in block.predecessor_block_ids
            ):
                schedulable.append(block)

        if not schedulable:
            elapsed = int((time.perf_counter() - start_ms) * 1000)
            return ScheduleResult(
                False,
                "No schedulable blocks found — possible missing predecessor outside block set",
                0, [], elapsed,
            )

        best_block: Block | None = None
        best_score = float("-inf")
        best_start = -1

        for block in schedulable:
            min_start = engine.get_earliest_predecessor_end(block)
            start_time = engine.find_earliest_valid_start(block, min_start)
            if start_time < 0:
                continue
            cpr_score = cpr_norm.get(block.id, 0.0)
            opa_score = _look_back_opa(block, current_axis_value)
            cst_score = cst.get(block.id, 0.0)
            score = (1.0 - weight) * cpr_score + weight * opa_score + gamma * cst_score
            if (score > best_score
                    or (score == best_score and (best_block is None
                                                 or block.id < best_block.id))):
                best_score = score
                best_block = block
                best_start = start_time

        if best_block is None:
            elapsed = int((time.perf_counter() - start_ms) * 1000)
            return ScheduleResult(
                False,
                "No feasible schedule found: remaining blocks cannot fit in any available window",
                0, [], elapsed,
            )

        engine.place_block(best_block, best_start)
        for axis, value in best_block.position_axes.items():
            latest = current_axis_latest_start.get(axis)
            if latest is None or latest < best_start:
                current_axis_latest_start[axis] = best_start
                current_axis_value[axis] = value
        remaining.remove(best_block.id)

    result = list(engine.scheduled.values())
    makespan = max(sb.day_index for sb in result) if result else 0
    elapsed = int((time.perf_counter() - start_ms) * 1000)
    return ScheduleResult(True, None, makespan, result, elapsed)
