package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
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
class DebugVideoOutputExperimentTest {
    @Test fun requiresDebuggableAppAndPrivateMarker() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.applicationInfo
        val oldFlags = info.flags
        val marker = File(context.filesDir, "debug-dedicated-video-output")
        try {
            marker.delete()
            info.flags = oldFlags or ApplicationInfo.FLAG_DEBUGGABLE
            assertFalse(DebugVideoOutputExperiment.enabled(context))
            marker.writeText("")
            assertTrue(DebugVideoOutputExperiment.enabled(context))
            info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertFalse(DebugVideoOutputExperiment.enabled(context))
        } finally {
            marker.delete()
            info.flags = oldFlags
        }
    }
}
