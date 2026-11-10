package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.ScrollView
import com.shilapi.xcertplay.host.R
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HevcColorSettingsScreenshotsTest {
    @Test @Config(sdk = [29], qualifiers = "ja-w320dp-h600dp")
    fun compactJapaneseLargeFont() = capture("compact-ja")

    @Test @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedEnglishLargeFont() = capture("expanded-en")

    @Test @Config(sdk = [29], qualifiers = "ar-ldrtl-w320dp-h600dp")
    fun compactArabicRtlLargeFont() = capture("compact-ar-rtl")

    private fun capture(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        val context = RuntimeEnvironment.getApplication()
        val originalFlags = context.applicationInfo.flags
        if (name == "compact-ar-rtl") context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup().get()
        try {
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ADVANCED)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            fun layout() {
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                // The common-library test manifest lacks the mobile app's supportsRtl=true.
                if (name == "compact-ar-rtl") root.layoutDirection = View.LAYOUT_DIRECTION_RTL
                val metrics = activity.resources.displayMetrics
                root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
                shadowOf(Looper.getMainLooper()).idle()
            }
            layout()
            val setting = descendants(activity.window.decorView).filterIsInstance<Switch>()
                .single { it.contentDescription == activity.getString(R.string.settings_hevc_color_correction) }
            val row = setting.parent as View
            assertTrue("HEVC color row must have a measured size", row.width > 0 && row.height > 0)
            if (name == "compact-ar-rtl") {
                // Force the direction on the isolated row: the library test manifest has no RTL flag.
                row.layoutDirection = View.LAYOUT_DIRECTION_RTL
                val left = row.left
                val top = row.top
                row.measure(View.MeasureSpec.makeMeasureSpec(row.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(left, top, left + row.measuredWidth, top + row.measuredHeight)
                assertTrue("row should resolve RTL", row.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                assertTrue("switch should be at the logical end", setting.left < row.width / 2)
            }
            val scroll: ScrollView = ReflectionHelpers.getField(activity, "rootScroll")
            var top = 0
            var current: View? = row
            while (current != null && current !== scroll) {
                top += current.top
                current = current.parent as? View
            }
            scroll.scrollTo(0, (top - 24 * activity.resources.displayMetrics.density).toInt().coerceAtLeast(0))
            assertTrue("advanced settings should scroll to the HEVC row", scroll.scrollY > 0)
            // Robolectric's native canvas does not apply ScrollView's scroll offset while drawing a
            // detached activity root. Move the content by the same offset for the screenshot.
            val screenshotOffset = scroll.scrollY
            scroll.scrollTo(0, 0)
            scroll.getChildAt(0).offsetTopAndBottom(-screenshotOffset)
            assertTrue("HEVC row should appear in the screenshot viewport", top - screenshotOffset in 0 until scroll.height)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                root.draw(canvas)
                val output = Paths.get("build/reports/hevc-color-ui/$name.png")
                Files.createDirectories(output.parent)
                Files.newOutputStream(output).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        } finally { activity.finish(); context.applicationInfo.flags = originalFlags }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
