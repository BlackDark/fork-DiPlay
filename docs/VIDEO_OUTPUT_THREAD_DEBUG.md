# Decoder output-thread experiment

This experiment lets a debuggable APK drain the main CarPlay decoder on a separate thread without enabling vendor low-latency codec options. It is off by default. Release APKs ignore the marker. This is an A/B test, not a proven frame-rate improvement. Run it while parked.

With the debug package installed and opened once, use its private app directory to enable the experiment:

```sh
adb -s "$serial" shell run-as com.shihab.diplay.hudtest touch files/debug-dedicated-video-output
```

Fully close and reopen the debug app, then reconnect CarPlay. This ensures the next controller reads the marker. Check its diagnostic report for the `decoder=` line ending in `outputThread=true`. Keep all video settings, content, and route fixed between A and B; especially keep the existing low-latency decoder option off because it also enables the output thread and changes codec settings. Compare `rx`, `shown`, decode p50/p90, recoveries, `maxGap`, and user-observed stutter across equal interaction runs. `shown` counts release to the Surface, not physical panel scanout.

Remove the marker, then fully close and reopen the debug app to return to normal behavior:

```sh
adb -s "$serial" shell run-as com.shihab.diplay.hudtest rm -f files/debug-dedicated-video-output
```

The separate thread calls the platform decoder while input is queued on another thread. Its lifecycle is covered by host-side tests, but this MediaTek decoder's behavior under concurrent calls, reconnect, and surface teardown remains unverified. If the app hangs, crashes, or video corrupts, remove the marker and restart the app. The decoder teardown wait is bounded to 500 ms; a vendor call that remains blocked longer can overlap codec release. Surface changes may also overlap an output dequeue.
