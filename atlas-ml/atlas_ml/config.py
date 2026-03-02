"""
ATLAS-ML configuration — class taxonomy and dataset mappings.

Edit this file to add/remove categories or remap classes.
The pipeline reads from here, so no code changes needed for taxonomy adjustments.
"""

# ---------------------------------------------------------------------------
# Class taxonomy
# ---------------------------------------------------------------------------
# These are the 5 classes the CNN will learn to distinguish.
# Order here determines the integer label assigned to each class.
CLASSES = [
    "seismic_event",       # 0 — real seismic events (from STEAD earthquakes)
    "seismic_noise",       # 1 — instrument background noise (from STEAD noise traces)
    "mechanical_noise",    # 2 — rotating/reciprocating machinery (from ESC-50)
    "environmental_noise", # 3 — weather and ambient ground coupling (from ESC-50)
    "impulsive_noise",     # 4 — sudden events that mimic seismic arrivals (from ESC-50)
]

# ---------------------------------------------------------------------------
# ESC-50 category → ATLAS class mapping
# Categories not listed here are ignored (not deleted from disk).
# ---------------------------------------------------------------------------
ESC50_CLASS_MAP = {
    # mechanical_noise — rotating/reciprocating machinery and vehicles
    "chainsaw":        "mechanical_noise",
    "engine":          "mechanical_noise",
    "helicopter":      "mechanical_noise",
    "hand_saw":        "mechanical_noise",
    "train":           "mechanical_noise",
    "airplane":        "mechanical_noise",
    "vacuum_cleaner":  "mechanical_noise",
    "washing_machine": "mechanical_noise",

    # environmental_noise — weather and ambient sources that couple into ground
    "rain":            "environmental_noise",
    "thunderstorm":    "environmental_noise",
    "wind":            "environmental_noise",
    "sea_waves":       "environmental_noise",
    "water_drops":     "environmental_noise",
    "footsteps":       "environmental_noise",

    # impulsive_noise — sudden transient events that can mimic seismic arrivals
    "fireworks":       "impulsive_noise",
    "glass_breaking":  "impulsive_noise",
    "clock_alarm":     "impulsive_noise",
    "crackling_fire":  "impulsive_noise",
}

# ---------------------------------------------------------------------------
# STEAD parameters
# ---------------------------------------------------------------------------
STEAD_SAMPLES_PER_CLASS = 2000   # earthquake traces to use
STEAD_NOISE_SAMPLES     = 2000   # noise traces to use
STEAD_SAMPLE_RATE       = 100    # Hz — STEAD native sample rate
STEAD_WINDOW_SECONDS    = 30     # seconds of waveform to use per trace

# ---------------------------------------------------------------------------
# Spectrogram parameters (must match generate_spectrograms.py)
# ---------------------------------------------------------------------------
SAMPLE_RATE = 22050
N_MELS      = 128
HOP_LENGTH  = 512
N_FFT       = 2048
IMG_SIZE    = 224

# ---------------------------------------------------------------------------
# Training parameters
# ---------------------------------------------------------------------------
BATCH_SIZE      = 32
NUM_WORKERS     = 4
SEED            = 42
LABEL_SMOOTHING = 0.1
WEIGHT_DECAY    = 1e-4

P1_EPOCHS   = 5       # frozen backbone
P1_LR       = 1e-3

P2_EPOCHS   = 20      # full fine-tune
P2_LR       = 1e-4    # classifier head
P2_LR_BB    = 1e-5    # backbone (lower — preserve pretrained weights)

# Confidence threshold — predictions below this are flagged as "uncertain"
CONFIDENCE_THRESHOLD = 0.60

# Retraining trigger — retrain after this many new verified images
RETRAIN_THRESHOLD = 50

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
from pathlib import Path

ROOT_DIR             = Path(__file__).parent.parent
DATA_DIR             = ROOT_DIR / "data"
ESC50_SPECTROGRAMS   = DATA_DIR / "spectrograms"
STEAD_SPECTROGRAMS   = DATA_DIR / "stead_spectrograms"
COMBINED_DATASET     = DATA_DIR / "combined_spectrograms"
RUNS_DIR             = ROOT_DIR / "runs"
INFERENCE_INBOX      = ROOT_DIR / "inbox"      # drop new images here
INFERENCE_PENDING    = ROOT_DIR / "pending"    # awaiting human review
INFERENCE_VERIFIED   = ROOT_DIR / "verified"   # reviewed and confirmed
INFERENCE_LOG        = ROOT_DIR / "classification_log.csv"
