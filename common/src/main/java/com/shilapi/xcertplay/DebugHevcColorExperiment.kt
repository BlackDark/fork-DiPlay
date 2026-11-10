package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.ColorMatrix
import java.io.File

/** Reversible hypothesis test: correct an assumed BT.601 conversion of BT.709 content. */
internal object DebugHevcColorExperiment {
    private const val MARKER = "debug-hevc-601-to-709"
    const val PREFERENCE = "hevc_color_correction"

    private fun markerEnabled(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            File(context.filesDir, MARKER).isFile

    // An explicit choice overrides the old debug marker, including when switched off.
    fun enabled(context: Context): Boolean {
        val prefs = CarPlayPicture.preferences(context)
        return if (prefs.contains(PREFERENCE)) prefs.getBoolean(PREFERENCE, false) else markerEnabled(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        CarPlayPicture.preferences(context).edit().putBoolean(PREFERENCE, enabled).apply()
    }

    // RGB -> Y'CbCr using 601, then Y'CbCr -> RGB using 709. No transfer conversion.
    // Assumes identical component ranges. Values already clipped by the decoder cannot be restored.
    fun correction(): ColorMatrix = ColorMatrix(floatArrayOf(
        1.0864f, -0.07234922f, -0.01405078f, 0f, 0f,
        0.09654619f, 0.84505163f, 0.05840218f, 0f, 0f,
        -0.01410632f, -0.02769368f, 1.0418f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
}
