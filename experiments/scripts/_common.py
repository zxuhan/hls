"""Tiny shared helpers for the experiment scripts."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data"
RESULTS_DIR = ROOT / "results"


def resolve_data(arg: str) -> Path:
    """Resolve ``--data`` argument: bare filename → look in ``data/``;
    otherwise treat as path."""
    p = Path(arg)
    if p.is_absolute() or p.parts[:1] in (("..",), (".",)):
        return p
    if "/" in arg or "\\" in arg:
        return p
    return DATA_DIR / arg
