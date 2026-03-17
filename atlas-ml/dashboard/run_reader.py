# run_reader.py — scan runs/ directory and parse model_meta.json

from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Optional
import json
import re

_RUN_NAME_RE = re.compile(r"(\d{8}_\d{6})$")
_ACCURACY_RE = re.compile(r"accuracy\s+([\d.]+)")


@dataclass
class RunInfo:
    name: str
    started_at: Optional[datetime]
    accuracy: Optional[float]
    blind_accuracy: Optional[float]
    epochs: Optional[int]
    model: Optional[str]
    trained_on: Optional[str]
    training_time: Optional[str]
    is_best: bool = False


def _parse_blind_accuracy(run_dir: Path) -> Optional[float]:
    results_file = run_dir / "blind_test_results.txt"
    if not results_file.exists():
        return None
    try:
        text = results_file.read_text()
        m = _ACCURACY_RE.search(text)
        if m:
            return float(m.group(1))
    except Exception:
        pass
    return None


def _parse_run(run_dir: Path) -> Optional[RunInfo]:
    meta_path = run_dir / "model_meta.json"

    # Parse start time from directory name
    m = _RUN_NAME_RE.search(run_dir.name)
    started_at = None
    if m:
        try:
            started_at = datetime.strptime(m.group(1), "%Y%m%d_%H%M%S")
        except ValueError:
            pass

    if not meta_path.exists():
        return RunInfo(
            name=run_dir.name,
            started_at=started_at,
            accuracy=None,
            blind_accuracy=None,
            epochs=None,
            model=None,
            trained_on=None,
            training_time=None,
        )

    try:
        meta = json.loads(meta_path.read_text())
    except Exception:
        return None

    accuracy = meta.get("best_val_accuracy") or meta.get("val_accuracy")

    # Handle schema variations: older runs use "epochs", newer use p1+p2
    p1 = meta.get("p1_epochs", 0) or 0
    p2 = meta.get("p2_epochs", 0) or 0
    epochs = (p1 + p2) if (p1 or p2) else meta.get("epochs")

    # Estimate training time from dashboard sidecar if present, else mtime delta
    training_time = None
    sidecar = run_dir / "dashboard_meta.json"
    if sidecar.exists():
        try:
            dm = json.loads(sidecar.read_text())
            start = dm.get("start_time")
            end = dm.get("end_time")
            if start and end:
                secs = int(end - start)
                training_time = f"{secs // 60}m {secs % 60}s"
        except Exception:
            pass

    if training_time is None:
        # Fall back to mtime of best_model.pt minus mtime of first file written
        best_pt = run_dir / "best_model.pt"
        if best_pt.exists() and started_at:
            secs = int(best_pt.stat().st_mtime - started_at.timestamp())
            if 0 < secs < 86400:
                training_time = f"{secs // 60}m {secs % 60}s"

    return RunInfo(
        name=run_dir.name,
        started_at=started_at,
        accuracy=accuracy,
        blind_accuracy=_parse_blind_accuracy(run_dir),
        epochs=epochs,
        model=meta.get("model"),
        trained_on=meta.get("trained_on"),
        training_time=training_time,
    )


def list_runs(runs_dir: Path) -> list[RunInfo]:
    if not runs_dir.exists():
        return []

    runs = []
    for d in sorted(runs_dir.iterdir(), reverse=True):
        if d.is_dir():
            info = _parse_run(d)
            if info:
                runs.append(info)

    # Mark best run
    best_acc = max((r.accuracy for r in runs if r.accuracy is not None), default=None)
    if best_acc is not None:
        for r in runs:
            if r.accuracy == best_acc:
                r.is_best = True
                break

    return runs
