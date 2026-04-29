"""Half-hour timeline + FTE-capacity helpers.

Mirrors :file:`backend/src/main/java/com/hls/algorithm/TimelineHelper.java`.
A calendar day always has 48 half-hour slots (``SLOTS_PER_DAY``); blocks must
fit fully within one day.
"""
from __future__ import annotations

from typing import Sequence

from .models import ShiftDay, SLOTS_PER_DAY


def build_fte_capacity(shift_schedule: Sequence[ShiftDay]) -> list[int]:
    """Return per-half-hour FTE supply across the full horizon."""
    horizon = len(shift_schedule) * SLOTS_PER_DAY
    cap = [0] * horizon
    for day_idx, day in enumerate(shift_schedule):
        offset = day_idx * SLOTS_PER_DAY
        for shift in day.shifts:
            start = offset + shift.start_half_hour
            end = offset + shift.end_half_hour
            for t in range(start, min(end, horizon)):
                cap[t] += shift.fte
    return cap


def day_index(absolute_time: int) -> int:
    """Day index (1-based) of a given absolute half-hour."""
    return absolute_time // SLOTS_PER_DAY + 1


def fits_in_single_day(start_time: int, duration: int) -> bool:
    return (start_time // SLOTS_PER_DAY) == ((start_time + duration - 1) // SLOTS_PER_DAY)


def next_day_start(absolute_time: int) -> int:
    return ((absolute_time // SLOTS_PER_DAY) + 1) * SLOTS_PER_DAY


def has_sufficient_fte(start_time: int, duration: int, fte_required: int,
                       fte_capacity: Sequence[int], fte_used: Sequence[int]) -> bool:
    for t in range(start_time, start_time + duration):
        if t >= len(fte_capacity):
            return False
        if fte_capacity[t] - fte_used[t] < fte_required:
            return False
    return True
