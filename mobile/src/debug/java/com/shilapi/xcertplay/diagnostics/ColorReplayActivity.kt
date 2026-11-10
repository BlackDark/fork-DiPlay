package com.shilapi.xcertplay.diagnostics

import android.app.Activity
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only controlled replay. The activity intentionally has no launcher entry. */
class ColorReplayActivity : Activity() {
    private val started = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val workerDone = CountDownLatch(1)
    private var worker: Thread? = null
    private var runToken = "manual"

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runToken = intent.getStringExtra("run")?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: "manual"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        val codec = intent.getStringExtra("codec")
        val route = intent.getStringExtra("route")
        if (codec !in listOf("avc", "hevc") || route !in listOf("texture", "surface")) {
            Log.e(TAG, "ERROR expected codec=avc|hevc route=texture|surface run=$runToken")
            finish()
            return
        }
        if (route == "texture") {
            val view = TextureView(this)
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(WIDTH, HEIGHT)
                    startReplay(codec!!, route, Surface(texture), true, width, height)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    finished.countDown()
                    if (started.compareAndSet(false, true)) workerDone.countDown()
                    // Return ownership to us; release after the codec no longer uses it.
                    Thread({ workerDone.await(); texture.release() }, "color-replay-release").start()
                    return false
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
            setContentView(view)
        } else {
            val view = SurfaceView(this)
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    startReplay(codec!!, route!!, holder.surface, false, width, height)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    finished.countDown()
                    if (started.compareAndSet(false, true)) workerDone.countDown()
                    if (!workerDone.await(1, TimeUnit.SECONDS)) {
                        Log.w(TAG, "surface destroyed before decoder stopped run=$runToken")
                    }
                }
            })
            setContentView(view)
        }
    }

    private fun startReplay(codec: String, route: String, surface: Surface, ownsSurface: Boolean, width: Int, height: Int) {
        if (!started.compareAndSet(false, true)) {
            if (ownsSurface) surface.release()
            return
        }
        if (width != WIDTH || height != HEIGHT) {
            Log.e(TAG, "ERROR surface=${width}x$height expected=${WIDTH}x$HEIGHT; capture would be scaled run=$runToken")
            if (ownsSurface) surface.release()
            workerDone.countDown()
            finish()
            return
        }
        worker = Thread({
            try {
                replay(codec, route, surface)
            } catch (e: Exception) {
                Log.e(TAG, "ERROR replay failed run=$runToken", e)
            } finally {
                if (ownsSurface) surface.release()
                workerDone.countDown()
            }
        }, "color-replay").also { it.start() }
    }

    private fun replay(codec: String, route: String, surface: Surface) {
        val expectedMime = if (codec == "avc") MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
        val decoderName = if (codec == "avc") "OMX.MTK.VIDEO.DECODER.AVC" else "OMX.MTK.VIDEO.DECODER.HEVC"
        val file = File(filesDir, "color-fixture/$codec.mp4")
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == expectedMime
            } ?: error("fixture has no $expectedMime track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            require(format.getInteger(MediaFormat.KEY_WIDTH) == WIDTH && format.getInteger(MediaFormat.KEY_HEIGHT) == HEIGHT) {
                "fixture dimensions differ from ${WIDTH}x$HEIGHT"
            }
            decoder = MediaCodec.createByCodecName(decoderName)
            Log.i(TAG, "START route=$route decoder=${decoder.name} input=${describeFormat(format)} surface=${WIDTH}x$HEIGHT run=$runToken")
            decoder.setOnFrameRenderedListener({ _, presentationTimeUs, nanoTime ->
                Log.i(TAG, "FRAME_RENDERED ptsUs=$presentationTimeUs nanoTime=$nanoTime; not panel scanout run=$runToken")
            }, Handler(Looper.getMainLooper()))
            decoder.configure(format, surface, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            var sentEos = false
            var rendered = false
            while (!rendered && finished.count > 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!sentEos) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(decoder.getInputBuffer(inputIndex))
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sentEos = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.i(TAG, "OUTPUT_FORMAT ${describeFormat(decoder.outputFormat)} run=$runToken")
                    in 0..Int.MAX_VALUE -> {
                        // Surface decoders can report zero-sized buffers for actual frames.
                        val isFrame = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0
                        decoder.releaseOutputBuffer(index, isFrame)
                        if (isFrame) {
                            Log.i(TAG, "FRAME_RELEASED route=$route codec=$codec; wait for FRAME_RENDERED and compositor before screenshot run=$runToken")
                            rendered = true
                        }
                    }
                }
            }
            check(rendered) { "decoder did not produce a frame before timeout or surface loss" }
            // Keep decoder and surface alive so the compositor retains the frame for capture.
            finished.await()
        } finally {
            try { decoder?.stop() } catch (_: IllegalStateException) { }
            decoder?.release()
            extractor.release()
            Log.i(TAG, "STOP route=$route codec=$codec run=$runToken")
        }
    }

    override fun onDestroy() {
        finished.countDown()
        super.onDestroy()
    }

    override fun onStop() {
        finished.countDown()
        super.onStop()
        finish()
    }

    private fun describeFormat(format: MediaFormat): String {
        val keys = listOf(
            MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_COLOR_FORMAT,
            MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER,
            "stride", "slice-height", "crop-left", "crop-top", "crop-right", "crop-bottom"
        )
        return keys.filter { format.containsKey(it) }.joinToString(" ") { key ->
            "$key=${try { format.getInteger(key).toString() } catch (_: Exception) { "unavailable" }}"
        }
    }

    private companion object {
        const val TAG = "DiPlayColorReplay"
        const val WIDTH = 1280
        const val HEIGHT = 720
    }
}
