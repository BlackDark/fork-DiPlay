package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeferredChoiceScreenshotsTest {
    @Test @Config(sdk = [29], qualifiers = "en-w320dp-h600dp-xhdpi")
    fun compactLargeFont() = capture("compact")

    @Test @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-xhdpi")
    fun fullLargeFont() = capture("full")

    private fun capture(name: String) {
        RuntimeEnvironment.setFontScale(1.5f)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        try {
            val parent = LinearLayout(activity)
            val save: (Int) -> Unit = {}
            DiPlayActivity::class.java.declaredMethods.single { it.name == "choice" }.apply {
                isAccessible = true
                invoke(activity, parent, activity.getString(R.string.cluster_map_size),
                    listOf("Standard", "Larger", "Largest"), 0, false, false, true, true, save)
            }
            parent.getChildAt(0).performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(3, dialog.listView.adapter.count)
            dialog.listView.scrollIndicators = 0
            val decor = dialog.window!!.decorView
            fun disableIndicators(view: View) {
                view.setScrollIndicators(0, View.SCROLL_INDICATOR_TOP or View.SCROLL_INDICATOR_BOTTOM or
                    View.SCROLL_INDICATOR_LEFT or View.SCROLL_INDICATOR_RIGHT or
                    View.SCROLL_INDICATOR_START or View.SCROLL_INDICATOR_END)
                if (view is ViewGroup) for (i in 0 until view.childCount) disableIndicators(view.getChildAt(i))
            }
            disableIndicators(decor)
            val width = activity.resources.displayMetrics.widthPixels
            val height = activity.resources.displayMetrics.heightPixels
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                val directory = File("build/reports/deferred-choice-ui").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
                dialog.dismiss()
            }
        } finally {
            activity.finish()
        }
    }
}
