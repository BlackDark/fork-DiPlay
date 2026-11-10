# Hardware color replay (debug builds only)

Purpose: compare the same controlled 1280×720 AVC/HEVC fixtures through the MTK hardware decoder and TextureView/SurfaceView. No CarPlay frames or credentials are captured. This tests decoder plus surface composition; it does not reproduce CarPlayPicture filters, OEM overlays, physical panel behavior, or prove scanout timing.

The activity exists only in `mobile` debug APKs. It is exported with `android.permission.DUMP`, has no launcher entry, and must be launched via ADB shell. It uses only app-private `files/color-fixture/avc.mp4` and `hevc.mp4`, and refuses non-1280×720 surfaces. Starting it brings DiPlay's debug package to the foreground and interrupts a running CarPlay session. It releases the decoder when the activity stops. Install an appropriate debug APK first; an ordinary debug APK has no CarPlay authentication assets.

For the four-route capture, the guided runner verifies the app-private fixture bytes, matching decoder and frame callback for each unique run, a 1280×720 output and screenshot, and decoder shutdown before the next run. It saves logs, PNGs, comparison JSON and foreground state to a new directory. It uses Back only when the replay activity is foreground, then records the final foreground; it does not force-launch another app if restoration fails. A callback is not panel scanout, and each image still needs visual inspection for overlays, scaling or other contamination. No color pass is inferred from the error metric.

```sh
python3 tools/color_replay.py --serial 192.168.1.229:5555 \
  --fixture-dir /Users/marbaced/projects/headunit/out/color-calibration \
  --compare-script /Users/marbaced/projects/headunit/tools/color-calibration-fixture.py \
  --output-dir /Users/marbaced/projects/headunit/out/color-replay-visit
```

The output directory must not exist already. Use the current device serial and a new directory for each visit. `ffmpeg` and `ffprobe` must be on PATH for the comparator. No connection or replay occurs until this command is run explicitly.

Generate the fixture using `/Users/marbaced/projects/headunit/docs/operations/diplay-color-fixture.md`. Set the shell variables below to the device serial and generated output directory. `run-as` requires the installed package to be debuggable.

```sh
serial=192.168.1.229:5555
fixture=/Users/marbaced/projects/headunit/out/color-calibration
package=com.shihab.diplay.hudtest
adb -s "$serial" shell run-as "$package" mkdir -p files/color-fixture
adb -s "$serial" shell -T "run-as $package sh -c 'cat > files/color-fixture/avc.mp4'" < "$fixture/avc.mp4"
adb -s "$serial" shell -T "run-as $package sh -c 'cat > files/color-fixture/hevc.mp4'" < "$fixture/hevc.mp4"
adb -s "$serial" shell -T run-as "$package" cat files/color-fixture/avc.mp4 | shasum -a 256
adb -s "$serial" shell -T run-as "$package" cat files/color-fixture/hevc.mp4 | shasum -a 256
shasum -a 256 "$fixture/avc.mp4" "$fixture/hevc.mp4"
```

Check hashes match before replay. Repeat all four combinations (`avc`/`hevc`, `texture`/`surface`):

```sh
adb -s "$serial" shell log -t DiPlayColorReplay 'RUN hevc-texture'
adb -s "$serial" shell am start -n "$package/com.shilapi.xcertplay.diagnostics.ColorReplayActivity" --es codec hevc --es route texture
adb -s "$serial" logcat -v threadtime -d -s DiPlayColorReplay:I '*:S' > color-hevc-texture.log
adb -s "$serial" exec-out screencap -p > color-hevc-texture.png
adb -s "$serial" shell input keyevent KEYCODE_BACK
```

Before each capture, inspect log lines after the latest `RUN` marker for the new `START` with the expected route and MTK decoder, `OUTPUT_FORMAT`, and `FRAME_RENDERED`. Wait at least two seconds after `FRAME_RENDERED` for compositor settling; the callback is not proof of panel scanout. If no callback arrives, a `FRAME_RELEASED` line alone is insufficient to validate a capture. Check screenshot dimensions are exactly 1280×720, the full chart is visible and unobscured, and system/brightness/picture settings are identical. Save each log and PNG with distinct codec/route names. Press Back after each capture; check `STOP` in the log before starting the next one. Restore DiPlay/CarPlay after the experiment.

Compare each capture against its corresponding software reference using `color-calibration-fixture.py compare`. Treat capture errors, missing callbacks, surface resize, overlays, or non-matching chart geometry as invalid evidence. API 27 does not expose a reliable per-layer dataspace or physical display measurement through this harness. Take separate photos for panel-level comparisons. Hardware replay has not yet been verified on the target device.
