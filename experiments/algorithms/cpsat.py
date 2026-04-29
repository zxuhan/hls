"""Candidate B — CP-SAT exact solver via ``ortools.sat.python.cp_model``.

Mirrors :file:`backend/src/main/java/com/hls/algorithm/CpSatScheduler.java`.

Time-varying FTE capacity is handled with the classic *deficit-interval*
trick: the cumulative is sized to the peak supply, and each interval where
supply is below peak gets a fictitious always-on demand that consumes the
slack. The model therefore sees a constant-capacity cumulative even though
the underlying schedule has shifts.
"""
from __future__ import annotations

import time
from typing import Sequence

from ortools.sat.python import cp_model

from .models import Block, ScheduleResult, ShiftDay
from .precedence import has_cycle
from .timeline import build_fte_capacity, day_index, fits_in_single_day, SLOTS_PER_DAY


def schedule_cpsat(
    blocks: Sequence[Block],
    shift_schedule: Sequence[ShiftDay],
    time_limit_seconds: int = 60,
    warm_start_hints: dict[str, int] | None = None,
) -> ScheduleResult:
    start_ms = time.perf_counter()

    if has_cycle(blocks):
        elapsed = int((time.perf_counter() - start_ms) * 1000)
        return ScheduleResult(False, "Precedence cycle detected", 0, [], elapsed)

    fte_capacity = build_fte_capacity(shift_schedule)
    horizon_length = len(shift_schedule) * SLOTS_PER_DAY
    block_map = {b.id: b for b in blocks}

    model = cp_model.CpModel()
    start_vars: dict[str, cp_model.IntVar] = {}
    interval_vars: dict[str, cp_model.IntervalVar] = {}

    for block in blocks:
        valid_starts: list[int] = []
        for t in range(0, horizon_length - block.duration_half_hours + 1):
            if fits_in_single_day(t, block.duration_half_hours) and _has_fte(
                t, block.duration_half_hours, block.fte_requirement, fte_capacity
            ):
                valid_starts.append(t)
        if not valid_starts:
            elapsed = int((time.perf_counter() - start_ms) * 1000)
            return ScheduleResult(
                False,
                f"No feasible schedule found: block {block.id} "
                f"(duration {block.duration_half_hours} half-hours) "
                f"cannot fit in any single-day shift window",
                0, [], elapsed,
            )
        domain = cp_model.Domain.FromValues(valid_starts)
        start_var = model.NewIntVarFromDomain(domain, f"start_{block.id}")
        interval = model.NewFixedSizeIntervalVar(
            start_var, block.duration_half_hours, f"interval_{block.id}"
        )
        start_vars[block.id] = start_var
        interval_vars[block.id] = interval

    # ── Constraint 2: FTE capacity (cumulative + deficit intervals) ──
    max_capacity = max(fte_capacity) if fte_capacity else 0
    if max_capacity > 0:
        all_intervals: list[cp_model.IntervalVar] = []
        all_demands: list[int] = []
        for block in blocks:
            all_intervals.append(interval_vars[block.id])
            all_demands.append(block.fte_requirement)

        # Deficit intervals: walk runs of equal capacity; whenever cap < max,
        # add a fictitious demand of (max-cap) over that range.
        if fte_capacity:
            range_start = 0
            prev_cap = fte_capacity[0]
            for t in range(1, horizon_length + 1):
                cap = fte_capacity[t] if t < horizon_length else -1
                if cap != prev_cap:
                    if prev_cap < max_capacity and t > range_start:
                        deficit = max_capacity - prev_cap
                        deficit_start = model.NewConstant(range_start)
                        deficit_interval = model.NewFixedSizeIntervalVar(
                            deficit_start, t - range_start,
                            f"fte_deficit_{range_start}_{t}",
                        )
                        all_intervals.append(deficit_interval)
                        all_demands.append(deficit)
                    range_start = t
                    prev_cap = cap

        model.AddCumulative(all_intervals, all_demands, max_capacity)

    # ── Constraint 3: Spatial exclusivity ──
    zone_intervals: dict[str, list[cp_model.IntervalVar]] = {}
    for block in blocks:
        for zone in block.occupied_zones:
            zone_intervals.setdefault(zone, []).append(interval_vars[block.id])
    for ivs in zone_intervals.values():
        if len(ivs) > 1:
            model.AddNoOverlap(ivs)

    # ── Constraint 4: Tool exclusivity ──
    tool_intervals: dict[str, list[cp_model.IntervalVar]] = {}
    for block in blocks:
        tool = block.required_tool
        if tool is not None and tool.exclusive:
            tool_intervals.setdefault(tool.tool_name, []).append(interval_vars[block.id])
    for ivs in tool_intervals.values():
        if len(ivs) > 1:
            model.AddNoOverlap(ivs)

    # ── Constraint 5: Precedence ──
    for block in blocks:
        for pred_id in block.predecessor_block_ids:
            if pred_id in start_vars:
                pred = block_map[pred_id]
                model.Add(
                    start_vars[block.id]
                    >= start_vars[pred_id] + pred.duration_half_hours
                )

    # ── Constraint 6: Per-axis operational position ──
    # For each axis, group blocks by their declared value; any two blocks with
    # different values on that axis cannot overlap.
    axis_groups: dict[str, dict[str, list[str]]] = {}
    for block in blocks:
        for axis, value in block.position_axes.items():
            axis_groups.setdefault(axis, {}).setdefault(value, []).append(block.id)
    for value_to_blocks in axis_groups.values():
        values = list(value_to_blocks.keys())
        for i in range(len(values)):
            for j in range(i + 1, len(values)):
                for id_a in value_to_blocks[values[i]]:
                    for id_b in value_to_blocks[values[j]]:
                        model.AddNoOverlap([interval_vars[id_a], interval_vars[id_b]])

    # ── Objective: minimize calendar-day makespan ──
    makespan_var = model.NewIntVar(1, len(shift_schedule), "makespan")
    for block in blocks:
        # makespan * SLOTS_PER_DAY >= start + duration  ⇔  ceil((start+dur)/48)
        model.Add(
            makespan_var * SLOTS_PER_DAY
            >= start_vars[block.id] + block.duration_half_hours
        )
    model.Minimize(makespan_var)

    # ── Optional warm start ──
    if warm_start_hints:
        for bid, hint in warm_start_hints.items():
            if bid in start_vars:
                model.AddHint(start_vars[bid], hint)

    # ── Solve ──
    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = float(time_limit_seconds)
    status = solver.Solve(model)
    elapsed = int((time.perf_counter() - start_ms) * 1000)

    if status in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        from .models import ScheduledBlock
        scheduled = []
        for block in blocks:
            start = int(solver.Value(start_vars[block.id]))
            end = start + block.duration_half_hours
            scheduled.append(ScheduledBlock(block.id, start, end, day_index(start)))
        makespan_val = int(solver.Value(makespan_var))
        best_bound = solver.BestObjectiveBound()
        gap = (
            0.0
            if status == cp_model.OPTIMAL
            else (max(0.0, (makespan_val - best_bound) / makespan_val) if makespan_val > 0 else None)
        )
        return ScheduleResult(
            True, None, makespan_val, scheduled, elapsed,
            best_bound=int(round(best_bound)),
            optimality_gap=gap,
        )

    return ScheduleResult(
        False,
        "No feasible schedule found by CP-SAT solver within time limit",
        0, [], elapsed,
    )


def _has_fte(start: int, duration: int, fte_required: int,
             fte_capacity: Sequence[int]) -> bool:
    end = start + duration
    if end > len(fte_capacity):
        return False
    for t in range(start, end):
        if fte_capacity[t] < fte_required:
            return False
    return True
