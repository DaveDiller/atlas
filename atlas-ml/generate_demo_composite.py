"""
Generate a single 2x2 composite reference image for the ATLAS demo.
Shows one labeled spectrogram per class, side by side.

Usage:
  python generate_demo_composite.py
"""

from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.image as mpimg

PREVIEWS = Path(__file__).parent / "data" / "demo_previews"
OUT      = Path(__file__).parent / "data" / "demo_previews" / "atlas_classes_reference.png"

panels = [
    ("seismic_event_1.png",          "Seismic Event\nM4.3 Earthquake — real tectonic waveform"),
    ("seismic_noise_1.png",          "Seismic Noise\nInstrument background — no event"),
    ("mechanical_noise_chainsaw.png", "Mechanical Noise\nChainsaw — proxy for drilling / machinery"),
    ("environmental_noise_rain.png",  "Environmental Noise\nRain — proxy for weather interference"),
]

fig, axes = plt.subplots(2, 2, figsize=(18, 9))
fig.patch.set_facecolor("#1a1a1a")

for ax, (fname, title) in zip(axes.flat, panels):
    img = mpimg.imread(PREVIEWS / fname)
    ax.imshow(img)
    ax.axis("off")
    ax.set_title(title, fontsize=12, fontweight="bold",
                 color="white", pad=8, linespacing=1.4)

plt.suptitle("ATLAS-ML — Classification Reference",
             fontsize=16, fontweight="bold", color="white", y=1.01)
plt.tight_layout()
plt.savefig(OUT, dpi=150, bbox_inches="tight",
            facecolor=fig.get_facecolor())
plt.close()
print(f"Saved: {OUT}")
