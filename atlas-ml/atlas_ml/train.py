"""
ATLAS-ML training pipeline.

Trains EfficientNet-B0 on the combined ESC-50 + STEAD spectrogram dataset.
Uses two-phase training: frozen backbone → full fine-tune.

Usage:
  python -m atlas_ml.train
  python -m atlas_ml.train --data data/combined_spectrograms
"""

import argparse
import json
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import timm
import torch
import torch.nn as nn
from PIL import Image
from sklearn.metrics import classification_report, confusion_matrix
from sklearn.model_selection import train_test_split
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler
from torchvision import transforms

from .config import (
    CLASSES, IMG_SIZE, BATCH_SIZE, NUM_WORKERS, SEED,
    LABEL_SMOOTHING, WEIGHT_DECAY, CONFIDENCE_THRESHOLD,
    P1_EPOCHS, P1_LR, P2_EPOCHS, P2_LR, P2_LR_BB,
    COMBINED_DATASET, RUNS_DIR,
)

torch.manual_seed(SEED)
np.random.seed(SEED)


# ---------------------------------------------------------------------------
# Device
# ---------------------------------------------------------------------------
def get_device():
    if torch.backends.mps.is_available():
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


# ---------------------------------------------------------------------------
# Dataset
# ---------------------------------------------------------------------------
def load_dataset(data_dir: Path):
    """Scan class subdirectories, return paths + integer labels + class names.

    Class discovery: uses the canonical CLASSES order when those directories
    exist; otherwise auto-discovers all subdirectories (sorted) so the loader
    works with any labelled image dataset, e.g. Events/NotEvents.

    Image formats: PNG, JPEG, JPG.
    """
    configured = [c for c in CLASSES if (data_dir / c).exists()]
    if configured:
        class_names = configured
    else:
        class_names = sorted(p.name for p in data_dir.iterdir() if p.is_dir())

    class_to_idx = {c: i for i, c in enumerate(class_names)}

    IMAGE_GLOBS = ["*.png", "*.jpg", "*.jpeg", "*.PNG", "*.JPG", "*.JPEG"]

    paths, labels = [], []
    for cls in class_names:
        for pattern in IMAGE_GLOBS:
            for img_path in (data_dir / cls).glob(pattern):
                paths.append(img_path)
                labels.append(class_to_idx[cls])

    return paths, labels, class_names


class SpectrogramDataset(Dataset):
    def __init__(self, paths, labels, transform=None):
        self.paths = paths
        self.labels = labels
        self.transform = transform

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, idx):
        img = Image.open(self.paths[idx]).convert("RGB")
        if self.transform:
            img = self.transform(img)
        return img, self.labels[idx]


# ---------------------------------------------------------------------------
# Transforms
# ---------------------------------------------------------------------------
MEAN = [0.485, 0.456, 0.406]
STD  = [0.229, 0.224, 0.225]

# Note: horizontal flip is meaningful (time direction) but vertical flip is not
# (flipping frequency axis changes the physical meaning)
train_tf = transforms.Compose([
    transforms.RandomResizedCrop(IMG_SIZE, scale=(0.7, 1.0)),
    transforms.RandomHorizontalFlip(),       # time shift augmentation
    transforms.ColorJitter(brightness=0.3, contrast=0.3),
    transforms.ToTensor(),
    transforms.Normalize(MEAN, STD),
])

val_tf = transforms.Compose([
    transforms.Resize(int(IMG_SIZE * 1.14)),
    transforms.CenterCrop(IMG_SIZE),
    transforms.ToTensor(),
    transforms.Normalize(MEAN, STD),
])


# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------
def build_model(num_classes: int) -> nn.Module:
    return timm.create_model("efficientnet_b0", pretrained=True,
                             num_classes=num_classes)

def freeze_backbone(model):
    for name, p in model.named_parameters():
        p.requires_grad = "classifier" in name

def unfreeze_all(model):
    for p in model.parameters():
        p.requires_grad = True


# ---------------------------------------------------------------------------
# Sampler
# ---------------------------------------------------------------------------
def make_sampler(labels):
    counts = Counter(labels)
    weights = [1.0 / counts[l] for l in labels]
    return WeightedRandomSampler(weights, num_samples=len(weights),
                                 replacement=True)


# ---------------------------------------------------------------------------
# Training loop
# ---------------------------------------------------------------------------
DEVICE = get_device()

