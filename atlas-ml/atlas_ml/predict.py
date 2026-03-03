"""
ATLAS-ML inference — classify seismic spectrogram images.

Loads best_model.pt from a training run and predicts the class of one or more
PNG spectrogram images, with a confidence score and an "uncertain" flag for
predictions below the threshold stored in model_meta.json.

Single-shot mode — classify a fixed list of images:
  python -m atlas_ml.predict path/to/spectrogram.png
  python -m atlas_ml.predict image1.png image2.png --model runs/atlas_ml_20260302_142439

Watch mode — monitor a directory and classify new images as they arrive:
  python -m atlas_ml.predict --watch inbox/ --interval 5
  python -m atlas_ml.predict --watch inbox/ --interval 30 --model runs/atlas_ml_20260302_142439

In watch mode, each new PNG that appears in the watched directory is classified
once and not re-processed. Press Ctrl+C to stop.

Add --route to enable file routing to pending/ and CSV logging.
"""

import argparse
import csv
import json
import shutil
import sys
import time
from datetime import datetime
from pathlib import Path

import timm
import torch
from PIL import Image
from torchvision import transforms

from .config import IMG_SIZE, INFERENCE_LOG, INFERENCE_PENDING, RUNS_DIR

MEAN = [0.485, 0.456, 0.406]
STD  = [0.229, 0.224, 0.225]

infer_tf = transforms.Compose([
    transforms.Resize(int(IMG_SIZE * 1.14)),
    transforms.CenterCrop(IMG_SIZE),
    transforms.ToTensor(),
    transforms.Normalize(MEAN, STD),
])


def get_device():
    if torch.backends.mps.is_available():
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


def find_latest_run() -> Path:
    """Return the most recently created run directory."""
    runs = sorted(RUNS_DIR.glob("atlas_ml_*"))
    if not runs:
        print(f"No training runs found in {RUNS_DIR}")
        print("Train a model first:  python -m atlas_ml.train")
        sys.exit(1)
    return runs[-1]


def load_model(run_dir: Path, device: torch.device = None):
    """Load model weights and metadata from a run directory."""
    meta_path  = run_dir / "model_meta.json"
    model_path = run_dir / "best_model.pt"

    if not meta_path.exists():
        print(f"model_meta.json not found in {run_dir}")
        sys.exit(1)
    if not model_path.exists():
        print(f"best_model.pt not found in {run_dir}")
        sys.exit(1)

    meta = json.loads(meta_path.read_text())
    class_names = meta["class_names"]
    threshold   = meta.get("confidence_threshold", 0.6)

    if device is None:
        device = get_device()
    model = timm.create_model("efficientnet_b0", pretrained=False,
                              num_classes=len(class_names))
    model.load_state_dict(torch.load(model_path, map_location=device))
    model.to(device)
    model.eval()

    return model, class_names, threshold, device


def predict_image(model, img_path: Path, class_names, threshold, device):
    """Return (class_name, confidence, uncertain) for a single image."""
    img = Image.open(img_path).convert("RGB")
    tensor = infer_tf(img).unsqueeze(0).to(device)

    with torch.no_grad():
        logits = model(tensor)
        probs  = torch.softmax(logits, dim=1)[0]

    confidence, idx = probs.max(0)
    confidence = confidence.item()
    class_name = class_names[idx.item()]
    uncertain  = confidence < threshold

    return class_name, confidence, uncertain


def _print_header(run_dir, class_names, threshold, device):
    meta = json.loads((run_dir / "model_meta.json").read_text())
    print(f"Model    : {run_dir.name}  (val acc {meta['val_accuracy']:.1%})")
    print(f"Device   : {device}")
    print(f"Classes  : {', '.join(class_names)}")
    print(f"Threshold: {threshold:.0%}  (below → uncertain)\n")


def _format_result(img_path: Path, class_name, confidence, uncertain) -> str:
    flag = "  ⚠ uncertain" if uncertain else ""
    return f"  {img_path.name:50s}  {class_name:25s}  {confidence:.1%}{flag}"


