"""Immutable data classes for the HLS scheduling problem.

Field shapes mirror the Java backend's records (see ../backend/src/main/java/com/hls/model/).
All time quantities are in **half-hour units**; the Excel loader does the hour-to-half-hour conversion.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping, Optional, Sequence


@dataclass(frozen=True)
class ToolRequirement:
    tool_name: str
    exclusive: bool


@dataclass(frozen=True)
class Block:
    id: str
    duration_half_hours: int
    fte_requirement: int
    occupied_zones: frozenset[str] = field(default_factory=frozenset)
    position_axes: Mapping[str, str] = field(default_factory=dict)
    required_tool: Optional[ToolRequirement] = None
    predecessor_block_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class Shift:
    """Half-hour internal representation. ``end_half_hour`` is exclusive."""
    start_half_hour: int
    end_half_hour: int
    fte: int


@dataclass(frozen=True)
class ShiftDay:
    shifts: tuple[Shift, ...]


@dataclass(frozen=True)
class ScheduledBlock:
    block_id: str
    start_time: int
    end_time: int
    day_index: int


@dataclass(frozen=True)
class ScheduleResult:
    success: bool
    error_message: Optional[str]
    makespan: int
    scheduled_blocks: Sequence[ScheduledBlock]
    runtime_ms: int
    best_bound: Optional[int] = None
    optimality_gap: Optional[float] = None


SLOTS_PER_DAY = 48
"""Half-hour slots per calendar day (48 = 24 hours × 2)."""
