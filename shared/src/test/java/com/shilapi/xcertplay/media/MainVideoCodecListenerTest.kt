package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class MainVideoCodecListenerTest {
    @Test fun reportsMainStreamCodecAndClearButIgnoresMirrors() {
        val sink = AndroidMediaSink()
        try {
            val values = mutableListOf<VideoCodec?>()
            sink.setMainVideoCodecChangedListener { values.add(it) }
            sink.onVideoCodec(120, VideoCodec.H265)
            sink.onVideoCodec(110, VideoCodec.H265)
            sink.onVideoCodec(110, VideoCodec.H264)
            sink.onScreenStreamActive(110, false)
            assertEquals(listOf(null, VideoCodec.H265, VideoCodec.H264, null), values)
            sink.onVideoCodec(110, VideoCodec.H265)
            val attached = mutableListOf<VideoCodec?>()
            sink.setMainVideoCodecChangedListener { attached.add(it) }
            assertEquals(listOf(VideoCodec.H265), attached)
            sink.setMainVideoCodecChangedListener(null)
            sink.onVideoCodec(110, VideoCodec.H264)
            assertEquals(listOf(VideoCodec.H265), attached)
        } finally { sink.close() }
    }

    @Test fun oldHostCannotUnregisterNewHostsListener() {
        val sink = AndroidMediaSink()
        try {
            val old: (VideoCodec?) -> Unit = {}
            val received = mutableListOf<VideoCodec?>()
            val current: (VideoCodec?) -> Unit = { received.add(it) }
            sink.setMainVideoCodecChangedListener(old)
            sink.setMainVideoCodecChangedListener(current)
            sink.clearMainVideoCodecChangedListener(old)
            sink.onVideoCodec(110, VideoCodec.H265)
            assertEquals(listOf(null, VideoCodec.H265), received)
            sink.clearMainVideoCodecChangedListener(current)
            sink.onVideoCodec(110, VideoCodec.H264)
            assertEquals(listOf(null, VideoCodec.H265), received)
        } finally { sink.close() }
    }
}
