#!/usr/bin/env python3
"""Capture controlled MTK decoder replay screenshots. Requires an explicit device serial."""

import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
import time
import uuid
from pathlib import Path

PACKAGE = "com.shihab.diplay.hudtest"
ACTIVITY = "com.shilapi.xcertplay.diagnostics.ColorReplayActivity"
TAG = "DiPlayColorReplay"
DECODERS = {"avc": "OMX.MTK.VIDEO.DECODER.AVC", "hevc": "OMX.MTK.VIDEO.DECODER.HEVC"}
PNG_HEADER = b"\x89PNG\r\n\x1a\n"


def current_run(log, marker):
    """Only return log lines after the last exact per-run marker."""
    lines = log.splitlines()
    found = [i for i, line in enumerate(lines) if line.endswith("RUN " + marker)]
    return "\n".join(lines[found[-1] + 1:]) if found else ""


def ready(log, marker, codec, route):
    """Return parsed output format only for an entirely current, matching run."""
    lines = [line for line in current_run(log, marker).splitlines() if f"run={marker}" in line]
    starts = [i for i, line in enumerate(lines) if f"START route={route} decoder={DECODERS[codec]} " in line
              and "surface=1280x720" in line]
    if not starts:
        return None
    lines = lines[starts[-1]:]
    if any("STOP " in line for line in lines):
        return None
    outputs = [i for i, line in enumerate(lines) if "OUTPUT_FORMAT " in line]
    if not outputs:
        return None
    output = lines[outputs[-1]]
    fields = {key: int(value) for key, value in re.findall(r"([a-z-]+)=(-?\d+)", output)}
    width, height = fields.get("width", 0), fields.get("height", 0)
    crop = {"crop-left": 0, "crop-top": 0, "crop-right": 1279, "crop-bottom": 719}
    if any(key in fields for key in crop):
        geometry_ok = width >= 1280 and height >= 720 and all(fields.get(key) == value for key, value in crop.items())
    else:
        geometry_ok = width == 1280 and height == 720
    if not geometry_ok:
        return None
    if not any("FRAME_RENDERED " in line for line in lines[outputs[-1] + 1:]):
        return None
    return fields


def screenshot_dimensions(data):
    if len(data) < 24 or data[:8] != PNG_HEADER or data[12:16] != b"IHDR":
        raise ValueError("invalid PNG screenshot")
    return struct.unpack(">II", data[16:24])


class Adb:
    def __init__(self, binary, serial):
        self.binary, self.serial = binary, serial

    def call(self, *args, stdin=None, timeout=20):
        return subprocess.run([self.binary, "-s", self.serial, *args], input=stdin,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              check=True, timeout=timeout).stdout

    def shell(self, command, stdin=None):
        return self.call("shell", "-T", command, stdin=stdin)


def foreground(adb):
    output = adb.shell("dumpsys activity activities").decode("utf-8", "replace")
    lines = [line.strip() for line in output.splitlines()
             if "mResumedActivity:" in line or "topResumedActivity=" in line]
    return lines[-1] if lines else "unknown"


def install_fixture(adb, name, data):
    # Fixed names only. Binary input and output use adb shell -T (no PTY conversion).
    adb.shell(f"run-as {PACKAGE} sh -c 'cat > files/color-fixture/{name}.mp4'", stdin=data)
    readback = adb.shell(f"run-as {PACKAGE} cat files/color-fixture/{name}.mp4")
    expected = hashlib.sha256(data).hexdigest()
    if hashlib.sha256(readback).hexdigest() != expected:
        raise RuntimeError(f"{name}: fixture readback SHA-256 mismatch")
    return expected


