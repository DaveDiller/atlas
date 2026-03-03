#!/bin/zsh
# =============================================================================
# ATLAS-ML Demo Script
# Run this script, then press ENTER at each prompt to advance to the next beat.
# =============================================================================

ATLAS_DIR="/Users/edwardyoung/dev/src/atlas-git/atlas-ml"
PREVIEWS="$ATLAS_DIR/data/demo_previews"
INBOX="$ATLAS_DIR/demo_inbox"

COMPOSITE="$ATLAS_DIR/data/demo_previews/atlas_classes_reference.png"

BOLD="\033[1m"
DIM="\033[2m"
CYAN="\033[1;36m"
GREEN="\033[1;32m"
YELLOW="\033[1;33m"
RESET="\033[0m"

pause() {
    echo ""
    echo -e "${DIM}─────────────────────────────────────────── press ENTER to continue ──${RESET}"
    read -r
}

banner() {
    echo ""
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
    echo -e "${CYAN}  $1${RESET}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
    echo ""
}

say() {
    echo -e "${YELLOW}▶  $1${RESET}"
}

cd "$ATLAS_DIR" || exit 1

# =============================================================================
clear
banner "ATLAS-ML Demo"
echo -e "  Applied Transforms, Learning & Analysis for Seismology"
echo -e "  CNN-based seismic event classifier"
echo ""
say "This is a beat-by-beat demo. Press ENTER to advance."
pause

# =============================================================================
# BEAT 1 — The problem, in pictures
# =============================================================================
clear
banner "Beat 1 of 5 — The Problem"
say "What does a real seismic event look like versus noise?"
say "Opening class reference image..."
echo ""

open "$COMPOSITE"
sleep 2

echo ""
echo -e "  ${BOLD}Top left   — Seismic Event${RESET}        M4.3 earthquake. Silent, then a sharp low-frequency burst at ~5s."
echo -e "  ${BOLD}Top right  — Seismic Noise${RESET}        Instrument background. Uniform orange smear, no structure."
echo -e "  ${BOLD}Bottom left  — Mechanical Noise${RESET}   Chainsaw (proxy for drilling). Distinct harmonic striping."
echo -e "  ${BOLD}Bottom right — Environmental Noise${RESET} Rain (proxy for weather). Broadband static, no onset."
echo ""
say "A geophysicist can spot these by eye. We're teaching a neural network to do it at scale."
pause

# =============================================================================
# BEAT 2 — What we built
# =============================================================================
clear
banner "Beat 2 of 5 — What We Built"
say "Here's the repo structure:"
echo ""

ls /Users/edwardyoung/dev/src/atlas-git/atlas-ml/

echo ""
say "And the pipeline package:"
echo ""

ls /Users/edwardyoung/dev/src/atlas-git/atlas-ml/atlas_ml/

echo ""
say "Five files. Each does one job:"
echo ""
echo -e "  ${BOLD}config.py${RESET}    — class taxonomy, hyperparameters, all paths in one place"
echo -e "  ${BOLD}dataset.py${RESET}   — assembles training data from ESC-50 + STEAD into 5 classes"
echo -e "  ${BOLD}train.py${RESET}     — two-phase EfficientNet-B0 training pipeline"
echo -e "  ${BOLD}predict.py${RESET}   — single-shot or streaming watch-mode inference"
echo ""
say "Two data sources, combined into one dataset:"
echo ""
echo -e "  STEAD  — 1.2 million real seismic waveforms (Stanford, open access)"
echo -e "  ESC-50 — 2000 labeled environmental audio clips (proxy for field noise)"
pause

# =============================================================================
# BEAT 3 — The results
# =============================================================================
clear
banner "Beat 3 of 5 — Model Results"
say "We trained on 4720 spectrogram images across 5 classes."
say "Here's how it did on the held-out test set:"
echo ""

cat "$ATLAS_DIR/runs/atlas_ml_20260302_142439/results.txt"

echo ""
say "The two classes that matter most in a live deployment:"
echo ""
echo -e "  ${GREEN}seismic_event  F1 = 0.926${RESET}  — catches 93 out of 100 real events"
echo -e "  ${GREEN}seismic_noise  F1 = 0.913${RESET}  — rejects instrument background reliably"
echo ""
say "Opening the training curve..."
echo ""

open -n "$ATLAS_DIR/runs/atlas_ml_20260302_142439/history.png"
sleep 2

echo ""
echo -e "  Reading the graph (right panel — Accuracy):"
echo ""
echo -e "  ${BOLD}Epochs${RESET} (x-axis)"
echo "     One epoch = the model has seen every training image once."
echo "     25 epochs = 25 passes through the full dataset."
echo ""
echo -e "  ${BOLD}Train (blue)${RESET}"
echo "     Accuracy on the images the model learned from."
echo "     Starts low, climbs as the model adjusts."
echo ""
echo -e "  ${BOLD}Val / Validation (orange)${RESET}"
echo "     Accuracy on images held back — the model never saw these during training."
echo "     This is the honest number. If it climbs with train, the model is genuinely learning."
echo "     If train climbs but val doesn't, the model is just memorising."
echo ""
echo -e "  ${BOLD}Unfreeze (dashed grey line at epoch 5)${RESET}"
echo "     The model starts as a pre-trained image classifier (trained on 1.2M photos)."
echo "     Phase 1: only the final classification layer is trained — backbone frozen."
echo "     At epoch 5 we unfreeze the whole network and fine-tune everything."
echo "     The jump in accuracy at that line is the backbone starting to adapt."
echo ""
say "Both curves climb together and plateau near 90% — the model is learning, not memorising."
pause

