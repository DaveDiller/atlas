# atlas_ml — Developer Reference

This package contains the ATLAS-ML training and inference pipeline. For project background, architecture decisions, and user stories see the [parent README](../README.md).

---

## Package Structure

```
atlas-ml/
├── generate_spectrograms.py        # ESC-50 WAV → spectrogram PNG
├── generate_stead_spectrograms.py  # STEAD HDF5 → spectrogram PNG
├── generate_demo_previews.py       # Labeled preview images for the demo
├── generate_demo_composite.py      # 2×2 composite reference image for the demo
├── demo.sh                         # Beat-by-beat interactive demo script
└── atlas_ml/
    ├── config.py     # Class taxonomy, dataset mappings, hyperparameters, paths
    ├── dataset.py    # Assembles combined training dataset
    ├── train.py      # Two-phase EfficientNet-B0 training pipeline
    ├── predict.py    # Inference — single-shot or streaming watch mode (with routing)
    └── review.py     # Human review CLI — confirm / correct / reject pending images
```

---

## Quickstart

```bash
# 1. Generate ESC-50 spectrograms (requires ESC-50 dataset)
python generate_spectrograms.py

# 2. Generate STEAD spectrograms (requires STEAD download via SeisBench)
python generate_stead_spectrograms.py

# 3. Assemble combined dataset
python -m atlas_ml.dataset

# 4. Train
python -m atlas_ml.train

# 5. Classify an image (single-shot)
python -m atlas_ml.predict path/to/spectrogram.png

# 5b. Or watch a directory for incoming images (streaming)
python -m atlas_ml.predict --watch inbox/ --interval 10

# 5c. Watch mode with routing — moves each classified file to pending/<class>/
#     and logs to classification_log.csv
python -m atlas_ml.predict --watch inbox/ --interval 10 --route

# 5d. Review pending images — confirm, correct, or reject one at a time
python -m atlas_ml.review

# 6. Run the interactive demo (after training)
python generate_demo_previews.py   # generates labeled preview images
python generate_demo_composite.py  # generates 2×2 composite reference image
./demo.sh                          # beat-by-beat demo — press ENTER to advance
```

---

## File Reference

### `config.py`

Central configuration — edit this file to change the class taxonomy, dataset mappings, or training hyperparameters. No other files need to change for taxonomy adjustments.

**Key contents:**

| Name | Description |
|------|-------------|
| `CLASSES` | Ordered list of 5 class names — order determines integer label assignment |
| `ESC50_CLASS_MAP` | Maps ESC-50 category names → ATLAS class names. Categories not listed are ignored. |
| `CONFIDENCE_THRESHOLD` | Predictions below this (0.0–1.0) are flagged as uncertain. Default: 0.60 |
| `P1_EPOCHS / P1_LR` | Phase 1 (frozen backbone) epochs and learning rate |
| `P2_EPOCHS / P2_LR / P2_LR_BB` | Phase 2 (full fine-tune) epochs, classifier LR, backbone LR |
| `COMBINED_DATASET` | Path to the assembled training dataset |
| `RUNS_DIR` | Where training run directories are written |

**Current classes:**

| Class | Source | Description |
|-------|--------|-------------|
| `seismic_event` | STEAD earthquakes | Real tectonic waveforms |
| `seismic_noise` | STEAD noise traces | Instrument background noise |
| `mechanical_noise` | ESC-50 | Rotating/reciprocating machinery |
| `environmental_noise` | ESC-50 | Weather and ambient ground coupling |
| `impulsive_noise` | ESC-50 | Sudden transients that mimic seismic arrivals |

---

### `dataset.py`

Assembles the combined training dataset from ESC-50 and STEAD spectrograms by symlinking images into a unified class directory structure.

**Usage:**
```bash
python -m atlas_ml.dataset
```

**Output:** `data/combined_spectrograms/<class_name>/*.png`

**What it does:**
- Reads ESC-50 spectrograms from `data/spectrograms/` and maps them to ATLAS classes via `ESC50_CLASS_MAP`
- Reads STEAD spectrograms from `data/stead_spectrograms/`
- Writes (symlinks) everything into `data/combined_spectrograms/` with one subdirectory per class
- Prints a class balance summary

---

### `train.py`

Two-phase EfficientNet-B0 training pipeline.

**Usage:**
```bash
python -m atlas_ml.train
python -m atlas_ml.train --data path/to/custom/dataset
```

**What it does:**
1. Loads images from `combined_spectrograms/`, splits 80/10/10 (train/val/test), stratified by class
2. **Phase 1** — freezes the EfficientNet backbone, trains only the classifier head
3. **Phase 2** — unfreezes all layers, fine-tunes with a lower learning rate on the backbone
4. Saves the best checkpoint (by val accuracy) throughout training
5. Evaluates the best checkpoint on the held-out test set and prints a per-class report