def run_epoch(model, loader, criterion, optimizer=None):
    training = optimizer is not None
    model.train() if training else model.eval()
    total_loss = correct = total = 0
    ctx = torch.enable_grad() if training else torch.no_grad()
    with ctx:
        for imgs, lbls in loader:
            imgs, lbls = imgs.to(DEVICE), lbls.to(DEVICE)
            if training:
                optimizer.zero_grad()
            out = model(imgs)
            loss = criterion(out, lbls)
            if training:
                loss.backward()
                optimizer.step()
            total_loss += loss.item() * len(lbls)
            correct += (out.argmax(1) == lbls).sum().item()
            total += len(lbls)
    return total_loss / total, correct / total

def collect_preds(model, loader):
    model.eval()
    all_preds, all_labels = [], []
    with torch.no_grad():
        for imgs, lbls in loader:
            preds = model(imgs.to(DEVICE)).argmax(1).cpu()
            all_preds.extend(preds.tolist())
            all_labels.extend(lbls.tolist())
    return all_labels, all_preds


# ---------------------------------------------------------------------------
# Plots
# ---------------------------------------------------------------------------
def plot_history(history, run_dir):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4))
    epochs = range(1, len(history["train_loss"]) + 1)
    ax1.plot(epochs, history["train_loss"], label="train")
    ax1.plot(epochs, history["val_loss"], label="val")
    ax1.axvline(P1_EPOCHS + 0.5, color="grey", linestyle="--",
                alpha=0.5, label="unfreeze")
    ax1.set_title("Loss"); ax1.set_xlabel("Epoch"); ax1.legend()
    ax2.plot(epochs, history["train_acc"], label="train")
    ax2.plot(epochs, history["val_acc"], label="val")
    ax2.axvline(P1_EPOCHS + 0.5, color="grey", linestyle="--",
                alpha=0.5, label="unfreeze")
    ax2.set_title("Accuracy"); ax2.set_xlabel("Epoch"); ax2.legend()
    plt.tight_layout()
    plt.savefig(run_dir / "history.png", dpi=150)
    plt.close()

