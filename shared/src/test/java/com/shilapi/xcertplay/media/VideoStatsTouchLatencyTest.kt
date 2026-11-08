package com.shilapi.xcertplay.media

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoStatsTouchLatencyTest {
    @Test fun reportsDelayFromTouchSendToDecoderOutputRelease() {
        var nowNs = 0L
        val stats = VideoStats(nanoTime = { nowNs })

        nowNs = 1_000_000_000L
        TouchLatencyProbe.onTouchSent(sentAtNs = nowNs, sendDurationNs = 2_000_000L)
        nowNs += 10_000_000L
        stats.onReceived(1_000)
        nowNs += 20_000_000L
        stats.onRendered()
        nowNs = 6_000_000_000L

        val line = stats.logIfDue() ?: error("the five-second statistics window should be ready")
        assertTrue("touch2frame" in line)
        assertTrue("touch2output avg=30ms max=30ms n=1" in line)
    }
}
