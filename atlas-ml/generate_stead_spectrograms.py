"""
Generate mel spectrogram PNGs from STEAD seismic waveforms.

Reads STEAD earthquake and noise traces directly from the SeisBench HDF5 file,
converts each trace to a mel spectrogram image, and writes PNGs to:
  data/stead_spectrograms/seismic_event/
  data/stead_spectrograms/seismic_noise/

The parameters intentionally use seismic-appropriate values (100 Hz native rate,
low fmax) so the spectrogram captures the meaningful 1–45 Hz seismic band.
Images are the same size (224×224) and colormap (magma) as ESC-50 spectrograms
so the CNN sees consistent input regardless of data source.

Usage:
  python generate_stead_spectrograms.py
  python generate_stead_spectrograms.py --events 2000 --noise 2000
  python generate_stead_spectrograms.py --sample   # 5 labeled previews per class
  python generate_stead_spectrograms.py --check    # verify HDF5 structure without generating
"""

import argparse
import random
import sys
from pathlib import Path

import h5py
import librosa
import librosa.display
import matplotlib
matplotlib.use("Agg")  # non-interactive backend — no display needed
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from tqdm import tqdm

# ---------------------------------------------------------------------------
# Paths — must match atlas_ml/config.py
# ---------------------------------------------------------------------------
ROOT_DIR         = Path(__file__).parent
DATA_DIR         = ROOT_DIR / "data"
STEAD_SPECTROGRAMS = DATA_DIR / "stead_spectrograms"

SEISBENCH_STEAD  = Path.home() / ".seisbench" / "datasets" / "STEAD"
METADATA_CSV     = SEISBENCH_STEAD / "metadata.csv"
WAVEFORMS_HDF5   = SEISBENCH_STEAD / "waveforms.hdf5"

# ---------------------------------------------------------------------------
# Parameters
# ---------------------------------------------------------------------------
SEED              = 42
SR                = 100      # Hz — STEAD native sample rate
WINDOW_SECONDS    = 30       # seconds of waveform to use per trace
N_MELS            = 128      # mel frequency bins
N_FFT             = 256      # FFT window — appropriate for 100 Hz data
HOP_LENGTH        = 10       # 0.1 s per time frame → 300 frames per 30s trace
FMIN              = 1.0      # Hz — ignore DC / infra-low frequencies
FMAX              = 45.0     # Hz — just below Nyquist (50 Hz) to avoid aliasing
IMG_SIZE          = 224      # output image pixels (square)

# Default sample counts (overridden by --events / --noise args)
DEFAULT_EVENTS    = 2000
DEFAULT_NOISE     = 2000

# STEAD HDF5 / CSV column conventions
TRACE_NAME_COL    = "trace_name"
CATEGORY_COL      = "trace_category"
EARTHQUAKE_LABEL  = "earthquake_local"
NOISE_LABEL       = "noise"

# Channel order in STEAD HDF5: (3, n_samples) — East, North, Vertical(Z)
Z_CHANNEL_IDX     = 2


# ---------------------------------------------------------------------------
# Spectrogram helpers
# ---------------------------------------------------------------------------

def _normalize(waveform: np.ndarray) -> np.ndarray:
    """Zero-mean, unit-variance normalisation with stability guard."""
    std = waveform.std()
    if std < 1e-10:
        return waveform - waveform.mean()
    return (waveform - waveform.mean()) / std


def _trim_or_pad(waveform: np.ndarray, n_samples: int) -> np.ndarray:
    """Trim long traces to n_samples; zero-pad short ones."""
    if len(waveform) >= n_samples:
        return waveform[:n_samples]
    return np.pad(waveform, (0, n_samples - len(waveform)))


