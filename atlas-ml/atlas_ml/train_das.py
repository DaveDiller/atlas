"""
ATLAS-ML DAS trainer — simple CNN trained from scratch.

Designed for grayscale DAS seismic images (Events / NotEvents).
No pretrained weights — the network learns directly from the data.

Usage:
  python -m atlas_ml.train_das --data /path/to/TrainingData
  python -m atlas_ml.train_das --data /path/to/TrainingData --epochs 80 --img-size 128
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

from .config import RUNS_DIR, SEED, BATCH_SIZE

torch.manual_seed(SEED)
np.random.seed(SEED)


# ---------------------------------------------------------------------------
# Hyperparameters
# ---------------------------------------------------------------------------
IMG_SIZE     = 500   # keep full resolution — Ben: "don't resample to 128, you'll lose all your resolution"
WEIGHT_DECAY = 1e-4
DROPOUT      = 0.5
BATCH_SIZE   = 8     # small batch to accommodate full 500×500 resolution

P1_EPOCHS = 5        # frozen backbone — train classifier head only
P1_LR     = 1e-3
P2_EPOCHS = 60       # full fine-tune
P2_LR     = 3e-4     # classifier head
P2_LR_BB  = 1e-5     # backbone (lower — preserve pretrained features)


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
    """Auto-discover class subdirectories, load grayscale images."""
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


class DasDataset(Dataset):
    def __init__(self, paths, labels, transform=None):
        self.paths = paths
        self.labels = labels
        self.transform = transform

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, idx):
        img = Image.open(self.paths[idx]).convert("L")  # force grayscale
        if self.transform:
            img = self.transform(img)
        return img, self.labels[idx]


# ---------------------------------------------------------------------------
# Transforms — grayscale, seismic-appropriate augmentation
# ---------------------------------------------------------------------------
# Normalize to [-1, 1] — standard for single-channel data
MEAN = [0.5]
STD  = [0.5]

def make_train_transform(img_size):
    return transforms.Compose([
        transforms.Resize((img_size, img_size)),
        transforms.RandomHorizontalFlip(),          # time-axis flip: valid for DAS
        transforms.RandomAffine(degrees=0,          # small time/channel shifts
                                translate=(0.05, 0.05)),
        transforms.RandomCrop(img_size,             # random crop after slight oversize
                              padding=img_size // 16,
                              padding_mode="reflect"),
        transforms.ColorJitter(brightness=0.3,      # simulate amplitude variations
                               contrast=0.3),       # simulate noise floor differences
        transforms.ToTensor(),                      # → [0, 1]
        transforms.Normalize(MEAN, STD),            # → [-1, 1]
        AddGaussianNoise(std=0.02),                 # simulate sensor noise
    ])

def make_val_transform(img_size):
    return transforms.Compose([
        transforms.Resize((img_size, img_size)),
        transforms.ToTensor(),
        transforms.Normalize(MEAN, STD),
    ])


class AddGaussianNoise:
    """Add small Gaussian noise to a tensor — simulates DAS sensor noise."""
    def __init__(self, std=0.02):
        self.std = std

    def __call__(self, tensor):
        return tensor + torch.randn_like(tensor) * self.std


# ---------------------------------------------------------------------------
# Model — residual CNN, no pretrained weights
# ---------------------------------------------------------------------------
class ResBlock(nn.Module):
    """
    Residual block: two convs + skip connection.

    The skip connection (output = F(x) + x) lets gradients flow directly
    through the network, preventing the vanishing/exploding gradient
    problems that plague deep plain CNNs.
    """
    def __init__(self, channels: int, kernel_size: int = 3):
        super().__init__()
        pad = kernel_size // 2
        self.conv1 = nn.Conv2d(channels, channels, kernel_size, padding=pad, bias=False)
        self.bn1   = nn.BatchNorm2d(channels)
        self.conv2 = nn.Conv2d(channels, channels, kernel_size, padding=pad, bias=False)
        self.bn2   = nn.BatchNorm2d(channels)
        self.relu  = nn.ReLU(inplace=True)

    def forward(self, x):
        out = self.relu(self.bn1(self.conv1(x)))
        out = self.bn2(self.conv2(out))
        return self.relu(out + x)          # skip connection


class DasResNet(nn.Module):
    """
    Residual CNN for DAS event classification.

    - Large-kernel stem captures wide seismic arrival patterns (hyperbolas
      span many channels — small kernels miss them entirely)
    - Residual blocks prevent gradient vanishing in deeper layers
    - Progressive channel doubling: 32 → 64 → 128 → 256
    """

    def __init__(self, num_classes: int = 2, dropout: float = DROPOUT,
                 stem_kernel: int = 15):
        super().__init__()

        # Stem — large kernel to capture wide seismic patterns
        self.stem = nn.Sequential(
            nn.Conv2d(1, 32, kernel_size=stem_kernel,
                      padding=stem_kernel // 2, bias=False),
            nn.BatchNorm2d(32),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),               # /2
        )

        # Stage 1 — 32 ch residual, then expand to 64
        self.stage1 = nn.Sequential(
            ResBlock(32, kernel_size=7),
            nn.Conv2d(32, 64, kernel_size=1, bias=False),
            nn.BatchNorm2d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),               # /4
        )

        # Stage 2 — 64 ch residual, then expand to 128
        self.stage2 = nn.Sequential(
            ResBlock(64, kernel_size=5),
            nn.Conv2d(64, 128, kernel_size=1, bias=False),
            nn.BatchNorm2d(128),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),               # /8
        )

        # Stage 3 — 128 ch residual, then expand to 256
        self.stage3 = nn.Sequential(
            ResBlock(128, kernel_size=3),
            nn.Conv2d(128, 256, kernel_size=1, bias=False),
            nn.BatchNorm2d(256),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),               # /16
        )

        self.pool = nn.AdaptiveAvgPool2d(1)   # → 256×1×1

        self.classifier = nn.Sequential(
            nn.Flatten(),
            nn.Linear(256, 128),
            nn.ReLU(inplace=True),
            nn.Dropout(dropout),
            nn.Linear(128, num_classes),
        )

    def forward(self, x):
        x = self.stem(x)
        x = self.stage1(x)
        x = self.stage2(x)
        x = self.stage3(x)
        x = self.pool(x)
        return self.classifier(x)


# Keep DasCNN for loading older saved models
class DasCNN(nn.Module):
    """Original 4-block plain CNN. Kept for compatibility with saved runs."""

    def __init__(self, num_classes: int = 2, dropout: float = DROPOUT):
        super().__init__()

        self.features = nn.Sequential(
            # Block 1 — larger kernel to capture wide seismic arrival patterns
            nn.Conv2d(1, 32, kernel_size=7, padding=3),
            nn.BatchNorm2d(32), nn.ReLU(inplace=True), nn.MaxPool2d(2),
            nn.Conv2d(32, 64, kernel_size=3, padding=1),
            nn.BatchNorm2d(64), nn.ReLU(inplace=True), nn.MaxPool2d(2),
            nn.Conv2d(64, 128, kernel_size=3, padding=1),
            nn.BatchNorm2d(128), nn.ReLU(inplace=True), nn.MaxPool2d(2),
            nn.Conv2d(128, 256, kernel_size=3, padding=1),
            nn.BatchNorm2d(256), nn.ReLU(inplace=True), nn.MaxPool2d(2),
        )
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.classifier = nn.Sequential(
            nn.Flatten(), nn.Linear(256, 128), nn.ReLU(inplace=True),
            nn.Dropout(dropout), nn.Linear(128, num_classes),
        )

    def forward(self, x):
        x = self.features(x)
        x = self.pool(x)
        return self.classifier(x)


def count_parameters(model):
    return sum(p.numel() for p in model.parameters() if p.requires_grad)


def build_model(num_classes: int) -> nn.Module:
    """EfficientNet-B0 pretrained on ImageNet, adapted for single-channel grayscale input.

    timm's in_chans=1 averages the 3-channel stem weights into a single-channel
    equivalent — a better starting point than random init even with the domain gap.
    """
    return timm.create_model("efficientnet_b0", pretrained=True,
                             in_chans=1, num_classes=num_classes)

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
# Mixup
# ---------------------------------------------------------------------------
def mixup_batch(imgs, labels, num_classes, alpha=0.4):
    """Blend pairs of images and produce soft labels — effective on small datasets."""
    lam = np.random.beta(alpha, alpha)
    idx = torch.randperm(imgs.size(0))
    mixed_imgs = lam * imgs + (1 - lam) * imgs[idx]
    # Soft one-hot labels
    y1 = torch.zeros(imgs.size(0), num_classes, device=imgs.device).scatter_(
        1, labels.view(-1, 1), 1)
    y2 = torch.zeros(imgs.size(0), num_classes, device=imgs.device).scatter_(
        1, labels[idx].view(-1, 1), 1)
    mixed_labels = lam * y1 + (1 - lam) * y2
    return mixed_imgs, mixed_labels


# ---------------------------------------------------------------------------
# Training loop
# ---------------------------------------------------------------------------
def run_epoch(model, loader, criterion, device, optimizer=None,
              num_classes=2, use_mixup=False):
    training = optimizer is not None
    model.train() if training else model.eval()
    total_loss = correct = total = 0
    ctx = torch.enable_grad() if training else torch.no_grad()
    with ctx:
        for imgs, lbls in loader:
            imgs, lbls = imgs.to(device), lbls.to(device)
            if training:
                optimizer.zero_grad()
                if use_mixup:
                    imgs, soft_labels = mixup_batch(imgs, lbls, num_classes)
                    out = model(imgs)
                    loss = -(soft_labels * torch.log_softmax(out, dim=1)).sum(dim=1).mean()
                    correct += (out.argmax(1) == lbls).sum().item()
                else:
                    out = model(imgs)
                    loss = criterion(out, lbls)
                    correct += (out.argmax(1) == lbls).sum().item()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=5.0)
                optimizer.step()
            else:
                out = model(imgs)
                loss = criterion(out, lbls)
                correct += (out.argmax(1) == lbls).sum().item()
            total_loss += loss.item() * len(lbls)
            total += len(lbls)
    return total_loss / total, correct / total


def collect_preds(model, loader, device):
    model.eval()
    all_preds, all_labels, all_probs = [], [], []
    with torch.no_grad():
        for imgs, lbls in loader:
            logits = model(imgs.to(device))
            probs = torch.softmax(logits, dim=1)
            preds = logits.argmax(1).cpu()
            all_preds.extend(preds.tolist())
            all_labels.extend(lbls.tolist())
            all_probs.extend(probs.cpu().tolist())
    return all_labels, all_preds, all_probs


# ---------------------------------------------------------------------------
# Plots
# ---------------------------------------------------------------------------
def plot_history(history, run_dir, epochs):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4))
    ep = range(1, epochs + 1)
    ax1.plot(ep, history["train_loss"], label="train")
    ax1.plot(ep, history["val_loss"],   label="val")
    ax1.set_title("Loss"); ax1.set_xlabel("Epoch"); ax1.legend()
    ax2.plot(ep, history["train_acc"], label="train")
    ax2.plot(ep, history["val_acc"],   label="val")
    ax2.set_ylim(0, 1)
    ax2.axhline(0.5, color="grey", linestyle="--", alpha=0.4, label="chance")
    ax2.set_title("Accuracy"); ax2.set_xlabel("Epoch"); ax2.legend()
    plt.tight_layout()
    plt.savefig(run_dir / "history.png", dpi=150)
    plt.close()


def plot_confusion(labels, preds, class_names, run_dir):
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
    ax.set_title("Normalised Confusion Matrix (internal test set)")
    plt.tight_layout()
    plt.savefig(run_dir / "confusion.png", dpi=150)
    plt.close()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def train(data_dir: Path, img_size: int = IMG_SIZE):
    device = get_device()
    run_dir = RUNS_DIR / f"das_cnn_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
    run_dir.mkdir(parents=True, exist_ok=True)

    print(f"Device   : {device}")
    print(f"Data     : {data_dir}")
    print(f"Run dir  : {run_dir}")
    print(f"Img size : {img_size}×{img_size}  P1: {P1_EPOCHS} epochs  P2: {P2_EPOCHS} epochs\n")

    # ── Load data ─────────────────────────────────────────────────────────────
    paths, labels, class_names = load_dataset(data_dir)
    if not paths:
        print("No images found. Check data directory.")
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

    train_tf = make_train_transform(img_size)
    val_tf   = make_val_transform(img_size)

    train_ds = DasDataset([paths[i] for i in idx_tr], y_tr, train_tf)
    val_ds   = DasDataset([paths[i] for i in idx_val], y_val, val_tf)
    test_ds  = DasDataset([paths[i] for i in idx_te],  y_te,  val_tf)

    train_loader = DataLoader(train_ds, batch_size=BATCH_SIZE,
                              sampler=make_sampler(y_tr), num_workers=4)
    val_loader   = DataLoader(val_ds, batch_size=BATCH_SIZE,
                              shuffle=False, num_workers=4)
    test_loader  = DataLoader(test_ds, batch_size=BATCH_SIZE,
                              shuffle=False, num_workers=4)

    # ── Model ─────────────────────────────────────────────────────────────────
    print("Building EfficientNet-B0 (pretrained ImageNet, single-channel)...")
    model = build_model(num_classes).to(device)
    criterion = nn.CrossEntropyLoss()

    history = {"train_loss": [], "train_acc": [], "val_loss": [], "val_acc": []}
    best_val_acc = 0.0
    t0 = time.time()

    def log(ep, phase, tr_loss, tr_acc, vl_loss, vl_acc):
        star = " ✓" if vl_acc > best_val_acc else ""
        print(f"  [{phase}] ep {ep:>2}  "
              f"train {tr_acc:.3f} (loss {tr_loss:.4f})  "
              f"val {vl_acc:.3f} (loss {vl_loss:.4f})  "
              f"{time.time()-t0:.0f}s{star}", flush=True)

    # ── Phase 1: frozen backbone ──────────────────────────────────────────────
    print(f"\n── Phase 1: train classifier head ({P1_EPOCHS} epochs) ──")
    freeze_backbone(model)
    opt = torch.optim.AdamW(
        filter(lambda p: p.requires_grad, model.parameters()),
        lr=P1_LR, weight_decay=WEIGHT_DECAY)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=P1_EPOCHS)

    for ep in range(1, P1_EPOCHS + 1):
        tr_loss, tr_acc = run_epoch(model, train_loader, criterion, device, opt,
                                    num_classes=num_classes, use_mixup=False)
        vl_loss, vl_acc = run_epoch(model, val_loader, criterion, device)
        sched.step()
        log(ep, "P1", tr_loss, tr_acc, vl_loss, vl_acc)
        history["train_loss"].append(tr_loss)
        history["train_acc"].append(tr_acc)
        history["val_loss"].append(vl_loss)
        history["val_acc"].append(vl_acc)
        if vl_acc > best_val_acc:
            best_val_acc = vl_acc
            torch.save(model.state_dict(), run_dir / "best_model.pt")

    # ── Phase 2: full fine-tune ───────────────────────────────────────────────
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
        tr_loss, tr_acc = run_epoch(model, train_loader, criterion, device, opt,
                                    num_classes=num_classes, use_mixup=True)
        vl_loss, vl_acc = run_epoch(model, val_loader, criterion, device)
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

    # ── Test evaluation ────────────────────────────────────────────────────────
    total_epochs = P1_EPOCHS + P2_EPOCHS
    print(f"\n── Internal test set (best val acc={best_val_acc:.3f}) ──")
    model.load_state_dict(torch.load(run_dir / "best_model.pt",
                                     map_location=device))
    true_labels, pred_labels, probs = collect_preds(model, test_loader, device)
    report = classification_report(true_labels, pred_labels,
                                   target_names=class_names, digits=3)
    print(report)
    (run_dir / "results.txt").write_text(report)

    meta = {
        "model": "EfficientNet-B0-pretrained",
        "class_names": class_names,
        "img_size": img_size,
        "in_chans": 1,
        "norm_mean": MEAN,
        "norm_std": STD,
        "confidence_threshold": 0.60,
        "p1_epochs": P1_EPOCHS,
        "p2_epochs": P2_EPOCHS,
        "best_val_accuracy": best_val_acc,
        "trained_on": str(data_dir),
    }
    (run_dir / "model_meta.json").write_text(json.dumps(meta, indent=2))

    plot_history(history, run_dir, total_epochs)
    plot_confusion(true_labels, pred_labels, class_names, run_dir)
    print(f"\nSaved to: {run_dir}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, required=True,
                        help="Directory with Events/ and NotEvents/ subdirs")
    parser.add_argument("--img-size", type=int, default=IMG_SIZE)
    args = parser.parse_args()
    train(data_dir=args.data, img_size=args.img_size)
