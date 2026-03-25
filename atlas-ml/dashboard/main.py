# main.py — FastAPI dashboard for atlas-ml training

import asyncio
import json
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request, Form
from fastapi.responses import HTMLResponse, StreamingResponse, JSONResponse, RedirectResponse
from typing import List
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from .data_stats import count_class_images
from .kasa_monitor import KasaMonitor
from .run_manager import TrainingManager, stream_logs
from .run_reader import list_runs

# ---------------------------------------------------------------------------
# Configuration — override with environment variables for different machines
# ---------------------------------------------------------------------------

_HERE = Path(__file__).parent
_ATLAS_ML_DIR = Path(os.environ.get("ATLAS_ML_DIR", _HERE.parent))
_RUNS_DIR = Path(os.environ.get("ATLAS_RUNS", "/data/atlas/runs"))
_DEFAULT_DATA_DIR = os.environ.get("ATLAS_DATA", "/data/atlas/training")
_CONFIG_FILE = _HERE / "dashboard_config.json"

# ---------------------------------------------------------------------------
# App setup
# ---------------------------------------------------------------------------

@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    manager.stop()  # terminate any running training on shutdown


app = FastAPI(title="Atlas ML Dashboard", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=str(_HERE / "static")), name="static")
templates = Jinja2Templates(directory=str(_HERE / "templates"))


# ---------------------------------------------------------------------------
# Config helpers
# ---------------------------------------------------------------------------

def _load_config() -> dict:
    if _CONFIG_FILE.exists():
        try:
            return json.loads(_CONFIG_FILE.read_text())
        except Exception:
            pass
    return {"data_dir": _DEFAULT_DATA_DIR}


def _save_config(data: dict):
    try:
        _CONFIG_FILE.write_text(json.dumps(data, indent=2))
    except Exception:
        pass


manager = TrainingManager(
    atlas_ml_dir=_ATLAS_ML_DIR,
    runs_dir=_RUNS_DIR,
    kasa_plugs=_load_config().get("kasa_plugs", {}),
)


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    cfg = _load_config()
    data_dir = cfg.get("data_dir", _DEFAULT_DATA_DIR)
    runs = await asyncio.to_thread(list_runs, _RUNS_DIR)
    counts = await asyncio.to_thread(count_class_images, Path(data_dir)) if data_dir else {}
    status = manager.status()
    return templates.TemplateResponse(request, "index.html", {
        "runs": runs,
        "counts": counts,
        "data_dir": data_dir,
        "status": status,
        "kasa_plugs": cfg.get("kasa_plugs", {}),
    })


@app.post("/api/kasa/save")
async def kasa_save(
    label: List[str] = Form(default=[]),
    ip: List[str] = Form(default=[]),
):
    plugs = {l.strip(): i.strip() for l, i in zip(label, ip) if l.strip() and i.strip()}
    cfg = _load_config()
    cfg["kasa_plugs"] = plugs
    _save_config(cfg)
    # Update manager with new plugs (takes effect on next training run)
    manager._kasa = KasaMonitor(plugs)
    return RedirectResponse(url="/", status_code=303)


@app.get("/api/data-stats", response_class=HTMLResponse)
async def data_stats(request: Request, path: str = ""):
    if path:
        cfg = _load_config()
        cfg["data_dir"] = path
        _save_config(cfg)
    else:
        path = _load_config().get("data_dir", _DEFAULT_DATA_DIR)
    counts = await asyncio.to_thread(count_class_images, Path(path))
    return templates.TemplateResponse(request, "partials/data_stats.html", {
        "counts": counts,
        "data_dir": path,
    })


@app.get("/api/runs", response_class=HTMLResponse)
async def runs_partial(request: Request):
    runs = await asyncio.to_thread(list_runs, _RUNS_DIR)
    return templates.TemplateResponse(request, "partials/run_table.html", {
        "runs": runs,
    })


@app.post("/api/run/start", response_class=HTMLResponse)
async def run_start(request: Request, data_dir: str = Form(...)):
    data_dir = data_dir.strip()
    if not data_dir:
        return templates.TemplateResponse(request, "partials/training_controls.html", {
            "status": manager.status(),
            "message": "Please enter a data directory path.",
        })
    if not Path(data_dir).exists():
        return templates.TemplateResponse(request, "partials/training_controls.html", {
            "status": manager.status(),
            "message": f"Directory not found: {data_dir}",
        })
    cfg = _load_config()
    cfg["data_dir"] = data_dir
    _save_config(cfg)
    started = manager.start(data_dir)
    status = manager.status()
    return templates.TemplateResponse(request, "partials/training_controls.html", {
        "status": status,
        "message": None if started else "A training run is already in progress.",
    })


@app.get("/api/run/status", response_class=HTMLResponse)
async def run_status(request: Request):
    status = manager.status()
    return templates.TemplateResponse(request, "partials/training_controls.html", {
        "status": status,
        "message": None,
    })


@app.get("/api/run/stop")
async def run_stop():
    manager.stop()
    return JSONResponse({"stopped": True})


@app.get("/api/run/logs/stream")
async def log_stream():
    return StreamingResponse(
        stream_logs(manager),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )
