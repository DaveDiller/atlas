# run_manager.py — manage training subprocess and stream logs via SSE

import asyncio
import json
import subprocess
import sys
import threading
import time
from collections import deque
from datetime import datetime
from pathlib import Path
from typing import Optional


class TrainingManager:

    def __init__(self, atlas_ml_dir: Path, runs_dir: Path):
        self._atlas_ml_dir = atlas_ml_dir  # parent of atlas_ml package
        self._runs_dir = runs_dir
        self._process: Optional[subprocess.Popen] = None
        self._log_buffer: deque[str] = deque(maxlen=2000)
        self._reader_thread: Optional[threading.Thread] = None
        self._start_time: Optional[float] = None
        self._end_time: Optional[float] = None
        self._data_dir: Optional[str] = None
        self._last_run_name: Optional[str] = None
        self._terminated: bool = False
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
                target=self._read_output,
                daemon=True,
            )
            self._reader_thread.start()
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
        return {
            "running": running,
            "terminated": self._terminated,
            "elapsed": elapsed,
            "data_dir": self._data_dir,
            "last_run_name": self._last_run_name,
            "return_code": self._process.returncode if self._process else None,
        }

    def log_lines(self, from_index: int) -> tuple[list[str], int]:
        """Return new log lines since from_index and the new cursor position."""
        buf = list(self._log_buffer)
        new_lines = buf[from_index:]
        return new_lines, len(buf)

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
                # Detect run name from "Saved to: .../runs/das_cnn_YYYYMMDD_HHMMSS"
                if "Saved to:" in line:
                    parts = line.split("Saved to:")
                    if len(parts) > 1:
                        self._last_run_name = Path(parts[1].strip()).name
        finally:
            self._process.stdout.close()
            self._end_time = time.time()
            self._write_sidecar()

    def _write_sidecar(self):
        """Write dashboard_meta.json into the run directory for accurate timing."""
        if not self._last_run_name or not self._start_time or not self._end_time:
            return
        run_dir = self._runs_dir / self._last_run_name
        if run_dir.exists():
            sidecar = run_dir / "dashboard_meta.json"
            try:
                sidecar.write_text(json.dumps({
                    "start_time": self._start_time,
                    "end_time": self._end_time,
                }))
            except Exception:
                pass


async def stream_logs(manager: TrainingManager):
    """Async generator for SSE log streaming.

    Waits for a run to start, streams all output, then emits a completion
    or termination message. Stays open indefinitely so the browser connection
    persists across multiple training runs without a page reload.
    """
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
            # Run just finished — flush remaining buffered lines
            lines, cursor = manager.log_lines(cursor)
            for line in lines:
                safe = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                yield f"data: <div class='log-line'>{safe}</div>\n\n"

            if manager._terminated:
                yield "data: <div class='log-line text-warning fw-bold'>⚠ Training run terminated.</div>\n\n"
            else:
                yield "data: <div class='log-line text-success fw-bold'>✓ Training complete.</div>\n\n"

            was_running = False
            cursor = 0  # reset for next run

        await asyncio.sleep(0.3)
