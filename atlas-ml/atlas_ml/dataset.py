"""
ATLAS-ML dataset builder.

Combines ESC-50 spectrograms and STEAD spectrograms into a single
labeled dataset ready for CNN training.

Usage:
  from atlas_ml.dataset import build_combined_dataset
  build_combined_dataset()   # copies/symlinks images into data/combined_spectrograms/
"""

import shutil
from collections import Counter
from pathlib import Path

import pandas as pd

from .config import (
    CLASSES, ESC50_CLASS_MAP,
    ESC50_SPECTROGRAMS, STEAD_SPECTROGRAMS, COMBINED_DATASET,
)


def build_esc50_split(combined_dir: Path) -> dict:
    """
    Copy ESC-50 spectrograms into combined_dir, remapped to ATLAS classes.
    Returns counts per class.
    """
    counts = Counter()
    skipped_categories = []

    for category_dir in sorted(ESC50_SPECTROGRAMS.iterdir()):
        if not category_dir.is_dir():
            continue

        atlas_class = ESC50_CLASS_MAP.get(category_dir.name)
        if atlas_class is None:
            skipped_categories.append(category_dir.name)
            continue

        dest_dir = combined_dir / atlas_class
        dest_dir.mkdir(parents=True, exist_ok=True)

        for img in category_dir.glob("*.png"):
            dest = dest_dir / f"esc50_{category_dir.name}_{img.name}"
            if not dest.exists():
                shutil.copy2(img, dest)
            counts[atlas_class] += 1

    print(f"  ESC-50: {sum(counts.values())} images from "
          f"{len(ESC50_CLASS_MAP)} categories "
          f"({len(skipped_categories)} ignored)")
    return counts


def build_stead_split(combined_dir: Path) -> dict:
    """
    Copy STEAD spectrograms into combined_dir under seismic_event / seismic_noise.
    Returns counts per class.
    """
    counts = Counter()

    if not STEAD_SPECTROGRAMS.exists():
        print("  STEAD spectrograms not yet generated — skipping.")
        print(f"  Run: python generate_stead_spectrograms.py")
        return counts

    for atlas_class in ["seismic_event", "seismic_noise"]:
        src_dir = STEAD_SPECTROGRAMS / atlas_class
        if not src_dir.exists():
            print(f"  STEAD: no {atlas_class} directory found, skipping")
            continue

        dest_dir = combined_dir / atlas_class
        dest_dir.mkdir(parents=True, exist_ok=True)

        for img in src_dir.glob("*.png"):
            dest = dest_dir / f"stead_{img.name}"
            if not dest.exists():
                shutil.copy2(img, dest)
            counts[atlas_class] += 1

    if counts:
        print(f"  STEAD: {sum(counts.values())} images "
              f"({counts['seismic_event']} events, {counts['seismic_noise']} noise)")
    return counts


def build_combined_dataset(force: bool = False) -> dict:
    """
    Build the combined dataset from ESC-50 and STEAD spectrograms.
    Images are copied into data/combined_spectrograms/{class}/

    Args:
        force: if True, rebuild from scratch even if directory exists

    Returns:
        dict of class -> count
    """
    if force and COMBINED_DATASET.exists():
        shutil.rmtree(COMBINED_DATASET)
        print(f"Cleared existing dataset at {COMBINED_DATASET}")

    COMBINED_DATASET.mkdir(parents=True, exist_ok=True)
    print(f"Building combined dataset → {COMBINED_DATASET}\n")

    all_counts = Counter()
    all_counts.update(build_esc50_split(COMBINED_DATASET))
    all_counts.update(build_stead_split(COMBINED_DATASET))

    print(f"\nDataset summary:")
    total = 0
    for cls in CLASSES:
        n = all_counts.get(cls, 0)
        total += n
        status = "MISSING — seismic data not yet available" if n == 0 else f"{n:>6} images"
        print(f"  {cls:25s}: {status}")
    print(f"  {'TOTAL':25s}: {total:>6} images")

    return dict(all_counts)


if __name__ == "__main__":
    build_combined_dataset()
