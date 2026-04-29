"""Precedence DAG helpers (cycle check, total successors, critical-path remaining).

Mirrors :file:`backend/src/main/java/com/hls/algorithm/PrecedenceHelper.java`.
"""
from __future__ import annotations

from collections import defaultdict, deque
from typing import Mapping, Sequence

from .models import Block


def build_successor_map(blocks: Sequence[Block]) -> dict[str, list[str]]:
    """Successor adjacency list from blocks' predecessor lists."""
    succ: dict[str, list[str]] = {b.id: [] for b in blocks}
    block_ids = set(succ)
    for b in blocks:
        for pred in b.predecessor_block_ids:
            if pred in block_ids:
                succ.setdefault(pred, []).append(b.id)
    return succ


def topological_sort(blocks: Sequence[Block]) -> list[Block]:
    """Kahn's algorithm. Raises ``ValueError`` if a cycle exists."""
    block_map = {b.id: b for b in blocks}
    succ = build_successor_map(blocks)
    in_deg: dict[str, int] = {b.id: 0 for b in blocks}
    for b in blocks:
        for pred in b.predecessor_block_ids:
            if pred in block_map:
                in_deg[b.id] += 1
    q: deque[str] = deque(bid for bid, d in in_deg.items() if d == 0)
    out: list[Block] = []
    while q:
        bid = q.popleft()
        out.append(block_map[bid])
        for s in succ.get(bid, ()):
            in_deg[s] -= 1
            if in_deg[s] == 0:
                q.append(s)
    if len(out) != len(blocks):
        raise ValueError("precedence cycle detected")
    return out


def has_cycle(blocks: Sequence[Block]) -> bool:
    try:
        topological_sort(blocks)
        return False
    except ValueError:
        return True


def compute_total_successors(blocks: Sequence[Block]) -> dict[str, int]:
    """For each block, the number of distinct direct + indirect successors."""
    succ = build_successor_map(blocks)
    cache: dict[str, int] = {}

    def reachable_count(bid: str) -> int:
        if bid in cache:
            return cache[bid]
        reachable: set[str] = set()
        stack: list[str] = list(succ.get(bid, ()))
        while stack:
            cur = stack.pop()
            if cur in reachable:
                continue
            reachable.add(cur)
            stack.extend(succ.get(cur, ()))
        cache[bid] = len(reachable)
        return cache[bid]

    return {b.id: reachable_count(b.id) for b in blocks}


def compute_critical_path_remaining(blocks: Sequence[Block]) -> dict[str, int]:
    """Longest remaining duration (in half-hours) from each block to a terminal node."""
    succ = build_successor_map(blocks)
    sorted_blocks = topological_sort(blocks)
    cpr: dict[str, int] = {}
    # Process in reverse topological order so successors are scored first
    for b in reversed(sorted_blocks):
        max_succ = 0
        for s_id in succ.get(b.id, ()):
            if s_id in cpr:
                max_succ = max(max_succ, cpr[s_id])
        cpr[b.id] = b.duration_half_hours + max_succ
    return cpr