**Output — a timestamped run directory under `runs/`:**

| File | Contents |
|------|----------|
| `best_model.pt` | Weights from the epoch with highest validation accuracy — use this for inference |
| `final_model.pt` | Weights from the last epoch |
| `model_meta.json` | Class names, confidence threshold, val accuracy, dataset path |
| `history.png` | Training/validation loss and accuracy curves |
| `confusion.png` | Normalised confusion matrix on test set |
| `results.txt` | Per-class precision, recall, F1 on test set |

---

### `predict.py`

Loads `best_model.pt` from a training run and classifies spectrogram PNG images. Supports two modes:

#### Single-shot mode

Classifies a fixed list of images and exits.

```bash
python -m atlas_ml.predict path/to/spectrogram.png
python -m atlas_ml.predict image1.png image2.png image3.png
python -m atlas_ml.predict image.png --model runs/atlas_ml_20260302_142439
python -m atlas_ml.predict image.png --device cpu   # force CPU (avoids GPU memory conflicts)
```

**Output:**
```
Model    : atlas_ml_20260302_142439  (val acc 91.3%)
Device   : mps
Classes  : seismic_event, seismic_noise, mechanical_noise, environmental_noise, impulsive_noise
Threshold: 60%  (below → uncertain)

  bucket1001$529,:3,:6000.png    seismic_event        93.0%
  rain_sample.png                environmental_noise  55.1%  ⚠ uncertain
```

#### Watch mode

Monitors a directory at a regular interval and classifies each new PNG that appears. Files already present when the watcher starts are not re-processed. Press Ctrl+C to stop.

```bash
# Poll every 10 seconds (default)
python -m atlas_ml.predict --watch inbox/

# Custom interval
python -m atlas_ml.predict --watch inbox/ --interval 30

# Specific model run
python -m atlas_ml.predict --watch inbox/ --interval 5 --model runs/atlas_ml_20260302_142439

# Force CPU (useful if GPU memory is shared with other apps)
python -m atlas_ml.predict --watch inbox/ --device cpu

# Routing mode — move each file to pending/<class>/ and log to classification_log.csv
python -m atlas_ml.predict --watch inbox/ --route
```

**Output (without `--route`):**
```
Model    : atlas_ml_20260302_142439  (val acc 91.3%)
...
Watching : inbox/  (every 10s)  — Ctrl+C to stop

[14:03:21]   event_001.png    seismic_event        91.2%
[14:03:31]   noise_042.png    mechanical_noise     87.5%
[14:03:31]   event_002.png    seismic_noise        55.3%  ⚠ uncertain
```

**Output (with `--route`):**
```
Watching : inbox/  (every 10s)  — Ctrl+C to stop  [routing ON → pending/]

[14:03:21]   event_001.png    seismic_event        91.2%
```
Each classified file is moved to `pending/<predicted_class>/` and a row is written to `classification_log.csv`.

Predictions below the confidence threshold are flagged with `⚠ uncertain`. The threshold is read from `model_meta.json` in the run directory (set in `config.py` before training).

**Flags:**

| Flag | Default | Description |
|------|---------|-------------|
| `--model PATH` | latest run | Path to a training run directory |
| `--device cpu\|mps\|cuda` | auto-detect | Force a specific device. Use `--device cpu` if GPU memory conflicts occur (e.g. macOS Preview open while running MPS inference) |
| `--watch DIR` | — | Directory to monitor (watch mode) |
| `--interval SECONDS` | 10 | Poll interval for watch mode |
| `--route` | off | Move classified files to `pending/<class>/` and log to `classification_log.csv` |

---

### `review.py`

Interactive human review CLI. Walks `pending/` one image at a time, opens each image in the system viewer, shows the predicted class and confidence (read from `classification_log.csv`), and prompts for a decision.

**Usage:**
```bash
# Review all images in pending/ (default)
python -m atlas_ml.review

# Custom pending directory
python -m atlas_ml.review --pending path/to/pending/

# Headless — don't open images (useful over SSH or in CI)
python -m atlas_ml.review --no-open
```

**Prompt actions:**

| Key | Action | Destination |
|-----|--------|-------------|
| ENTER | Confirm — prediction is correct | `verified/<predicted_class>/` |
| `c` | Correct — choose the right class from a numbered list | `verified/<chosen_class>/` |
| `r` | Reject — discard (poor quality, ambiguous, unclassifiable) | `rejected/` |
| `q` | Quit — stop early, print summary |  |

