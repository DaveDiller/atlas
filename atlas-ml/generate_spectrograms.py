"""
Convert ESC-50 WAV files to mel spectrogram PNG images.

Output structure mirrors the training pipeline format:
  data/ESC-50/spectrograms/{category}/{filename}.png

A mel spectrogram is a 2D image where:
  - X axis = time
  - Y axis = frequency (mel scale — logarithmic, matches human/sensor perception)
  - Pixel colour = signal strength at that frequency at that moment

Usage:
  python generate_spectrograms.py            # convert all 2000 clips
  python generate_spectrograms.py --sample   # generate 5 samples only (preview)
"""

import argparse
from pathlib import Path

import librosa
import librosa.display
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
DATA_DIR  = Path("data/ESC-50")
AUDIO_DIR = DATA_DIR / "audio"
META_CSV  = DATA_DIR / "meta/esc50.csv"
OUT_DIR   = Path("data/spectrograms")

SAMPLE_RATE = 22050   # resample to standard rate
N_MELS      = 128     # frequency bins (height of image)
HOP_LENGTH  = 512     # time resolution
N_FFT       = 2048    # FFT window size
IMG_SIZE    = 224     # output image size (matches CNN input)


# ---------------------------------------------------------------------------
# Single file conversion
# ---------------------------------------------------------------------------
def wav_to_spectrogram(wav_path: Path, out_path: Path) -> None:
    # Load audio and resample
    y, sr = librosa.load(wav_path, sr=SAMPLE_RATE, mono=True)

    # Compute mel spectrogram
    mel = librosa.feature.melspectrogram(
        y=y, sr=sr, n_mels=N_MELS, n_fft=N_FFT, hop_length=HOP_LENGTH
    )
    # Convert to log scale (decibels) — matches how sensors perceive amplitude
    mel_db = librosa.power_to_db(mel, ref=np.max)

    # Render as image — no axes, labels, or whitespace, just the signal
    fig, ax = plt.subplots(figsize=(IMG_SIZE/100, IMG_SIZE/100), dpi=100)
    fig.subplots_adjust(left=0, right=1, bottom=0, top=1)
    librosa.display.specshow(mel_db, sr=sr, hop_length=HOP_LENGTH,
                             x_axis=None, y_axis=None, ax=ax, cmap="magma")
    ax.set_axis_off()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=100, bbox_inches="tight", pad_inches=0)
    plt.close(fig)


def wav_to_spectrogram_labeled(wav_path: Path, out_path: Path, label: str) -> None:
    """Version with axes and title — for human preview only."""
    y, sr = librosa.load(wav_path, sr=SAMPLE_RATE, mono=True)
    mel = librosa.feature.melspectrogram(
        y=y, sr=sr, n_mels=N_MELS, n_fft=N_FFT, hop_length=HOP_LENGTH
    )
    mel_db = librosa.power_to_db(mel, ref=np.max)

    fig, ax = plt.subplots(figsize=(8, 4))
    img = librosa.display.specshow(mel_db, sr=sr, hop_length=HOP_LENGTH,
                                   x_axis="time", y_axis="mel", ax=ax, cmap="magma")
    fig.colorbar(img, ax=ax, format="%+2.0f dB")
    ax.set_title(f"{label}  —  {wav_path.name}", fontsize=11)
    ax.set_xlabel("Time (s)")
    ax.set_ylabel("Frequency (Hz, mel scale)")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=120, bbox_inches="tight")
    plt.close(fig)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main(sample_only: bool = False) -> None:
    df = pd.read_csv(META_CSV)

    if sample_only:
        # Pick one clip from 5 representative categories
        sample_categories = ["chainsaw", "thunderstorm", "engine", "dog", "rain"]
        rows = pd.concat([
            df[df["category"] == cat].iloc[[0]] for cat in sample_categories
        ])
        print(f"Generating {len(rows)} sample spectrograms for preview...\n")
        preview_dir = Path("data/spectrogram_preview")
        for _, row in rows.iterrows():
            wav_path = AUDIO_DIR / row["filename"]
            out_path = preview_dir / f"{row['category']}.png"
            wav_to_spectrogram_labeled(wav_path, out_path, row["category"])
            print(f"  {row['category']:25s} → {out_path}")
        print(f"\nPreview images saved to: {preview_dir.resolve()}")
        return

    # Full conversion
    total = len(df)
    print(f"Converting {total} WAV files to mel spectrograms...")
    print(f"Output: {OUT_DIR.resolve()}\n")

    done = skipped = errors = 0
    for _, row in df.iterrows():
        wav_path = AUDIO_DIR / row["filename"]
        out_path = OUT_DIR / row["category"] / (Path(row["filename"]).stem + ".png")

        if out_path.exists():
            skipped += 1
            continue

        try:
            wav_to_spectrogram(wav_path, out_path)
            done += 1
            if done % 100 == 0:
                print(f"  {done + skipped}/{total}  ({row['category']})")
        except Exception as e:
            print(f"  ERROR {row['filename']}: {e}")
            errors += 1

    print(f"\nDone.  converted={done}  skipped={skipped}  errors={errors}")
    print(f"Output: {OUT_DIR.resolve()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample", action="store_true",
                        help="Generate 5 labeled preview images only")
    args = parser.parse_args()
    main(sample_only=args.sample)
