package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/** User-configured broadcasts are listened for only while the application process is alive. */
internal object CustomBroadcastActions {
    private const val PREFS = "custom_broadcast_actions"
    private const val ENABLED = "enabled"
    private const val ACTIONS = "actions"
    private const val DISABLED = "disabled"
    private const val SCHEME = "scheme:"
    private const val MIME = "mime:"
    const val EXTRA_LAUNCH_TOKEN = "com.shilapi.xcertplay.custom_broadcast_launch"
    val launchToken: String = UUID.randomUUID().toString()
    private var receiver: BroadcastReceiver? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)
    fun actions(context: Context) = prefs(context).getStringSet(ACTIONS, emptySet())!!.sorted()
    fun actionEnabled(context: Context, action: String) = action !in disabled(context)
    private fun disabled(context: Context) = prefs(context).getStringSet(DISABLED, emptySet())!!.toSet()
    fun valid(action: String) = action.isNotEmpty() && action.length <= 1024 && action.none { it.isISOControl() }
    fun validScheme(value: String) = value.isEmpty() || (value.length <= 128 && value.matches(Regex("[a-z][a-z0-9+.-]*")))
    fun validMime(value: String) = value.isEmpty() || (value.length <= 255 && value.matches(Regex("([a-z0-9!#$&^_.+-]+|\\*)/([a-z0-9!#$&^_.+-]+|\\*)")) && (!value.startsWith("*/") || value == "*/*"))
    fun scheme(context: Context, action: String) = prefs(context).getString(SCHEME + action, "").orEmpty()
    fun mime(context: Context, action: String) = prefs(context).getString(MIME + action, "").orEmpty()

    internal fun dataFilter(action: String, scheme: String, mime: String): IntentFilter? {
        if (scheme.isEmpty() && mime.isEmpty()) return null
        require(validScheme(scheme) && validMime(mime))
        return IntentFilter(action).apply {
            if (scheme.isNotEmpty()) addDataScheme(scheme)
            if (mime.isNotEmpty()) addDataType(mime)
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, value).apply()
        refresh(context)
    }
    fun add(context: Context, action: String, scheme: String = "", mime: String = "") {
        require(valid(action) && validScheme(scheme) && validMime(mime))
        prefs(context).edit().putStringSet(ACTIONS, actions(context).toSet() + action)
            .putString(SCHEME + action, scheme).putString(MIME + action, mime).apply()
        refresh(context)
    }
    fun setActionEnabled(context: Context, action: String, value: Boolean) {
        val updated = if (value) disabled(context) - action else disabled(context) + action
        prefs(context).edit().putStringSet(DISABLED, updated).apply()
        refresh(context)
    }
    fun remove(context: Context, action: String) {
        prefs(context).edit().putStringSet(ACTIONS, actions(context).toSet() - action)
            .putStringSet(DISABLED, disabled(context) - action)
            .remove(SCHEME + action).remove(MIME + action).apply()
        refresh(context)
    }
    fun refresh(context: Context) {
        val app = context.applicationContext
        receiver?.let { app.unregisterReceiver(it) }
        receiver = null
        if (!enabled(app)) return
        val active = actions(app).filter { actionEnabled(app, it) }
        if (active.isEmpty()) return
        val filter = IntentFilter().apply { active.forEach { addAction(it) } }
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // A delivery queued before refresh must not use an obsolete data filter.
                if (receiver !== this) return
                if (!intent.categories.isNullOrEmpty()) return
                val action = intent.action ?: return
                if (!enabled(context) || action !in actions(context) || !actionEnabled(context, action)) return
                val launch = Intent(context, DiPlayActivity::class.java).apply {
                    this.action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_LAUNCH_TOKEN, launchToken)
                }
                try { context.startActivity(launch) }
                catch (error: RuntimeException) { Log.w("DiPlayBroadcast", "Could not open DiPlay", error) }
            }
        }
        ContextCompat.registerReceiver(app, next, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = next
        active.forEach { action ->
            dataFilter(action, scheme(app, action), mime(app, action))?.let {
                ContextCompat.registerReceiver(app, next, it, ContextCompat.RECEIVER_EXPORTED)
            }
        }
    }
}