def capture(adb, codec, route, output, compare_script, reference, timeout, settle):
    marker = uuid.uuid4().hex
    adb.shell(f"log -t {TAG} 'RUN {marker}'")
    result = {"codec": codec, "route": route, "marker": marker,
              "expected_decoder": DECODERS[codec], "color_pass": None,
              "caveat": "Inspect the full chart visually for overlays/scaling. Frame callback is not panel scanout."}
    log_path = output / f"{codec}-{route}.log"
    saw_start = False
    try:
        adb.shell(f"am start -n {PACKAGE}/{ACTIVITY} --es codec {codec} --es route {route} --es run {marker}")
        deadline = time.monotonic() + timeout
        info = None
        while time.monotonic() < deadline:
            log = adb.call("logcat", "-d", "-v", "threadtime", "-s", f"{TAG}:I", "*:S").decode("utf-8", "replace")
            log_path.write_text(current_run(log, marker) + "\n")
            tagged = [line for line in current_run(log, marker).splitlines() if f"run={marker}" in line]
            saw_start = saw_start or any("START " in line for line in tagged)
            if any(" ERROR " in line for line in tagged):
                raise RuntimeError(f"{codec}/{route}: replay logged ERROR; inspect {log_path}")
            info = ready(log, marker, codec, route)
            if info is not None:
                break
            time.sleep(0.5)
        if info is None:
            raise RuntimeError(f"{codec}/{route}: missing current decoder, output format, or FRAME_RENDERED")
        time.sleep(settle)
        log = adb.call("logcat", "-d", "-v", "threadtime", "-s", f"{TAG}:I", "*:S").decode("utf-8", "replace")
        log_path.write_text(current_run(log, marker) + "\n")
        info = ready(log, marker, codec, route)
        if info is None:
            raise RuntimeError(f"{codec}/{route}: decoder stopped or output changed during settling")
        if ACTIVITY not in foreground(adb):
            raise RuntimeError(f"{codec}/{route}: replay is no longer foreground")
        data = adb.call("exec-out", "screencap", "-p")
        if screenshot_dimensions(data) != (1280, 720):
            raise RuntimeError(f"{codec}/{route}: screenshot dimensions are not 1280x720")
        screenshot = output / f"{codec}-{route}.png"
        screenshot.write_bytes(data)
        comparison = subprocess.run([sys.executable, str(compare_script), "compare", str(reference), str(screenshot)],
                                    check=True, capture_output=True, text=True, timeout=60)
        measurement = json.loads(comparison.stdout)
        (output / f"{codec}-{route}-comparison.json").write_text(json.dumps(measurement, indent=2) + "\n")
        result.update({"capture": screenshot.name, "output_format": info,
                       "mean_absolute_error": measurement["mean_absolute_error"],
                       "max_absolute_error": measurement["max_absolute_error"],
                       "capture_status": "collected; visual inspection required"})
        return result
    finally:
        # Never send Back to another app if replay failed to open or has been covered.
        active_error = sys.exc_info()[0] is not None
        try:
            if ACTIVITY in foreground(adb):
                adb.shell("input keyevent KEYCODE_BACK")
            if saw_start:
                stop_deadline = time.monotonic() + 10
                while time.monotonic() < stop_deadline:
                    log = adb.call("logcat", "-d", "-v", "threadtime", "-s", f"{TAG}:I", "*:S").decode("utf-8", "replace")
                    log_path.write_text(current_run(log, marker) + "\n")
                    if any("STOP " in line and f"run={marker}" in line for line in current_run(log, marker).splitlines()):
                        break
                    time.sleep(0.5)
                else:
                    if not active_error:
                        raise RuntimeError(f"{codec}/{route}: decoder did not report STOP")
        except (subprocess.SubprocessError, OSError):
            if not active_error:
                raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="explicit ADB device serial")
    parser.add_argument("--fixture-dir", type=Path, required=True)
    parser.add_argument("--compare-script", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True, help="new directory; existing files are not overwritten")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--settle", type=float, default=2)
    args = parser.parse_args()
    if args.timeout <= 0 or args.settle < 2:
        parser.error("timeout must be positive; settle must be at least 2 seconds")
    for name in ("avc.mp4", "hevc.mp4", "avc-software.png", "hevc-software.png"):
        if not (args.fixture_dir / name).is_file():
            parser.error(f"missing fixture: {name}")
    if not args.compare_script.is_file():
        parser.error("comparison script does not exist")
    args.output_dir.mkdir(parents=True, exist_ok=False)
    adb = Adb(args.adb, args.serial)
    summary = {"serial": args.serial, "original_foreground": "unknown", "runs": [],
               "note": "Hardware captures need visual review; callbacks do not measure panel scanout."}
    try:
        summary["original_foreground"] = foreground(adb)
        adb.shell(f"run-as {PACKAGE} mkdir -p files/color-fixture")
        summary["fixture_sha256"] = {name: install_fixture(adb, name, (args.fixture_dir / f"{name}.mp4").read_bytes())
                                     for name in DECODERS}
        for codec in DECODERS:
            for route in ("texture", "surface"):
                print(f"Capturing {codec}/{route}; keep display unobstructed", flush=True)
                summary["runs"].append(capture(adb, codec, route, args.output_dir, args.compare_script,
                                               args.fixture_dir / f"{codec}-software.png", args.timeout, args.settle))
        summary["status"] = "collected; visual inspection required"
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError, KeyError, json.JSONDecodeError) as error:
        summary["status"] = "incomplete"
        summary["error"] = str(error)
        raise
    finally:
        try:
            summary["final_foreground"] = foreground(adb)
        except (subprocess.SubprocessError, OSError):
            summary["final_foreground"] = "unknown"
        (args.output_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"Results: {args.output_dir} (inspect every screenshot; no automatic color pass)")


if __name__ == "__main__":
    main()
