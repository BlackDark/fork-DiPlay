package com.shilapi.xcertplay

import android.content.IntentFilter
import android.content.Intent
import android.content.BroadcastReceiver
import android.net.Uri
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class CustomBroadcastActionsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val action = "com.example.ACTION"

    @After fun cleanup() {
        CustomBroadcastActions.setEnabled(context, false)
        CustomBroadcastActions.remove(context, action)
    }

    @Test fun actionOnlyFilterDoesNotMatchData() {
        val filter = IntentFilter(action)
        assertTrue(filter.matchData(null, null, null) >= 0)
        assertEquals(IntentFilter.NO_MATCH_DATA, filter.matchData(null, "package", Uri.parse("package:example")))
        assertEquals(IntentFilter.NO_MATCH_DATA, filter.matchData("text/plain", null, null))
        assertNull(CustomBroadcastActions.dataFilter(action, "", ""))
    }

    @Test fun schemeAndMimeAreAppliedToSeparateDataFilter() {
        val packageFilter = CustomBroadcastActions.dataFilter(action, "package", "")!!
        assertTrue(packageFilter.matchData(null, "package", Uri.parse("package:example")) >= 0)
        assertTrue(packageFilter.matchData(null, "other", Uri.parse("other:example")) < 0)
        val combined = CustomBroadcastActions.dataFilter(action, "content", "text/plain")!!
        assertTrue(combined.matchData("text/plain", "content", Uri.parse("content://example/item")) >= 0)
        assertTrue(combined.matchData("image/png", "content", Uri.parse("content://example/item")) < 0)
        assertTrue(combined.matchData(null, "content", Uri.parse("content://example/item")) < 0)
    }

    @Test fun mimeOnlyMatchesWithoutSchemeOrContentAndFile() {
        val filter = CustomBroadcastActions.dataFilter(action, "", "text/*")!!
        assertTrue(filter.matchData("text/plain", null, null) >= 0)
        assertTrue(filter.matchData("text/plain", "content", Uri.parse("content://example/item")) >= 0)
        assertTrue(filter.matchData("image/png", null, null) < 0)
    }

    @Test fun dataOptionsAreValidatedAndRemovedWithAction() {
        assertTrue(CustomBroadcastActions.validScheme("package"))
        assertFalse(CustomBroadcastActions.validScheme("Package"))
        assertFalse(CustomBroadcastActions.validScheme("1bad"))
        assertTrue(CustomBroadcastActions.validMime("*/*"))
        assertTrue(CustomBroadcastActions.validMime("text/plain"))
        assertFalse(CustomBroadcastActions.validMime("*/plain"))
        assertFalse(CustomBroadcastActions.validMime("text/"))
        assertFalse(CustomBroadcastActions.validMime("TEXT/plain"))
        CustomBroadcastActions.add(context, action, "package", "text/plain")
        assertEquals("package", CustomBroadcastActions.scheme(context, action))
        assertEquals("text/plain", CustomBroadcastActions.mime(context, action))
        CustomBroadcastActions.remove(context, action)
        assertEquals("", CustomBroadcastActions.scheme(context, action))
        assertEquals("", CustomBroadcastActions.mime(context, action))
    }

    @Test fun registeredReceiverMatchesConfiguredDataAndLegacyNoData() {
        CustomBroadcastActions.add(context, action, "package")
        CustomBroadcastActions.setEnabled(context, true)
        context.sendBroadcast(Intent(action).setData(Uri.parse("package:example")))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(shadowOf(context).nextStartedActivity)
        context.sendBroadcast(Intent(action))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(shadowOf(context).nextStartedActivity)
        context.sendBroadcast(Intent(action).setData(Uri.parse("other:example")))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(context).nextStartedActivity)
        context.sendBroadcast(Intent(action).addCategory("com.example.EXTRA"))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(context).nextStartedActivity)
        CustomBroadcastActions.setActionEnabled(context, action, false)
        context.sendBroadcast(Intent(action).setData(Uri.parse("package:example")))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun filtersDoNotCrossActionsAndAllAreUnregistered() {
        val other = "com.example.OTHER"
        try {
            CustomBroadcastActions.add(context, action, "package")
            CustomBroadcastActions.add(context, other, "content", "text/plain")
            CustomBroadcastActions.setEnabled(context, true)
            context.sendBroadcast(Intent(other).setData(Uri.parse("package:example")))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(shadowOf(context).nextStartedActivity)
            CustomBroadcastActions.remove(context, action)
            context.sendBroadcast(Intent(action).setData(Uri.parse("package:example")))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(shadowOf(context).nextStartedActivity)
            CustomBroadcastActions.setEnabled(context, false)
            context.sendBroadcast(Intent(other).setDataAndType(Uri.parse("content://example/item"), "text/plain"))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(shadowOf(context).nextStartedActivity)
        } finally {
            CustomBroadcastActions.remove(context, other)
        }
    }

    @Test fun deliveryQueuedBeforeFilterRefreshCannotUseOldFilter() {
        CustomBroadcastActions.add(context, action, "package")
        CustomBroadcastActions.setEnabled(context, true)
        val old: BroadcastReceiver = ReflectionHelpers.getStaticField(CustomBroadcastActions::class.java, "receiver")
        CustomBroadcastActions.remove(context, action)
        CustomBroadcastActions.add(context, action, "content", "text/plain")
        old.onReceive(context, Intent(action).setData(Uri.parse("package:example")))
        assertNull(shadowOf(context).nextStartedActivity)
    }
}