def make_spectrogram_png(waveform: np.ndarray, out_path: Path,
                         labeled: bool = False) -> None:
    """
    Convert a 1-D waveform array to a mel spectrogram PNG.

    labeled=False → clean 224×224 image for CNN training (no axes).
    labeled=True  → annotated image for human visual inspection.
    """
    waveform = _normalize(waveform)

    mel = librosa.feature.melspectrogram(
        y=waveform.astype(np.float32),
        sr=SR,
        n_fft=N_FFT,
        hop_length=HOP_LENGTH,
        n_mels=N_MELS,
        fmin=FMIN,
        fmax=FMAX,
    )
    mel_db = librosa.power_to_db(mel, ref=np.max)

    if labeled:
        fig, ax = plt.subplots(figsize=(9, 4))
        img = librosa.display.specshow(
            mel_db, sr=SR, hop_length=HOP_LENGTH,
            x_axis="time", y_axis="mel",
            fmin=FMIN, fmax=FMAX,
            ax=ax, cmap="magma",
        )
        plt.colorbar(img, ax=ax, format="%+2.0f dB")
        ax.set_title(out_path.stem)
        ax.set_xlabel("Time (s)")
        ax.set_ylabel("Frequency (Hz)")
        plt.tight_layout()
        plt.savefig(out_path, dpi=150)
    else:
        # Exact pixel dimensions — no borders, no axes
        fig = plt.figure(figsize=(IMG_SIZE / 100, IMG_SIZE / 100), dpi=100)
        ax = fig.add_axes([0, 0, 1, 1])
        ax.imshow(mel_db, aspect="auto", origin="lower",
                  cmap="magma", interpolation="nearest")
        ax.axis("off")
        plt.savefig(out_path, dpi=100, bbox_inches="tight", pad_inches=0)

    plt.close()


# ---------------------------------------------------------------------------
# Main generation function
# ---------------------------------------------------------------------------

def generate(n_events: int, n_noise: int, labeled: bool = False) -> None:
    """Read STEAD HDF5 and write mel spectrogram PNGs for both classes."""
    _check_files_exist()

    print(f"Loading STEAD metadata …")
    meta = pd.read_csv(METADATA_CSV, low_memory=False)
    print(f"  Total traces      : {len(meta):,}")

    eq_meta    = meta[meta[CATEGORY_COL] == EARTHQUAKE_LABEL].reset_index(drop=True)
    noise_meta = meta[meta[CATEGORY_COL] == NOISE_LABEL].reset_index(drop=True)
    print(f"  Earthquake traces : {len(eq_meta):,}")
    print(f"  Noise traces      : {len(noise_meta):,}\n")

    n_window = SR * WINDOW_SECONDS   # samples per trace

    jobs = [
        ("seismic_event", eq_meta,    n_events),
        ("seismic_noise", noise_meta, n_noise),
    ]

    with h5py.File(WAVEFORMS_HDF5, "r") as hf:
        for class_name, df, n_requested in jobs:
            out_dir = STEAD_SPECTROGRAMS / class_name
            out_dir.mkdir(parents=True, exist_ok=True)

            # Check how many already exist
            existing = len(list(out_dir.glob("*.png")))
            still_needed = n_requested - existing
            if still_needed <= 0:
                print(f"{class_name}: {existing} images already exist — skipping.")
                continue

            # Sample from rows not yet converted
            all_names = set(df[TRACE_NAME_COL].tolist())
            done_names = {p.stem for p in out_dir.glob("*.png")}
            remaining = df[~df[TRACE_NAME_COL].isin(done_names)].reset_index(drop=True)

            n_to_do = min(still_needed, len(remaining))
            df_todo = remaining.sample(n=n_to_do, random_state=SEED)

            print(f"{class_name}: {existing} done, generating {n_to_do} more "
                  f"(target {n_requested})")

            ok = skipped = 0
            for _, row in tqdm(df_todo.iterrows(), total=len(df_todo),
                               desc=class_name, unit="img"):
                trace_name = row[TRACE_NAME_COL]
                out_path   = out_dir / f"{trace_name}.png"

                try:
                    # Trace name format: "bucketN$row_idx,:3,:6000"
                    bucket_key, rest = trace_name.split("$")
                    row_idx = int(rest.split(",")[0])
                    raw = hf["data"][bucket_key][row_idx]  # shape: (3, 6000)

                    # Extract Z (vertical) channel
                    if raw.ndim == 2:
                        waveform = raw[Z_CHANNEL_IDX].astype(np.float64)
                    else:
                        waveform = raw.astype(np.float64)

                    waveform = _trim_or_pad(waveform, n_window)
                    make_spectrogram_png(waveform, out_path, labeled=labeled)
                    ok += 1

                except KeyError:
                    # Trace listed in metadata but absent from HDF5 (can happen)
                    skipped += 1
                    if skipped <= 3:
                        print(f"\n  Missing in HDF5: {trace_name}")
                except Exception as exc:
                    skipped += 1
                    if skipped <= 3:
                        print(f"\n  Error {trace_name}: {exc}")

            total = existing + ok
            print(f"  Saved {ok} new images ({skipped} skipped) — "
                  f"{total} total in {out_dir}\n")


