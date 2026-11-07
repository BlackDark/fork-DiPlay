package com.shilapi.xcertplay

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        VehicleAutoStart.launch(context, "boot")
    }

}

/** Registers for the OEM's suspend-resume event while DiPlay's process is alive. */
class DiPlayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            VehicleWakeReceiver(),
            IntentFilter(VehicleWakeReceiver.ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}

internal class VehicleWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        VehicleAutoStart.launchAfterWake(context)
    }

    companion object {
        const val ACTION = "com.reglink.action.WAKEUP"
    }
}

private object VehicleAutoStart {
    private const val WAKE_START_DELAY_MS = 6_000L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun launchAfterWake(context: Context) {
        val appContext = context.applicationContext
        val enabled = AirPlayPersistence.loadAutoStartOnBoot(appContext)
        StartupDiagnosticSnapshot.received(appContext, "wake", enabled)
        if (!enabled) return

        // OEM restores Wi-Fi five seconds after wake; wait one more second before opening DiPlay.
        mainHandler.postDelayed({
            if (AirPlayPersistence.loadAutoStartOnBoot(appContext)) launch(appContext, "wake")
        }, WAKE_START_DELAY_MS)
    }

    fun launch(context: Context, trigger: String) {
        val enabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, trigger, enabled)
        if (!enabled) return

        // Match the launcher intent so an existing CarPlay task comes forward without starting Home over it.
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Vehicle auto-start could not launch DiPlayActivity after $trigger", error)
        }
    }

    private const val TAG = "xcertplay-boot"
}