def plot_confusion(labels, preds, class_names, run_dir):
    cm = confusion_matrix(labels, preds, normalize="true")
    fig, ax = plt.subplots(figsize=(8, 6))
    im = ax.imshow(cm, cmap="Blues", vmin=0, vmax=1)
    plt.colorbar(im, ax=ax)
    ax.set_xticks(range(len(class_names)))
    ax.set_yticks(range(len(class_names)))
    ax.set_xticklabels(class_names, rotation=30, ha="right", fontsize=9)
    ax.set_yticklabels(class_names, fontsize=9)
    for i in range(len(class_names)):
        for j in range(len(class_names)):
            ax.text(j, i, f"{cm[i,j]:.2f}", ha="center", va="center",
                    fontsize=9, color="white" if cm[i, j] > 0.5 else "black")
    ax.set_xlabel("Predicted"); ax.set_ylabel("True")
    ax.set_title("Normalised Confusion Matrix (test set)")
    plt.tight_layout()
    plt.savefig(run_dir / "confusion.png", dpi=150)
    plt.close()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def train(data_dir: Path = COMBINED_DATASET):
    run_dir = RUNS_DIR / f"atlas_ml_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
    run_dir.mkdir(parents=True, exist_ok=True)

    print(f"Device  : {DEVICE}")
    print(f"Data    : {data_dir}")
    print(f"Run dir : {run_dir}\n")

    # ── Load data ────────────────────────────────────────────────────────────
    paths, labels, class_names = load_dataset(data_dir)
    if not paths:
        print("No images found. Run dataset builder first:")
        print("  python -m atlas_ml.dataset")
        return

    num_classes = len(class_names)
    counts = Counter(labels)

    print(f"{num_classes} classes, {len(paths)} total images:")
    for i, name in enumerate(class_names):
        print(f"  {name:25s}: {counts[i]:>6}")

    idx = list(range(len(paths)))
    idx_tr, idx_tmp, y_tr, y_tmp = train_test_split(
        idx, labels, test_size=0.2, stratify=labels, random_state=SEED)
    idx_val, idx_te, y_val, y_te = train_test_split(
        idx_tmp, y_tmp, test_size=0.5, stratify=y_tmp, random_state=SEED)

    print(f"\nSplit — train:{len(idx_tr)}  val:{len(idx_val)}  test:{len(idx_te)}\n")

    train_ds = SpectrogramDataset([paths[i] for i in idx_tr], y_tr, train_tf)
    val_ds   = SpectrogramDataset([paths[i] for i in idx_val], y_val, val_tf)
    test_ds  = SpectrogramDataset([paths[i] for i in idx_te],  y_te,  val_tf)

    train_loader = DataLoader(train_ds, batch_size=BATCH_SIZE,
                              sampler=make_sampler(y_tr), num_workers=NUM_WORKERS)
    val_loader   = DataLoader(val_ds, batch_size=BATCH_SIZE, shuffle=False,
                              num_workers=NUM_WORKERS)
    test_loader  = DataLoader(test_ds, batch_size=BATCH_SIZE, shuffle=False,
                              num_workers=NUM_WORKERS)

    # ── Model ────────────────────────────────────────────────────────────────
    print("Building EfficientNet-B0 (pretrained ImageNet)...")
    model = build_model(num_classes).to(DEVICE)
    criterion = nn.CrossEntropyLoss(label_smoothing=LABEL_SMOOTHING)

    history = {"train_loss": [], "train_acc": [], "val_loss": [], "val_acc": []}
    best_val_acc = 0.0
    t0 = time.time()

    def log(ep, phase, tr_loss, tr_acc, vl_loss, vl_acc):
        star = " ✓" if vl_acc > best_val_acc else ""
        print(f"  [{phase}] ep {ep:>2}  "
              f"train {tr_acc:.3f} (loss {tr_loss:.4f})  "
              f"val {vl_acc:.3f} (loss {vl_loss:.4f})  "
              f"{time.time()-t0:.0f}s{star}")

    # ── Phase 1: frozen backbone ─────────────────────────────────────────────
    print(f"\n── Phase 1: train classifier head ({P1_EPOCHS} epochs) ──")
    freeze_backbone(model)
    opt = torch.optim.AdamW(
        filter(lambda p: p.requires_grad, model.parameters()),
        lr=P1_LR, weight_decay=WEIGHT_DECAY)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=P1_EPOCHS)

    for ep in range(1, P1_EPOCHS + 1):
        tr_loss, tr_acc = run_epoch(model, train_loader, criterion, opt)
        vl_loss, vl_acc = run_epoch(model, val_loader, criterion)
        sched.step()
        log(ep, "P1", tr_loss, tr_acc, vl_loss, vl_acc)
        history["train_loss"].append(tr_loss)
        history["train_acc"].append(tr_acc)
        history["val_loss"].append(vl_loss)
        history["val_acc"].append(vl_acc)
        if vl_acc > best_val_acc:
            best_val_acc = vl_acc
            torch.save(model.state_dict(), run_dir / "best_model.pt")

    # ── Phase 2: full fine-tune ──────────────────────────────────────────────
    print(f"\n── Phase 2: fine-tune all layers ({P2_EPOCHS} epochs) ──")
    unfreeze_all(model)
    classifier_params = [p for n, p in model.named_parameters() if "classifier" in n]
    backbone_params   = [p for n, p in model.named_parameters() if "classifier" not in n]
    opt = torch.optim.AdamW([
        {"params": backbone_params,   "lr": P2_LR_BB},
        {"params": classifier_params, "lr": P2_LR},
    ], weight_decay=WEIGHT_DECAY)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=P2_EPOCHS)

    for ep in range(1, P2_EPOCHS + 1):
        tr_loss, tr_acc = run_epoch(model, train_loader, criterion, opt)
        vl_loss, vl_acc = run_epoch(model, val_loader, criterion)
        sched.step()
        log(ep + P1_EPOCHS, "P2", tr_loss, tr_acc, vl_loss, vl_acc)
        history["train_loss"].append(tr_loss)
        history["train_acc"].append(tr_acc)
        history["val_loss"].append(vl_loss)
        history["val_acc"].append(vl_acc)
        if vl_acc > best_val_acc:
            best_val_acc = vl_acc
            torch.save(model.state_dict(), run_dir / "best_model.pt")

    torch.save(model.state_dict(), run_dir / "final_model.pt")

    # ── Test evaluation ──────────────────────────────────────────────────────
    print(f"\n── Test set (best val acc={best_val_acc:.3f}) ──")
    model.load_state_dict(torch.load(run_dir / "best_model.pt", map_location=DEVICE))
    true_labels, pred_labels = collect_preds(model, test_loader)
    report = classification_report(true_labels, pred_labels,
                                   target_names=class_names, digits=3)
    print(report)
    (run_dir / "results.txt").write_text(report)

    meta = {
        "class_names": class_names,
        "confidence_threshold": CONFIDENCE_THRESHOLD,
        "val_accuracy": best_val_acc,
        "trained_on": str(data_dir),
    }
    (run_dir / "model_meta.json").write_text(json.dumps(meta, indent=2))

    plot_history(history, run_dir)
    plot_confusion(true_labels, pred_labels, class_names, run_dir)
    print(f"\nSaved to: {run_dir}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=COMBINED_DATASET)
    args = parser.parse_args()
    train(data_dir=args.data)