# ---------------------------------------------------------------------------
# Diagnostic / utility
# ---------------------------------------------------------------------------

def check_structure() -> None:
    """Print HDF5 structure and a few trace names so you can verify layout."""
    _check_files_exist()

    print(f"HDF5 file: {WAVEFORMS_HDF5}")
    with h5py.File(WAVEFORMS_HDF5, "r") as hf:
        print(f"Top-level keys: {list(hf.keys())}")
        data_grp = hf["data"]
        trace_names = list(data_grp.keys())
        print(f"Number of traces in HDF5: {len(trace_names):,}")
        print(f"\nFirst 5 trace names:")
        for name in trace_names[:5]:
            ds = data_grp[name]
            print(f"  {name!r:50s}  shape={ds.shape}  dtype={ds.dtype}")

    print(f"\nMetadata CSV: {METADATA_CSV}")
    meta = pd.read_csv(METADATA_CSV, nrows=3, low_memory=False)
    print(f"Columns: {list(meta.columns)}")
    print(meta.head(3).to_string())


def _check_files_exist() -> None:
    missing = []
    if not METADATA_CSV.exists():
        missing.append(str(METADATA_CSV))
    if not WAVEFORMS_HDF5.exists():
        missing.append(str(WAVEFORMS_HDF5))
    if missing:
        print("ERROR: STEAD files not found:")
        for f in missing:
            print(f"  {f}")
        partial = list(SEISBENCH_STEAD.glob("*.partial"))
        if partial:
            print("\nPartial files detected — download still in progress:")
            for p in partial:
                size_gb = p.stat().st_size / 1e9
                print(f"  {p.name}  ({size_gb:.1f} GB)")
        sys.exit(1)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Generate mel spectrograms from STEAD seismic waveforms")
    parser.add_argument(
        "--events", type=int, default=DEFAULT_EVENTS,
        help=f"Number of earthquake traces to convert (default: {DEFAULT_EVENTS})")
    parser.add_argument(
        "--noise", type=int, default=DEFAULT_NOISE,
        help=f"Number of noise traces to convert (default: {DEFAULT_NOISE})")
    parser.add_argument(
        "--sample", action="store_true",
        help="Generate 5 labeled preview images per class (for visual inspection)")
    parser.add_argument(
        "--check", action="store_true",
        help="Print HDF5 structure and exit — useful for verifying download")
    args = parser.parse_args()

    if args.check:
        check_structure()
    elif args.sample:
        generate(n_events=5, n_noise=5, labeled=True)
        print(f"\nPreview images written to {STEAD_SPECTROGRAMS}/")
    else:
        generate(n_events=args.events, n_noise=args.noise, labeled=False)
        print("Done. Next step:")
        print("  python -m atlas_ml.dataset   # build combined dataset")
        print("  python -m atlas_ml.train     # train the model")
