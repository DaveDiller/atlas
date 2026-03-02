"""
ATLAS-ML inference — classify a seismic spectrogram image.

Loads best_model.pt from a training run and predicts the class of one or more
PNG spectrogram images, with a confidence score and an "uncertain" flag for
predictions below the threshold stored in model_meta.json.

Usage:
  python -m atlas_ml.predict path/to/spectrogram.png
  python -m atlas_ml.predict path/to/spectrogram.png --model runs/atlas_ml_20260302_142439
  python -m atlas_ml.predict data/stead_spectrograms/seismic_event/*.png
"""

import argparse
import json
import sys
from pathlib import Path

import timm
import torch
from PIL import Image
from torchvision import transforms

from .config import IMG_SIZE, RUNS_DIR

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


def load_model(run_dir: Path):
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


def predict(image_paths: list[Path], run_dir: Path):
    model, class_names, threshold, device = load_model(run_dir)

    print(f"Model   : {run_dir.name}  (val acc {json.loads((run_dir / 'model_meta.json').read_text())['val_accuracy']:.1%})")
    print(f"Device  : {device}")
    print(f"Classes : {', '.join(class_names)}")
    print(f"Threshold: {threshold:.0%}  (below → uncertain)\n")

    for img_path in image_paths:
        if not img_path.exists():
            print(f"  {img_path.name:50s}  NOT FOUND")
            continue

        class_name, confidence, uncertain = predict_image(
            model, img_path, class_names, threshold, device)

        flag = "  ⚠ uncertain" if uncertain else ""
        print(f"  {img_path.name:50s}  {class_name:25s}  {confidence:.1%}{flag}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Classify seismic spectrogram images with ATLAS-ML")
    parser.add_argument(
        "images", nargs="+", type=Path,
        help="One or more spectrogram PNG files to classify")
    parser.add_argument(
        "--model", type=Path, default=None,
        help="Path to a training run directory (default: latest run)")
    args = parser.parse_args()

    run_dir = args.model if args.model else find_latest_run()
    predict(args.images, run_dir)
