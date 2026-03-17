# kasa_monitor.py — TP-Link Kasa smart plug energy monitoring
#
# Polls one or more Kasa plugs during training to record whole-device
# power consumption. Requires python-kasa to be installed.
#
# Plugs are configured in dashboard_config.json under "kasa_plugs":
#   {
#     "kasa_plugs": {
#       "marlin": "192.168.1.50",
#       "nas":    "192.168.1.51"
#     }
#   }

import asyncio
import time
from typing import Optional

_kasa_available = False
try:
    from kasa import SmartPlug
    _kasa_available = True
except ImportError:
    pass


async def read_plug_watts(ip: str) -> Optional[float]:
    """Read instantaneous power draw in watts from a Kasa plug."""
    if not _kasa_available:
        return None
    try:
        plug = SmartPlug(ip)
        await asyncio.wait_for(plug.update(), timeout=3.0)
        emeter = plug.emeter_realtime
        # python-kasa returns power in watts under 'power' or 'power_mw'
        if "power" in emeter:
            return float(emeter["power"])
        if "power_mw" in emeter:
            return float(emeter["power_mw"]) / 1000.0
    except Exception:
        pass
    return None


class KasaMonitor:
    """
    Polls configured Kasa plugs at regular intervals during a training run.
    Accumulates per-device power samples for energy calculation.
    """

    SAMPLE_INTERVAL = 10.0  # seconds between polls

    def __init__(self, plugs: dict[str, str]):
        """
        plugs: dict of {label: ip_address}
        e.g. {"marlin": "192.168.1.50", "nas": "192.168.1.51"}
        """
        self._plugs = plugs  # label -> ip
        self._samples: dict[str, list[float]] = {k: [] for k in plugs}
        self._running = False
        self._start_time: Optional[float] = None
        self._end_time: Optional[float] = None

    def start(self):
        self._samples = {k: [] for k in self._plugs}
        self._start_time = time.time()
        self._end_time = None
        self._running = True

    def stop(self):
        self._running = False
        self._end_time = time.time()

    async def poll_once(self):
        """Take one power reading from all plugs. Call from async context."""
        for label, ip in self._plugs.items():
            w = await read_plug_watts(ip)
            if w is not None:
                self._samples[label].append(w)

    def energy_summary(self) -> dict:
        """
        Returns per-device energy summary:
        {
          "marlin": {"avg_w": 524.3, "energy_wh": 17.5, "samples": 22},
          "nas":    {"avg_w":  38.1, "energy_wh":  1.3, "samples": 22},
        }
        """
        if not self._start_time or not self._end_time:
            return {}
        duration_h = (self._end_time - self._start_time) / 3600.0
        result = {}
        for label, samples in self._samples.items():
            if samples:
                avg_w = sum(samples) / len(samples)
                result[label] = {
                    "avg_w": round(avg_w, 1),
                    "energy_wh": round(avg_w * duration_h, 2),
                    "samples": len(samples),
                }
        return result

    def current_watts(self) -> dict[str, float]:
        """Most recent reading per plug, for live display."""
        return {
            label: samples[-1]
            for label, samples in self._samples.items()
            if samples
        }

    @property
    def available(self) -> bool:
        return _kasa_available and bool(self._plugs)