**Example session:**
```
Found 3 image(s) to review in pending/

  ENTER=confirm   c=correct   r=reject   q=quit

[1/3]  event_001.png
  Predicted : seismic_event  (99.9%)
  >

[2/3]  mechanical_001.png
  Predicted : mechanical_noise  (100.0%)
  > c
    1  seismic_event
    2  seismic_noise
    3  mechanical_noise
    4  environmental_noise
    5  impulsive_noise
  Class number: 4

[3/3]  noise_001.png
  Predicted : seismic_noise  (99.9%)
  > r

Reviewed 3 image(s):  1 confirmed  1 corrected  1 rejected
Verified pool: environmental_noise=1  seismic_event=1
```

**Directory layout created:**
```
verified/
  seismic_event/
  seismic_noise/
  mechanical_noise/
  environmental_noise/
  impulsive_noise/
rejected/
```

Class names are read from `model_meta.json` in the latest run, falling back to `config.CLASSES`.

---

### `generate_spectrograms.py`  *(top-level, not part of the package)*

Converts ESC-50 WAV files to mel spectrogram PNGs.

**Usage:**
```bash
python generate_spectrograms.py
python generate_spectrograms.py --sample   # 5 labeled preview images per class
```

**Requires:** `data/ESC-50/` (read-only, not committed)
**Output:** `data/spectrograms/<esc50_category>/*.png`

---

### `generate_stead_spectrograms.py`  *(top-level, not part of the package)*

Converts STEAD seismic waveforms to mel spectrogram PNGs using the SeisBench HDF5 file.

**Usage:**
```bash
python generate_stead_spectrograms.py
python generate_stead_spectrograms.py --events 2000 --noise 2000
python generate_stead_spectrograms.py --sample   # 5 labeled preview images per class
python generate_stead_spectrograms.py --check    # verify HDF5 structure without generating
```

**Requires:** STEAD downloaded via SeisBench (`~/.seisbench/datasets/STEAD/`)
**Output:** `data/stead_spectrograms/seismic_event/*.png` and `data/stead_spectrograms/seismic_noise/*.png`

**Note on STEAD download:** SeisBench downloads the pre-processed STEAD HDF5 (~85GB) automatically on first use:
```python
import seisbench.data as sbd
sbd.STEAD()
```
If a download stalls and leaves `.partial` files, use `sbd.STEAD(force=True)` to restart — SeisBench does not support resuming partial downloads.

---

### `generate_demo_previews.py`  *(top-level, not part of the package)*

Generates labeled spectrogram preview images for the demo — annotated PNGs with axes, titles, and frequency scales for human reading (not CNN inference).

**Usage:**
```bash
python generate_demo_previews.py
```

**Requires:** STEAD HDF5 downloaded, ESC-50 spectrograms generated
**Output:** `data/demo_previews/*.png` — 6 images:
- `seismic_event_1.png` / `seismic_event_2.png` — M4.0+ earthquakes from STEAD
- `seismic_noise_1.png` / `seismic_noise_2.png` — instrument background from STEAD
- `mechanical_noise_chainsaw.png` — chainsaw from ESC-50
- `environmental_noise_rain.png` — rain from ESC-50

---

### `generate_demo_composite.py`  *(top-level, not part of the package)*

Generates a single 2×2 composite reference image showing one labeled spectrogram per class. Used in the demo to display all four classes in one window.

**Usage:**
```bash
python generate_demo_composite.py
```

**Requires:** `data/demo_previews/` populated by `generate_demo_previews.py`
**Output:** `data/demo_previews/atlas_classes_reference.png`

---

## Data Sources

Neither dataset is committed to this repo — both must be obtained separately.

| Dataset | Size | How to get it |
|---------|------|---------------|
| ESC-50 | ~600MB | [github.com/karolpiczak/ESC-50](https://github.com/karolpiczak/ESC-50) |
| STEAD (via SeisBench) | ~85GB | `import seisbench.data as sbd; sbd.STEAD()` |

---

## Model Performance (baseline, 2026-03-02)

Trained on 4720 images, EfficientNet-B0, MPS device.

| Class | Precision | Recall | F1 | Support |
|-------|-----------|--------|----|---------|
| seismic_event | 0.917 | 0.935 | 0.926 | 200 |
| seismic_noise | 0.932 | 0.895 | 0.913 | 200 |
| mechanical_noise | 0.929 | 0.812 | 0.867 | 32 |
| environmental_noise | 0.625 | 0.833 | 0.714 | 24 |
| impulsive_noise | 0.750 | 0.750 | 0.750 | 16 |
| **overall** | | | **0.898** | 472 |

`environmental_noise` and `impulsive_noise` are the weakest classes due to limited training images (~160–240 vs. 2000 for seismic classes). More ESC-50 category mappings or additional data sources will improve these.
