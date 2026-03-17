#!/bin/bash
# Run the Atlas ML dashboard.
# Override paths with environment variables if needed:
#   ATLAS_ML_DIR   — path to atlas-ml/ directory (default: directory of this script)
#   ATLAS_RUNS_DIR — path to runs/ directory (default: $ATLAS_ML_DIR/runs)
#   ATLAS_DATA_DIR — default training data path shown in UI
#   PORT           — port to listen on (default: 8765)

cd "$(dirname "$0")"
PORT="${PORT:-8765}"
exec uvicorn dashboard.main:app --host 0.0.0.0 --port "$PORT" --reload
