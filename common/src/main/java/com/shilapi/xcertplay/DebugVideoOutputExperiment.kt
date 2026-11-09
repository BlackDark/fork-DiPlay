package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

/** Local A/B experiment. Release builds never consult the marker. */
internal object DebugVideoOutputExperiment {
    private const val MARKER = "debug-dedicated-video-output"

    fun enabled(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            File(context.filesDir, MARKER).isFile
}
