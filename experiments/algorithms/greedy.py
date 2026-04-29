"""Candidate A — priority-rule greedy schedulers (MTS, SPT)."""
from __future__ import annotations

import time
from typing import Sequence

from .models import Block, ScheduleResult, ShiftDay
from .placement import PlacementEngine
from .precedence import compute_total_successors, has_cycle


def _schedule_with_priority(
    blocks: Sequence[Block],
    shift_schedule: Sequence[ShiftDay],
    priority_values: dict[str, int],
    ascending: bool,
) -> ScheduleResult:
    """Generic greedy constructor parametrised by a priority map.

    ``ascending=True`` means *smaller priority value* = higher priority (used by SPT).
    Tie-break is alphabetical block-id, matching the Java backend.
    """
    start_ms = time.perf_counter()

    if has_cycle(blocks):
        elapsed = int((time.perf_counter() - start_ms) * 1000)
        return ScheduleResult(False, "Precedence cycle detected", 0, [], elapsed)

    block_map = {b.id: b for b in blocks}
    block_ids = set(block_map)
    engine = PlacementEngine(shift_schedule)
    remaining = set(block_ids)

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

        if ascending:
            schedulable.sort(key=lambda b: (priority_values.get(b.id, 0), b.id))
        else:
            schedulable.sort(key=lambda b: (-priority_values.get(b.id, 0), b.id))

        selected = schedulable[0]
        min_start = engine.get_earliest_predecessor_end(selected)
        start_time = engine.find_earliest_valid_start(selected, min_start)
        if start_time < 0:
            elapsed = int((time.perf_counter() - start_ms) * 1000)
            return ScheduleResult(
                False,
                f"No feasible schedule found: block {selected.id} "
                f"(duration {selected.duration_half_hours} half-hours) "
                f"cannot fit in any available window",
                0, [], elapsed,
            )

        engine.place_block(selected, start_time)
        remaining.remove(selected.id)

    result = list(engine.scheduled.values())
    makespan = max(sb.day_index for sb in result) if result else 0
    elapsed = int((time.perf_counter() - start_ms) * 1000)
    return ScheduleResult(True, None, makespan, result, elapsed)


def schedule_mts(blocks: Sequence[Block], shift_schedule: Sequence[ShiftDay]) -> ScheduleResult:
    """Most Total Successors — pick the block with the most direct + indirect successors."""
    priority = compute_total_successors(blocks)
    return _schedule_with_priority(blocks, shift_schedule, priority, ascending=False)


def schedule_spt(blocks: Sequence[Block], shift_schedule: Sequence[ShiftDay]) -> ScheduleResult:
    """Shortest Processing Time — pick the block with the smallest duration."""
    priority = {b.id: b.duration_half_hours for b in blocks}
    return _schedule_with_priority(blocks, shift_schedule, priority, ascending=True)
