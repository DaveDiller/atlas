# data_stats.py — count labeled images in a training data directory

from pathlib import Path

_IMAGE_SUFFIXES = {".jpeg", ".jpg", ".png"}


def count_class_images(data_dir: Path) -> dict:
    """
    Scan immediate subdirectories of data_dir and count image files.
    Returns {"ClassName": count, ...} or {"error": "message"} if invalid.
    """
    if not data_dir.exists():
        return {"error": f"Path does not exist: {data_dir}"}
    if not data_dir.is_dir():
        return {"error": f"Not a directory: {data_dir}"}

    counts = {}
    for subdir in sorted(data_dir.iterdir()):
        if subdir.is_dir():
            n = sum(
                1 for f in subdir.iterdir()
                if f.is_file() and f.suffix.lower() in _IMAGE_SUFFIXES
            )
            counts[subdir.name] = n

    if not counts:
        return {"error": "No class subdirectories found"}

    return counts
