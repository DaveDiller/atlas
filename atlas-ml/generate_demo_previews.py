"""
Generate labeled spectrogram preview images for the ATLAS demo.

Produces 6 annotated PNGs in data/demo_previews/:
  - 2 seismic events (real earthquakes from STEAD)
  - 2 seismic noise traces (instrument background from STEAD)
  - 1 mechanical noise (chainsaw from ESC-50)
  - 1 environmental noise (rain from ESC-50)

Images are human-readable with axes, titles, and frequency scales —
not the clean CNN-training images.

Usage:
  python generate_demo_previews.py
"""

from pathlib import Path

import h5py
import librosa
import librosa.display
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from PIL import Image

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
ROOT_DIR   = Path(__file__).parent
OUT_DIR    = ROOT_DIR / "data" / "demo_previews"
SEISBENCH  = Path.home() / ".seisbench" / "datasets" / "STEAD"
ESC50_SPECS = ROOT_DIR / "data" / "spectrograms"

SR         = 100     # STEAD sample rate (Hz)
N_MELS     = 128
N_FFT      = 256
HOP_LENGTH = 10
FMIN       = 1.0
FMAX       = 45.0
N_SAMPLES  = SR * 30  # 30-second window

ESC50_SR   = 22050
ESC50_NFFT = 2048
ESC50_HOP  = 512


def save_labeled(mel_db, sr, hop_length, fmin, fmax, title, out_path):
    fig, ax = plt.subplots(figsize=(10, 4))
    img = librosa.display.specshow(
        mel_db, sr=sr, hop_length=hop_length,
        x_axis="time", y_axis="mel",
        fmin=fmin, fmax=fmax,
        ax=ax, cmap="magma",
    )
    plt.colorbar(img, ax=ax, format="%+2.0f dB")
    ax.set_title(title, fontsize=13, fontweight="bold")
    ax.set_xlabel("Time (s)")
    ax.set_ylabel("Frequency (Hz)")
    plt.tight_layout()
    plt.savefig(out_path, dpi=150)
    plt.close()
    print(f"  Saved: {out_path.name}")


def make_stead_preview(trace_name, title, out_path):
    bucket_key, rest = trace_name.split("$")
    row_idx = int(rest.split(",")[0])
    with h5py.File(SEISBENCH / "waveforms.hdf5", "r") as hf:
        raw = hf["data"][bucket_key][row_idx]  # (3, 6000)
    waveform = raw[2].astype(np.float64)        # Z channel
    waveform = waveform[:N_SAMPLES]
    std = waveform.std()
    if std > 1e-10:
        waveform = (waveform - waveform.mean()) / std
    mel = librosa.feature.melspectrogram(
        y=waveform.astype(np.float32), sr=SR,
        n_fft=N_FFT, hop_length=HOP_LENGTH, n_mels=N_MELS,
        fmin=FMIN, fmax=FMAX,
    )
    save_labeled(librosa.power_to_db(mel, ref=np.max),
                 SR, HOP_LENGTH, FMIN, FMAX, title, out_path)


def make_esc50_preview(png_path, original_wav_class, atlas_class, title, out_path):
    """Re-render an ESC-50 spectrogram PNG with labeled axes."""
    # Load the pre-generated spectrogram image and convert back to array for display
    img = np.array(Image.open(png_path).convert("L")).astype(np.float32)
    # Treat pixel values as a proxy dB map (already log-scaled)
    mel_db = img / 255.0 * -80.0  # rough rescale to dB range

    fig, ax = plt.subplots(figsize=(10, 4))
    librosa.display.specshow(
        mel_db, sr=ESC50_SR, hop_length=ESC50_HOP,
        x_axis="time", y_axis="mel",
        ax=ax, cmap="magma",
    )
    ax.set_title(title, fontsize=13, fontweight="bold")
    ax.set_xlabel("Time (s)")
    ax.set_ylabel("Frequency (Hz)")
    plt.tight_layout()
    plt.savefig(out_path, dpi=150)
    plt.close()
    print(f"  Saved: {out_path.name}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Generating demo previews → {OUT_DIR}\n")

    # Load metadata to pick good earthquake examples with known magnitude
    print("Loading STEAD metadata...")
    meta = pd.read_csv(SEISBENCH / "metadata.csv", low_memory=False)
    meta["source_magnitude"] = pd.to_numeric(meta["source_magnitude"], errors="coerce")
    eq    = meta[meta["trace_category"] == "earthquake_local"]
    noise = meta[meta["trace_category"] == "noise"]

    # Pick 2 earthquakes with magnitude >= 4.0 for a clear signal
    eq_picks = eq[eq["source_magnitude"] >= 4.0].head(2)
    noise_picks = noise.head(2)

    print("\nSeismic events:")
    for i, (_, row) in enumerate(eq_picks.iterrows()):
        mag = row["source_magnitude"]
        title = f"Seismic Event — M{mag:.1f} Earthquake (STEAD)\n{row['trace_name']}"
        make_stead_preview(
            row["trace_name"], title,
            OUT_DIR / f"seismic_event_{i+1}.png"
        )

    print("\nSeismic noise:")
    for i, (_, row) in enumerate(noise_picks.iterrows()):
        title = f"Seismic Noise — Instrument Background (STEAD)\n{row['trace_name']}"
        make_stead_preview(
            row["trace_name"], title,
            OUT_DIR / f"seismic_noise_{i+1}.png"
        )

    print("\nMechanical noise (chainsaw → ESC-50):")
    chainsaw_png = next((ESC50_SPECS / "chainsaw").glob("*.png"), None)
    if chainsaw_png:
        make_esc50_preview(
            chainsaw_png, "chainsaw", "mechanical_noise",
            "Mechanical Noise — Chainsaw (ESC-50 proxy for drilling/machinery)",
            OUT_DIR / "mechanical_noise_chainsaw.png"
        )
    else:
        print("  No chainsaw spectrograms found — skipping")

    print("\nEnvironmental noise (rain → ESC-50):")
    rain_png = next((ESC50_SPECS / "rain").glob("*.png"), None)
    if rain_png:
        make_esc50_preview(
            rain_png, "rain", "environmental_noise",
            "Environmental Noise — Rain (ESC-50 proxy for weather interference)",
            OUT_DIR / "environmental_noise_rain.png"
        )
    else:
        print("  No rain spectrograms found — skipping")

    print(f"\nDone. {len(list(OUT_DIR.glob('*.png')))} preview images in {OUT_DIR}")


if __name__ == "__main__":
    main()