def predict(image_paths: list[Path], run_dir: Path, device: torch.device = None):
    """Single-shot: classify a fixed list of images and exit."""
    model, class_names, threshold, device = load_model(run_dir, device)
    _print_header(run_dir, class_names, threshold, device)

    for img_path in image_paths:
        if not img_path.exists():
            print(f"  {img_path.name:50s}  NOT FOUND")
            continue
        class_name, confidence, uncertain = predict_image(
            model, img_path, class_names, threshold, device)
        print(_format_result(img_path, class_name, confidence, uncertain))


def _log_classification(img_path: Path, class_name: str, confidence: float, uncertain: bool):
    """Append one row to INFERENCE_LOG, creating the file with headers if needed."""
    write_header = not INFERENCE_LOG.exists()
    with INFERENCE_LOG.open("a", newline="") as f:
        writer = csv.writer(f)
        if write_header:
            writer.writerow(["timestamp", "filename", "source_path", "predicted_class", "confidence", "uncertain"])
        writer.writerow([
            datetime.now().isoformat(timespec="seconds"),
            img_path.name,
            str(img_path),
            class_name,
            f"{confidence:.4f}",
            uncertain,
        ])


def watch(watch_dir: Path, interval: float, run_dir: Path, device: torch.device = None,
          route: bool = False):
    """Watch mode: poll a directory every `interval` seconds, classify new PNGs.

    When route=True, each classified file is moved to pending/<class>/ and a row
    is appended to classification_log.csv.
    """
    model, class_names, threshold, device = load_model(run_dir, device)
    _print_header(run_dir, class_names, threshold, device)

    route_note = "  [routing ON → pending/]" if route else ""
    print(f"Watching : {watch_dir}  (every {interval}s)  — Ctrl+C to stop{route_note}\n")

    seen = set(watch_dir.glob("*.png"))  # don't re-process files already present

    try:
        while True:
            time.sleep(interval)
            current = set(watch_dir.glob("*.png"))
            new_files = sorted(current - seen)
            for img_path in new_files:
                class_name, confidence, uncertain = predict_image(
                    model, img_path, class_names, threshold, device)
                ts = datetime.now().strftime("%H:%M:%S")
                print(f"[{ts}] {_format_result(img_path, class_name, confidence, uncertain)}")

                if route:
                    dest_dir = INFERENCE_PENDING / class_name
                    dest_dir.mkdir(parents=True, exist_ok=True)
                    shutil.move(str(img_path), dest_dir / img_path.name)
                    _log_classification(img_path, class_name, confidence, uncertain)

            seen = current
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Classify seismic spectrogram images with ATLAS-ML")

    # Single-shot mode
    parser.add_argument(
        "images", nargs="*", type=Path,
        help="One or more spectrogram PNG files to classify (single-shot mode)")

    # Watch mode
    parser.add_argument(
        "--watch", type=Path, default=None, metavar="DIR",
        help="Directory to monitor for new PNG files (watch mode)")
    parser.add_argument(
        "--interval", type=float, default=10.0, metavar="SECONDS",
        help="Poll interval in seconds for watch mode (default: 10)")

    parser.add_argument(
        "--route", action="store_true", default=False,
        help="Move classified files to pending/<class>/ and log to classification_log.csv")
    parser.add_argument(
        "--model", type=Path, default=None,
        help="Path to a training run directory (default: latest run)")
    parser.add_argument(
        "--device", type=str, default=None, choices=["cpu", "mps", "cuda"],
        help="Device to run inference on (default: auto-detect)")

    args = parser.parse_args()
    run_dir = args.model if args.model else find_latest_run()
    device  = torch.device(args.device) if args.device else None

    if args.watch:
        if not args.watch.is_dir():
            print(f"Watch directory not found: {args.watch}")
            sys.exit(1)
        watch(args.watch, args.interval, run_dir, device, route=args.route)
    elif args.images:
        predict(args.images, run_dir, device)
    else:
        parser.print_help()
