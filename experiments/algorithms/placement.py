"""Greedy placement engine.

Maintains the partial schedule for the greedy schedulers and answers
*"earliest valid start time"* / placement queries against the six hard
constraints (day containment, FTE capacity, spatial exclusivity, tool
exclusivity, precedence, multi-axis operational position).

Mirrors :file:`backend/src/main/java/com/hls/algorithm/GreedyPlacementEngine.java`.
"""
from __future__ import annotations

from typing import Sequence

from .models import Block, ScheduledBlock, ShiftDay, SLOTS_PER_DAY
from .timeline import (
    build_fte_capacity,
    day_index,
    fits_in_single_day,
    has_sufficient_fte,
    next_day_start,
)


class PlacementEngine:
    def __init__(self, shift_schedule: Sequence[ShiftDay]):
        self.fte_capacity: list[int] = build_fte_capacity(shift_schedule)
        self.horizon_length: int = len(shift_schedule) * SLOTS_PER_DAY
        self.fte_used: list[int] = [0] * self.horizon_length
        self.scheduled: dict[str, ScheduledBlock] = {}
        # zone -> list of [start, end) intervals
        self._zone_occupancy: dict[str, list[tuple[int, int]]] = {}
        # exclusive tool name -> list of [start, end) intervals
        self._tool_occupancy: dict[str, list[tuple[int, int]]] = {}
        # axis -> list of [start, end, value] intervals
        self._axis_occupancy: dict[str, list[tuple[int, int, str]]] = {}

    # ── Queries ──────────────────────────────────────────────────────────

    def find_earliest_valid_start(self, block: Block, min_start: int) -> int:
        """Earliest absolute half-hour at which ``block`` can begin without violating
        any of the six hard constraints, ``-1`` if no such time exists."""
        t = max(min_start, 0)
        dur = block.duration_half_hours
        while t + dur <= self.horizon_length:
            if not fits_in_single_day(t, dur):
                t = next_day_start(t)
                continue
            if not has_sufficient_fte(t, dur, block.fte_requirement,
                                      self.fte_capacity, self.fte_used):
                t += 1
                continue
            if self._has_zone_conflict(block, t, t + dur):
                t += 1
                continue
            if self._has_tool_conflict(block, t, t + dur):
                t += 1
                continue
            if self._has_position_conflict(block, t, t + dur):
                t += 1
                continue
            return t
        return -1

    def get_earliest_predecessor_end(self, block: Block) -> int:
        """End time of the latest-finishing already-scheduled predecessor."""
        min_start = 0
        for pred_id in block.predecessor_block_ids:
            pred = self.scheduled.get(pred_id)
            if pred is not None and pred.end_time > min_start:
                min_start = pred.end_time
        return min_start

    # ── Mutation ─────────────────────────────────────────────────────────

    def place_block(self, block: Block, start_time: int) -> ScheduledBlock:
        end_time = start_time + block.duration_half_hours
        for t in range(start_time, end_time):
            self.fte_used[t] += block.fte_requirement
        for zone in block.occupied_zones:
            self._zone_occupancy.setdefault(zone, []).append((start_time, end_time))
        if block.required_tool is not None and block.required_tool.exclusive:
            self._tool_occupancy.setdefault(block.required_tool.tool_name, []).append(
                (start_time, end_time))
        for axis, value in block.position_axes.items():
            self._axis_occupancy.setdefault(axis, []).append((start_time, end_time, value))
        sb = ScheduledBlock(block.id, start_time, end_time, day_index(start_time))
        self.scheduled[block.id] = sb
        return sb

    # ── Conflict checks ──────────────────────────────────────────────────

    @staticmethod
    def _has_overlap(intervals: list[tuple[int, int]] | None, start: int, end: int) -> bool:
        if not intervals:
            return False
        for (s, e) in intervals:
            if start < e and s < end:
                return True
        return False

    def _has_zone_conflict(self, block: Block, start: int, end: int) -> bool:
        for zone in block.occupied_zones:
            if self._has_overlap(self._zone_occupancy.get(zone), start, end):
                return True
        return False

    def _has_tool_conflict(self, block: Block, start: int, end: int) -> bool:
        tool = block.required_tool
        if tool is None or not tool.exclusive:
            return False
        return self._has_overlap(self._tool_occupancy.get(tool.tool_name), start, end)

    def _has_position_conflict(self, block: Block, start: int, end: int) -> bool:
        if not block.position_axes:
            return False
        for axis, value in block.position_axes.items():
            occ = self._axis_occupancy.get(axis)
            if not occ:
                continue
            for (s, e, existing_value) in occ:
                if start < e and s < end and existing_value != value:
                    return True
        return False
