"""
ATLAS-ML blind test evaluator.

Runs a trained DasCNN against held-out blind test data and reports
accuracy, precision, recall, F1, and a confusion matrix.

Usage:
  python -m atlas_ml.eval_blind --model runs/das_cnn_YYYYMMDD_HHMMSS --data /path/to/BlindTestData
"""

import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import timm
import torch
from PIL import Image
from sklearn.metrics import classification_report, confusion_matrix
from torch.utils.data import DataLoader, Dataset
from torchvision import transforms

from .train_das import DasCNN, DasResNet, make_val_transform


# ---------------------------------------------------------------------------
# Dataset
# ---------------------------------------------------------------------------
class BlindDataset(Dataset):
    def __init__(self, paths, labels, transform=None):
        self.paths = paths
        self.labels = labels
        self.transform = transform

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, idx):
        img = Image.open(self.paths[idx]).convert("L")
        if self.transform:
            img = self.transform(img)
        return img, self.labels[idx]


def load_blind_data(data_dir: Path):
    class_names = sorted(p.name for p in data_dir.iterdir() if p.is_dir())
    class_to_idx = {c: i for i, c in enumerate(class_names)}

    IMAGE_GLOBS = ["*.jpeg", "*.jpg", "*.png", "*.JPEG", "*.JPG", "*.PNG"]

    paths, labels = [], []
    for cls in class_names:
        for pattern in IMAGE_GLOBS:
            for img_path in (data_dir / cls).glob(pattern):
                paths.append(img_path)
                labels.append(class_to_idx[cls])

    return paths, labels, class_names


# ---------------------------------------------------------------------------
# Plot
# ---------------------------------------------------------------------------
def plot_confusion(labels, preds, class_names, out_path):
    cm = confusion_matrix(labels, preds, normalize="true")
    fig, ax = plt.subplots(figsize=(6, 5))
    im = ax.imshow(cm, cmap="Blues", vmin=0, vmax=1)
    plt.colorbar(im, ax=ax)
    ax.set_xticks(range(len(class_names)))
    ax.set_yticks(range(len(class_names)))
    ax.set_xticklabels(class_names, rotation=30, ha="right", fontsize=10)
    ax.set_yticklabels(class_names, fontsize=10)
    for i in range(len(class_names)):
        for j in range(len(class_names)):
            ax.text(j, i, f"{cm[i,j]:.2f}", ha="center", va="center",
                    fontsize=12, color="white" if cm[i, j] > 0.5 else "black")
    ax.set_xlabel("Predicted"); ax.set_ylabel("True")
    ax.set_title("Blind Test — Normalised Confusion Matrix")
    plt.tight_layout()
    plt.savefig(out_path, dpi=150)
    plt.close()
    print(f"Confusion matrix saved to: {out_path}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def evaluate(model_dir: Path, data_dir: Path):
    # Load metadata
    meta_path = model_dir / "model_meta.json"
    meta = json.loads(meta_path.read_text())
    class_names = meta["class_names"]
    img_size    = meta["img_size"]
    num_classes = len(class_names)

    print(f"Model    : {model_dir}")
    print(f"Blind data: {data_dir}")
    print(f"Classes  : {class_names}")
    print(f"Img size : {img_size}×{img_size}\n")

    # Device
    if torch.backends.mps.is_available():
        device = torch.device("mps")
    elif torch.cuda.is_available():
        device = torch.device("cuda")
    else:
        device = torch.device("cpu")
    print(f"Device   : {device}\n")

    # Load model — support EfficientNet-B0-pretrained (current), DasResNet, DasCNN (legacy)
    model_class = meta.get("model", "DasCNN")
    if "EfficientNet" in model_class:
        model = timm.create_model("efficientnet_b0", pretrained=False,
                                  in_chans=1, num_classes=num_classes).to(device)
    elif model_class == "DasResNet":
        model = DasResNet(num_classes=num_classes,
                          stem_kernel=meta.get("stem_kernel", 15)).to(device)
    else:
        model = DasCNN(num_classes=num_classes).to(device)
    model.load_state_dict(torch.load(model_dir / "best_model.pt",
                                     map_location=device))
    model.eval()

    # Load blind data
    paths, labels, blind_classes = load_blind_data(data_dir)
    if not paths:
        print("No images found in blind test directory.")
        return

    from collections import Counter
    counts = Counter(labels)
    print(f"{len(paths)} blind test images:")
    for i, name in enumerate(blind_classes):
        print(f"  {name:25s}: {counts[i]:>6}")
    print()

    # Warn if class order differs
    if blind_classes != class_names:
        print(f"WARNING: blind class order {blind_classes} differs from "
              f"training order {class_names}. Remapping...")
        remap = {blind_classes.index(c): class_names.index(c)
                 for c in class_names if c in blind_classes}
        labels = [remap.get(l, l) for l in labels]

    tf = make_val_transform(img_size)
    dataset = BlindDataset(paths, labels, tf)
    loader  = DataLoader(dataset, batch_size=16, shuffle=False, num_workers=4)

    # Run inference
    all_preds, all_labels, all_probs = [], [], []
    with torch.no_grad():
        for imgs, lbls in loader:
            logits = model(imgs.to(device))
            probs  = torch.softmax(logits, dim=1)
            preds  = logits.argmax(1).cpu()
            all_preds.extend(preds.tolist())
            all_labels.extend(lbls.tolist())
            all_probs.extend(probs.cpu().tolist())

    # Report
    report = classification_report(all_labels, all_preds,
                                   target_names=class_names, digits=3)
    print("── Blind Test Results ──────────────────────────────")
    print(report)

    # Save results
    results_path = model_dir / "blind_test_results.txt"
    results_path.write_text(report)
    print(f"Results saved to: {results_path}")

    plot_confusion(all_labels, all_preds, class_names,
                   model_dir / "blind_test_confusion.png")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True,
                        help="Run directory containing best_model.pt and model_meta.json")
    parser.add_argument("--data",  type=Path, required=True,
                        help="BlindTestData directory with Events/ and NotEvents/ subdirs")
    args = parser.parse_args()
    evaluate(model_dir=args.model, data_dir=args.data)
