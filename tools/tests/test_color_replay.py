import importlib.util
import json
import struct
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("color_replay", Path(__file__).parents[1] / "color_replay.py")
replay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(replay)


def png(width=1280, height=720):
    return replay.PNG_HEADER + b"\x00\x00\x00\x0dIHDR" + struct.pack(">II", width, height)


class FakeAdb:
    def __init__(self, screenshot=None, stop=True):
        self.marker = None
        self.active = False
        self.stopped = False
        self.stop = stop
        self.screenshot = screenshot if screenshot is not None else png()
        self.calls = []
        self.log_count = 0

    def shell(self, command, stdin=None):
        self.calls.append(command)
        if command.startswith("log -t "):
            self.marker = command.split("RUN ")[1].rstrip("'")
        if command.startswith("am start"):
            self.active = True
        if command == "input keyevent KEYCODE_BACK":
            self.active = False
            self.stopped = True
        if command == "dumpsys activity activities":
            activity = replay.ACTIVITY if self.active else "original.app/.Main"
            return f"mResumedActivity: {activity}".encode()
        return b""

    def call(self, *args, **kwargs):
        if args[0] == "exec-out":
            return self.screenshot
        self.log_count += 1
        marker = self.marker
        log = (f"DiPlayColorReplay: RUN {marker}\n"
               f"DiPlayColorReplay: START route=texture decoder={replay.DECODERS['hevc']} input=width=1280 height=720 surface=1280x720 run={marker}\n"
               f"DiPlayColorReplay: OUTPUT_FORMAT width=1280 height=720 color-range={self.log_count} run={marker}\n"
               f"DiPlayColorReplay: FRAME_RENDERED ptsUs=0 run={marker}\n")
        if self.stopped and self.stop:
            log += f"DiPlayColorReplay: STOP route=texture codec=hevc run={marker}\n"
        return log.encode()


class ReplayTest(unittest.TestCase):
    def test_stale_callback_and_wrong_decoder_do_not_satisfy_ready(self):
        marker = "a" * 32
        prior = "b" * 32
        log = (f"tag: RUN {marker}\n"
               f"tag: START route=texture decoder={replay.DECODERS['hevc']} surface=1280x720 run={marker}\n"
               f"tag: OUTPUT_FORMAT width=1280 height=720 run={marker}\n"
               f"tag: FRAME_RENDERED ptsUs=0 run={prior}\n")
        self.assertIsNone(replay.ready(log, marker, "hevc", "texture"))
        self.assertIsNone(replay.ready(log.replace(prior, marker), marker, "avc", "texture"))
        self.assertIsNotNone(replay.ready(log.replace(prior, marker), marker, "hevc", "texture"))

    def test_wrong_or_missing_format_is_rejected(self):
        marker = "c" * 32
        base = (f"tag: RUN {marker}\ntag: START route=texture decoder={replay.DECODERS['hevc']} surface=1280x720 run={marker}\n"
                f"tag: OUTPUT_FORMAT width=1280 height=768 run={marker}\ntag: FRAME_RENDERED run={marker}\n")
        self.assertIsNone(replay.ready(base, marker, "hevc", "texture"))
        self.assertIsNotNone(replay.ready(base.replace("height=768", "height=768 crop-left=0 crop-top=0 crop-right=1279 crop-bottom=719"), marker, "hevc", "texture"))
        self.assertIsNone(replay.ready(base.replace("OUTPUT_FORMAT", "OTHER_FORMAT"), marker, "hevc", "texture"))
        wrong_crop = base.replace("height=768", "height=720 crop-left=5 crop-top=0 crop-right=1279 crop-bottom=719")
        self.assertIsNone(replay.ready(wrong_crop, marker, "hevc", "texture"))
        too_small = base.replace("width=1280 height=768 run=", "width=1000 height=720 crop-left=0 crop-top=0 crop-right=1279 crop-bottom=719 run=")
        self.assertIsNone(replay.ready(too_small, marker, "hevc", "texture"))
        self.assertIsNone(replay.ready(base.replace("height=768", "height=720") + f"tag: STOP run={marker}\n", marker, "hevc", "texture"))

    def test_success_captures_comparison_and_returns_to_previous_app(self):
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as directory, patch.object(replay.time, "sleep"), \
             patch.object(replay.subprocess, "run", return_value=SimpleNamespace(stdout=json.dumps({"mean_absolute_error": 1, "max_absolute_error": 2}))):
            output = Path(directory)
            result = replay.capture(adb, "hevc", "texture", output, output / "compare.py", output / "ref.png", 1, 2)
            self.assertIsNone(result["color_pass"])
            self.assertEqual(2, result["output_format"]["color-range"])
            self.assertTrue((output / "hevc-texture.png").exists())
            self.assertTrue((output / "hevc-texture-comparison.json").exists())
            self.assertIn("input keyevent KEYCODE_BACK", adb.calls)
            self.assertFalse(adb.active)

    def test_wrong_screenshot_dimensions_fails_and_still_closes_activity(self):
        adb = FakeAdb(png(640, 360))
        with tempfile.TemporaryDirectory() as directory, patch.object(replay.time, "sleep"):
            output = Path(directory)
            with self.assertRaisesRegex(RuntimeError, "dimensions"):
                replay.capture(adb, "hevc", "texture", output, output / "compare.py", output / "ref.png", 1, 2)
            self.assertFalse(adb.active)
            self.assertFalse((output / "hevc-texture.png").exists())

    def test_fixture_readback_mismatch_fails(self):
        class CorruptAdb:
            def shell(self, command, stdin=None):
                return b"different" if " cat files/" in command else b""
        with self.assertRaisesRegex(RuntimeError, "SHA-256"):
            replay.install_fixture(CorruptAdb(), "avc", b"fixture")

    def test_stop_during_settle_rejects_capture(self):
        adb = FakeAdb()
        def stop_during_sleep(_):
            adb.stopped = True
        with tempfile.TemporaryDirectory() as directory, patch.object(replay.time, "sleep", side_effect=stop_during_sleep):
            output = Path(directory)
            with self.assertRaisesRegex(RuntimeError, "stopped or output changed"):
                replay.capture(adb, "hevc", "texture", output, output / "compare.py", output / "ref.png", 1, 2)
            self.assertFalse((output / "hevc-texture.png").exists())


if __name__ == "__main__":
    unittest.main()