clear
banner "Beat 3 of 5 — Model Results (Confusion Matrix)"
say "Opening the confusion matrix..."
open -n "$ATLAS_DIR/runs/atlas_ml_20260302_142439/confusion.png"
sleep 2
echo ""
echo -e "  Reading the confusion matrix:"
echo ""
echo "     Each row = what the signal actually was."
echo "     Each column = what the model predicted."
echo "     Diagonal = correct. Off-diagonal = mistakes."
echo "     Values are proportions (1.0 = perfect, 0 = never correct)."
echo ""
say "The two seismic classes are nearly perfect. Noise classes are weaker — more data will fix that."
pause

# =============================================================================
# BEAT 4 — Live inference
# =============================================================================
clear
banner "Beat 4 of 5 — Live Inference"

# CNN-ready training images (clean, no axes/borders — what the model actually sees)
EVENT_IMG="data/stead_spectrograms/seismic_event/bucket629\$530,:3,:6000.png"
NOISE_IMG="data/stead_spectrograms/seismic_noise/bucket2\$915,:3,:6000.png"
MECH_IMG="data/spectrograms/chainsaw/1-19898-C-41.png"
ENV_IMG="data/spectrograms/rain/4-180380-A-10.png"

# Labeled preview images (same signals, annotated for human reading)
EVENT_PREVIEW="$PREVIEWS/seismic_event_1.png"
NOISE_PREVIEW="$PREVIEWS/seismic_noise_1.png"
MECH_PREVIEW="$PREVIEWS/mechanical_noise_chainsaw.png"
ENV_PREVIEW="$PREVIEWS/environmental_noise_rain.png"

say "Single-shot mode — one image at a time."
say "The labeled image opens so you can see what the model is looking at."
say "The model sees the same signal as a clean 224×224 pixel grid."
echo ""

# --- Image 1 ---
say "Image 1: top-left in the reference — M4.3 Earthquake"
python -m atlas_ml.predict --device cpu "$EVENT_IMG"
pause

# --- Image 2 ---
say "Image 2: top-right — Instrument Background"
python -m atlas_ml.predict --device cpu "$NOISE_IMG"
pause

# --- Image 3 ---
say "Image 3: bottom-left — Chainsaw (mechanical noise)"
python -m atlas_ml.predict --device cpu "$MECH_IMG"
pause

# --- Image 4 ---
say "Image 4: bottom-right — Rain (environmental noise)"
python -m atlas_ml.predict --device cpu "$ENV_IMG"
pause

# --- Watch mode ---
clear
banner "Beat 4 of 5 — Live Inference (Watch Mode)"
say "Now watch mode — the model monitors a directory and classifies new images as they arrive."
say "This is how it would run in a live deployment wired to a signal feed."
echo ""
say "Starting watcher on demo_inbox/ ..."
echo ""

mkdir -p "$INBOX"
rm -f "$INBOX"/*.png

python -m atlas_ml.predict --watch "$INBOX" --interval 3 --device cpu &
WATCH_PID=$!
sleep 2

say "Dropping a seismic event into the inbox... (top-left in the reference)"
sleep 2
cp "$EVENT_IMG" "$INBOX/event_001.png"

sleep 5
say "Dropping instrument background noise... (top-right)"
sleep 2
cp "$NOISE_IMG" "$INBOX/noise_001.png"

sleep 5
say "Dropping mechanical noise (chainsaw)... (bottom-left)"
sleep 2
cp "$MECH_IMG" "$INBOX/mechanical_001.png"

sleep 5
say "Stopping watcher."
kill $WATCH_PID 2>/dev/null
wait $WATCH_PID 2>/dev/null
echo ""
say "In a real deployment this watcher points at whatever folder"
say "your signal processing pipeline drops spectrograms into."
pause

# =============================================================================
# BEAT 5 — What's next
# =============================================================================
clear
banner "Beat 5 of 5 — What's Next"
echo ""
echo -e "  ${BOLD}What's working:${RESET}"
echo "    ✓  End-to-end pipeline from raw waveform → classification"
echo "    ✓  89.8% test accuracy on real STEAD seismic data"
echo "    ✓  Streaming watch mode ready to wire into a live signal feed"
echo "    ✓  Confidence thresholding — uncertain predictions are flagged, not guessed"
echo ""
echo -e "  ${BOLD}What needs work:${RESET}"
echo "    ⚠  Environmental and impulsive noise classes are underrepresented"
echo "       → Need real field noise recordings, not ESC-50 audio proxies"
echo "    ⚠  ESC-50 is recorded at 22kHz; STEAD at 100Hz — different frequency worlds"
echo "       → Model generalises but site-specific noise data would be much stronger"
echo "    ⚠  No human-in-the-loop review workflow yet (retrain on confirmed labels)"
echo ""
echo -e "  ${BOLD}Open questions for Dave:${RESET}"
echo "    ?  What does the actual signal source look like in the field?"
echo "    ?  What format does it produce — miniSEED, SAC, HDF5, something else?"
echo "    ?  Is there any labeled field noise data we can train on directly?"
echo "    ?  What's the acceptable false positive rate in a live deployment?"
echo ""
say "The architecture is built to grow. New classes = new folder + retrain."
echo ""

banner "Demo Complete"
echo -e "  Repo:  https://github.com/DaveDiller/atlas"
echo -e "  Model: runs/atlas_ml_20260302_142439/best_model.pt"
echo ""
