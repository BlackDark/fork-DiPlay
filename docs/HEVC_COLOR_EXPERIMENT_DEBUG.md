# HEVC color correction experiment

Advanced > HEVC color correction (experimental). Default off. Applies live to the main HEVC TextureView; H.264 and SurfaceView (Direct/Smooth) unchanged. Test on affected hardware; may worsen colors elsewhere or cost GPU time. Picture controls apply afterward; original preview bypasses both.

Hypothesis: unit converts BT.709 using BT.601 coefficients. RGB matrix approximates 601-to-709 after decoding; cannot restore clipped channels. Controlled fixture did not reproduce the codec mismatch. Source colors and decoder behavior remain unproven. A retained frame may briefly use the new matrix when the codec announcement changes.

Legacy debug marker `files/debug-hevc-601-to-709` enables correction only in debuggable builds when no explicit setting exists. Once the user chooses on or off, that choice overrides the marker. To compare, toggle the setting without reconnecting. Confirm HEVC and TextureView are active; capture matching screenshots and check the physical display plus frame timing.
