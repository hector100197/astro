"""Loader for the YAML scenario catalog in ``scenarios/``."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Iterator

import yaml


def _scenarios_dir() -> Path:
    """Walk up from this file to find the repo's ``scenarios/`` directory."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "scenarios"
        if (candidate / "pleiades.yaml").exists():
            return candidate
    raise FileNotFoundError(
        f"Could not locate the scenarios/ directory near {here}. "
        f"Run from a checkout of the astro repository."
    )


def list_scenarios() -> list[str]:
    """Return the names of every YAML scenario in the catalog (without extension)."""
    return sorted(p.stem for p in _scenarios_dir().glob("*.yaml"))


def load_scenario(name: str) -> dict[str, Any]:
    """Load a scenario YAML by name (without extension)."""
    path = _scenarios_dir() / f"{name}.yaml"
    if not path.exists():
        raise FileNotFoundError(
            f"Scenario {name!r} not found. Available: {list_scenarios()}"
        )
    with path.open() as f:
        return yaml.safe_load(f)


def iter_scenarios() -> Iterator[tuple[str, dict[str, Any]]]:
    """Iterate (name, content) for every scenario in the catalog."""
    for p in sorted(_scenarios_dir().glob("*.yaml")):
        with p.open() as f:
            yield p.stem, yaml.safe_load(f)
