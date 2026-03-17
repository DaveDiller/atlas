# run_manager.py — manage training subprocess and stream logs via SSE

import asyncio
import json
import shutil
import subprocess
import sys
import threading
import time
from collections import deque
from pathlib import Path
from typing import Optional

from .kasa_monitor import KasaMonitor

# Sample GPU power every N seconds during training
_POWER_SAMPLE_INTERVAL = 5.0


def _sample_gpu_power_watts() -> Optional[float]:
    """Query instantaneous GPU power draw via nvidia-smi. Returns None if unavailable."""
    if not shutil.which("nvidia-smi"):
        return None
    try:
        result = subprocess.run(
            ["nvidia-smi", "--query-gpu=power.draw", "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=3,
        )
        if result.returncode == 0:
            return float(result.stdout.strip().split("\n")[0])
    except Exception:
        pass
    return None


class TrainingManager:

    def __init__(self, atlas_ml_dir: Path, runs_dir: Path,
                 kasa_plugs: Optional[dict] = None):
        self._atlas_ml_dir = atlas_ml_dir
        self._runs_dir = runs_dir
        self._process: Optional[subprocess.Popen] = None
        self._log_buffer: deque[str] = deque(maxlen=2000)
        self._reader_thread: Optional[threading.Thread] = None
        self._power_thread: Optional[threading.Thread] = None
        self._start_time: Optional[float] = None
        self._end_time: Optional[float] = None
        self._data_dir: Optional[str] = None
        self._last_run_name: Optional[str] = None
        self._terminated: bool = False
        self._power_samples: list[float] = []   # GPU watts, sampled every interval
        self._kasa = KasaMonitor(kasa_plugs or {})
        self._lock = threading.Lock()

    # -------------------------------------------------------------------------
    # Public API
    # -------------------------------------------------------------------------

    def start(self, data_dir: str) -> bool:
        """Start a training run. Returns False if one is already running."""
        with self._lock:
            if self.is_running():
                return False

            self._log_buffer.clear()
            self._start_time = time.time()
            self._end_time = None
            self._data_dir = data_dir
            self._last_run_name = None
            self._terminated = False
            self._power_samples = []

            cmd = [
                sys.executable, "-m", "atlas_ml.train_das",
                "--data", data_dir,
            ]
            self._process = subprocess.Popen(
                cmd,
                cwd=str(self._atlas_ml_dir),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
            )

            self._reader_thread = threading.Thread(
                target=self._read_output, daemon=True)
            self._reader_thread.start()

            self._power_thread = threading.Thread(
                target=self._sample_power, daemon=True)
            self._power_thread.start()

            if self._kasa.available:
                self._kasa.start()

            return True

    def is_running(self) -> bool:
        return self._process is not None and self._process.poll() is None

    def status(self) -> dict:
        running = self.is_running()
        elapsed = None
        if self._start_time:
            end = self._end_time or time.time()
            secs = int(end - self._start_time)
            elapsed = f"{secs // 60}m {secs % 60}s"

        current_gpu_w = self._power_samples[-1] if self._power_samples else None
        kasa_watts = self._kasa.current_watts() if self._kasa.available else {}

        return {
            "running": running,
            "terminated": self._terminated,
            "elapsed": elapsed,
            "data_dir": self._data_dir,
            "last_run_name": self._last_run_name,
            "return_code": self._process.returncode if self._process else None,
            "current_power_w": current_gpu_w,
            "kasa_watts": kasa_watts,
        }

    def log_lines(self, from_index: int) -> tuple[list[str], int]:
        buf = list(self._log_buffer)
        return buf[from_index:], len(buf)

    def stop(self):
        if self._process and self._process.poll() is None:
            self._terminated = True
            self._process.terminate()

    # -------------------------------------------------------------------------
    # Internal
    # -------------------------------------------------------------------------

    def _read_output(self):
        try:
            for line in self._process.stdout:
                line = line.rstrip()
                self._log_buffer.append(line)
                if "Saved to:" in line:
                    parts = line.split("Saved to:")
                    if len(parts) > 1:
                        self._last_run_name = Path(parts[1].strip()).name
        finally:
            self._process.stdout.close()
            self._end_time = time.time()
            self._write_sidecar()

    def _sample_power(self):
        """Background thread: poll nvidia-smi and Kasa plugs while training runs."""
        loop = asyncio.new_event_loop()
        while self.is_running():
            w = _sample_gpu_power_watts()
            if w is not None:
                self._power_samples.append(w)
            if self._kasa.available:
                loop.run_until_complete(self._kasa.poll_once())
            time.sleep(_POWER_SAMPLE_INTERVAL)
        loop.close()
        if self._kasa.available:
            self._kasa.stop()

    def _energy_wh(self) -> Optional[float]:
        """Calculate watt-hours from power samples and elapsed time."""
        if not self._power_samples or not self._start_time or not self._end_time:
            return None
        avg_watts = sum(self._power_samples) / len(self._power_samples)
        duration_hours = (self._end_time - self._start_time) / 3600.0
        return round(avg_watts * duration_hours, 2)

    def _write_sidecar(self):
        """Write dashboard_meta.json with timing and energy data."""
        if not self._last_run_name or not self._start_time or not self._end_time:
            return
        run_dir = self._runs_dir / self._last_run_name
        if not run_dir.exists():
            return
        sidecar = run_dir / "dashboard_meta.json"
        try:
            data = {
                "start_time": self._start_time,
                "end_time": self._end_time,
            }
            energy = self._energy_wh()
            if energy is not None:
                data["gpu_energy_wh"] = energy
                data["gpu_avg_power_w"] = round(
                    sum(self._power_samples) / len(self._power_samples), 1)
                data["gpu_power_samples"] = len(self._power_samples)
            kasa_summary = self._kasa.energy_summary()
            if kasa_summary:
                data["kasa_energy"] = kasa_summary
            sidecar.write_text(json.dumps(data, indent=2))
        except Exception:
            pass


async def stream_logs(manager: TrainingManager):
    """Async generator for SSE log streaming."""
    cursor = 0
    was_running = False

    while True:
        running = manager.is_running()

        if running:
            was_running = True
            lines, cursor = manager.log_lines(cursor)
            for line in lines:
                safe = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                yield f"data: <div class='log-line'>{safe}</div>\n\n"
        elif was_running:
            lines, cursor = manager.log_lines(cursor)
            for line in lines:
                safe = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                yield f"data: <div class='log-line'>{safe}</div>\n\n"

            if manager._terminated:
                yield "data: <div class='log-line text-warning fw-bold'>⚠ Training run terminated.</div>\n\n"
            else:
                yield "data: <div class='log-line text-success fw-bold'>✓ Training complete.</div>\n\n"

            was_running = False
            cursor = 0

        await asyncio.sleep(0.3)
