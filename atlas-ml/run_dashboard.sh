#!/bin/bash
# Run the Atlas ML dashboard.
# Override paths with environment variables if needed:
#   ATLAS_ML_DIR   — path to atlas-ml/ directory (default: directory of this script)
#   ATLAS_RUNS — path to runs/ directory (default: $ATLAS_ML_DIR/runs)
#   ATLAS_DATA — default training data path shown in UI
#   PORT           — port to listen on (default: 8765)

cd "$(dirname "$0")"
PORT="${PORT:-8765}"
ATLAS_RUNS="${ATLAS_RUNS:-/data/atlas/runs}"
ATLAS_DATA="${ATLAS_DATA:-/data/atlas/training}"
export ATLAS_RUNS ATLAS_DATA
exec uvicorn dashboard.main:app --host 0.0.0.0 --port "$PORT" --reload
