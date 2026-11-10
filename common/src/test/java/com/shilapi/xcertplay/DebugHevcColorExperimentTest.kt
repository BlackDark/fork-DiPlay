package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.graphics.ColorMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class DebugHevcColorExperimentTest {
    private fun pixel(m: ColorMatrix, input: FloatArray): FloatArray = FloatArray(4) { row ->
        (m.array[row * 5 + 4] + (0..3).sumOf { (m.array[row * 5 + it] * input[it]).toDouble() }).toFloat()
    }

    @Test fun requiresDebuggableAppAndPrivateMarker() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.applicationInfo
        val flags = info.flags
        val marker = File(context.filesDir, "debug-hevc-601-to-709")
        val prefs = CarPlayPicture.preferences(context)
        try {
            prefs.edit().clear().commit()
            marker.delete()
            info.flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
            assertFalse(DebugHevcColorExperiment.enabled(context))
            marker.writeText("")
            assertTrue(DebugHevcColorExperiment.enabled(context))
            info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertFalse(DebugHevcColorExperiment.enabled(context))
        } finally { marker.delete(); prefs.edit().clear().commit(); info.flags = flags }
    }

    @Test fun explicitChoiceOverridesMarkerAndWorksInRelease() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.applicationInfo
        val flags = info.flags
        val marker = File(context.filesDir, "debug-hevc-601-to-709")
        val prefs = CarPlayPicture.preferences(context)
        try {
            prefs.edit().clear().commit()
            marker.writeText("")
            info.flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
            assertTrue(DebugHevcColorExperiment.enabled(context))
            DebugHevcColorExperiment.setEnabled(context, false)
            assertFalse(DebugHevcColorExperiment.enabled(context))
            info.flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            DebugHevcColorExperiment.setEnabled(context, true)
            assertTrue(DebugHevcColorExperiment.enabled(context))
            DebugHevcColorExperiment.setEnabled(context, false)
            assertFalse(DebugHevcColorExperiment.enabled(context))
        } finally { marker.delete(); prefs.edit().clear().commit(); info.flags = flags }
    }

    @Test fun correctionMatchesKnownSamplesAndPreservesAlpha() {
        val actual = pixel(DebugHevcColorExperiment.correction(), floatArrayOf(8f, 243f, 84f, 77f))
        floatArrayOf(0f, 211.0f, 80.7f, 77f).forEachIndexed { i, expected ->
            // Android clamps transformed channels after applying the composed filter.
            assertEquals(expected, actual[i].coerceIn(0f, 255f), 0.15f)
        }
    }

    @Test fun pictureControlsAreAppliedAfterCorrection() {
        val prefs = CarPlayPicture.preferences(RuntimeEnvironment.getApplication())
        prefs.edit().clear().putInt(CarPlayPicture.WARMTH, 100).commit()
        try {
            val input = floatArrayOf(8f, 243f, 84f, 77f)
            val first = pixel(DebugHevcColorExperiment.correction(), input)
            val expected = pixel(CarPlayPicture.matrix(prefs), first)
            val actual = pixel(CarPlayPicture.combinedMatrix(prefs, true), input)
            expected.forEachIndexed { i, value -> assertEquals(value, actual[i], 0.001f) }
            val disabled = CarPlayPicture.combinedMatrix(prefs, false).array
            CarPlayPicture.matrix(prefs).array.forEachIndexed { i, value -> assertEquals(value, disabled[i], 0.001f) }
        } finally { prefs.edit().clear().commit() }
    }
}
