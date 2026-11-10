package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BroadcastDialogScreenshotsTest {
    @Test @Config(sdk = [29], qualifiers = "en-w320dp-h600dp-xhdpi")
    fun compactLargeFont() = capture("compact-en", false)
    @Test @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-xhdpi")
    fun fullLargeFont() = capture("full-en", false)
    @Test @Config(sdk = [29], qualifiers = "ar-ldrtl-w320dp-h600dp-xhdpi")
    fun compactArabicLargeFont() = capture("compact-ar", true)

    private fun capture(name: String, rtl: Boolean) {
        RuntimeEnvironment.setFontScale(1.5f)
        val app = RuntimeEnvironment.getApplication()
        val originalFlags = app.applicationInfo.flags
        if (rtl) app.applicationInfo.flags = originalFlags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showBroadcastActionDialog")
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val decor = dialog.window!!.decorView
            if (rtl) decor.layoutDirection = View.LAYOUT_DIRECTION_RTL
            fun descendants(view: View): List<View> = listOf(view) +
                (if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList())
            val inputs = descendants(decor).filterIsInstance<EditText>()
            assertEquals(3, inputs.size)
            fun disableIndicators(view: View) {
                view.setScrollIndicators(0, View.SCROLL_INDICATOR_TOP or View.SCROLL_INDICATOR_BOTTOM or
                    View.SCROLL_INDICATOR_LEFT or View.SCROLL_INDICATOR_RIGHT or View.SCROLL_INDICATOR_START or View.SCROLL_INDICATOR_END)
                if (view is ViewGroup) for (index in 0 until view.childCount) disableIndicators(view.getChildAt(index))
            }
            disableIndicators(decor)
            val width = activity.resources.displayMetrics.widthPixels
            val height = activity.resources.displayMetrics.heightPixels
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
            inputs.forEach { input ->
                assertTrue("input must be laid out", input.width > 0 && input.height > 0)
                assertTrue("input must be visible", input.visibility == View.VISIBLE && input.isShown)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                val output = File("build/reports/broadcast-dialog-ui/$name.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle(); dialog.dismiss() }
        } finally { activity.finish(); app.applicationInfo.flags = originalFlags }
    }
}
