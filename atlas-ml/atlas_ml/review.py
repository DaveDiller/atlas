"""
ATLAS-ML human review CLI — triage classified images from pending/.

Walks pending/ one image at a time, opens it with the system viewer, shows the
predicted class and confidence from classification_log.csv, and prompts for a
decision.

Usage:
  python -m atlas_ml.review                     # review pending/ (default)
  python -m atlas_ml.review --pending PATH      # custom pending dir
  python -m atlas_ml.review --no-open           # don't open images (headless)

Actions at the prompt:
  ENTER  — confirm prediction  → verified/<predicted_class>/
  c      — correct prediction  → choose class → verified/<chosen_class>/
  r      — reject              → rejected/
  q      — quit and print summary
"""

import argparse
import csv
import json
import shutil
import subprocess
import sys
from collections import Counter
from pathlib import Path

from .config import (
    CLASSES,
    INFERENCE_LOG,
    INFERENCE_PENDING,
    INFERENCE_VERIFIED,
    RUNS_DIR,
)

REJECTED_DIR = INFERENCE_VERIFIED.parent / "rejected"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _load_log_index(log_path: Path) -> dict[str, dict]:
    """Return {filename: row_dict} from classification_log.csv."""
    index: dict[str, dict] = {}
    if not log_path.exists():
        return index
    with log_path.open(newline="") as f:
        for row in csv.DictReader(f):
            index[row["filename"]] = row
    return index


def _class_names_from_meta() -> list[str]:
    """Read class names from the latest run's model_meta.json."""
    runs = sorted(RUNS_DIR.glob("atlas_ml_*"))
    if not runs:
        return CLASSES
    meta_path = runs[-1] / "model_meta.json"
    if not meta_path.exists():
        return CLASSES
    meta = json.loads(meta_path.read_text())
    return meta.get("class_names", CLASSES)


def _collect_images(pending_dir: Path) -> list[tuple[Path, str]]:
    """Return list of (img_path, predicted_class) from all pending subdirs."""
    items = []
    for class_dir in sorted(pending_dir.iterdir()):
        if not class_dir.is_dir():
            continue
        for img in sorted(class_dir.glob("*.png")):
            items.append((img, class_dir.name))
    return items


def _open_image(img_path: Path, no_open: bool):
    """Open the image with the platform viewer (non-blocking)."""
    if no_open:
        return
    try:
        subprocess.Popen(["open", str(img_path)],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def _move(src: Path, dest_dir: Path):
    dest_dir.mkdir(parents=True, exist_ok=True)
    shutil.move(str(src), dest_dir / src.name)


def _verified_counts() -> dict[str, int]:
    counts: dict[str, int] = {}
    if INFERENCE_VERIFIED.exists():
        for d in INFERENCE_VERIFIED.iterdir():
            if d.is_dir():
                counts[d.name] = len(list(d.glob("*.png")))
    return counts


# ---------------------------------------------------------------------------
# Main review loop
# ---------------------------------------------------------------------------

def review(pending_dir: Path, no_open: bool):
    class_names = _class_names_from_meta()
    log_index = _load_log_index(INFERENCE_LOG)

    images = _collect_images(pending_dir)
    if not images:
        print(f"No images found in {pending_dir}")
        return

    total = len(images)
    stats = Counter()  # confirmed / corrected / rejected

    print(f"Found {total} image(s) to review in {pending_dir}\n")
    print("  ENTER=confirm   c=correct   r=reject   q=quit\n")

    for i, (img_path, predicted_class) in enumerate(images, 1):
        log_row = log_index.get(img_path.name, {})
        confidence = log_row.get("confidence", "unknown")
        uncertain  = log_row.get("uncertain", "")

        conf_str = f"{float(confidence):.1%}" if confidence != "unknown" else "unknown"
        flag = "  ⚠ uncertain" if uncertain == "True" else ""

        print(f"[{i}/{total}]  {img_path.name}")
        print(f"  Predicted : {predicted_class}  ({conf_str}){flag}")

        _open_image(img_path, no_open)

        choice = input("  > ").strip().lower()

        if choice in ("", "y"):
            _move(img_path, INFERENCE_VERIFIED / predicted_class)
            stats["confirmed"] += 1

        elif choice == "c":
            for idx, name in enumerate(class_names, 1):
                print(f"    {idx}  {name}")
            pick = input("  Class number: ").strip()
            try:
                chosen = class_names[int(pick) - 1]
            except (ValueError, IndexError):
                print("  Invalid choice — skipping.")
                continue
            _move(img_path, INFERENCE_VERIFIED / chosen)
            stats["corrected"] += 1

        elif choice == "r":
            _move(img_path, REJECTED_DIR)
            stats["rejected"] += 1

        elif choice == "q":
            print("  Quitting early.")
            break

        else:
            print("  Unrecognised input — skipping.")
            continue

        print()

    # Summary
    reviewed = stats["confirmed"] + stats["corrected"] + stats["rejected"]
    print(f"\nReviewed {reviewed} image(s):  "
          f"{stats['confirmed']} confirmed  "
          f"{stats['corrected']} corrected  "
          f"{stats['rejected']} rejected")

    vc = _verified_counts()
    if vc:
        detail = "  ".join(f"{k}={v}" for k, v in sorted(vc.items()))
        print(f"Verified pool: {detail}")


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Human review triage for ATLAS-ML classified images")
    parser.add_argument(
        "--pending", type=Path, default=INFERENCE_PENDING,
        help=f"Directory of classified images awaiting review (default: {INFERENCE_PENDING})")
    parser.add_argument(
        "--no-open", action="store_true", default=False,
        help="Don't open images in the system viewer (useful in headless environments)")

    args = parser.parse_args()

    if not args.pending.exists():
        print(f"Pending directory not found: {args.pending}")
        sys.exit(1)

    review(args.pending, no_open=args.no_open)
