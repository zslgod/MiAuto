package com.andrerinas.openheadunit

import androidx.appcompat.app.AppCompatDelegate
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.multidex.MultiDex
import com.andrerinas.openheadunit.connection.IcocaLinkState
import com.andrerinas.openheadunit.icoca.IcocaProjectionActivity
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.aap.AapNavigation
import com.andrerinas.openheadunit.ssl.ConscryptInitializer
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.Settings
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {

    private val component: AppComponent by lazy {
        AppComponent(this)
    }

    /** Process-scoped coroutine host: lives as long as the process, survives any activity going away. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One auto-open per ICCOA session; reset whenever the link leaves Connected. */
    private var projectionAutoOpenRequested = false

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this



        // Enable vector drawable support on older Android versions
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        if (ConscryptInitializer.isNeededForTls12()) {
            ConscryptInitializer.initialize()
        }

        // Root support
        component.suExecutor.register()

        if (isUserUnlocked()) {
            val settings = Settings(this) // Create a Settings instance
            AppLog.init(settings, this) // Initialize AppLog with settings for conditional logging

            // Sync auto-start settings to device-protected storage so that
            // BootCompleteReceiver, UsbAttachedActivity, and AutoStartReceiver
            // can read them during locked boot (before user unlock)
            Settings.syncAutoStartOnBootToDeviceStorage(this, settings.autoStartOnBoot)
            Settings.syncAutoStartOnUsbToDeviceStorage(this, settings.autoStartOnUsb)
            Settings.syncAutoStartOnWifiToDeviceStorage(this, settings.autoStartOnWifi)
            Settings.syncAutoStartWifiSsidToDeviceStorage(this, settings.autoStartWifiSsid)
            Settings.syncAutoStartBtMacsToDeviceStorage(this, settings.autoStartBluetoothDeviceMacs)

            // Apply app theme (runs the live manager when dynamic, or when a saved place
            // can force the app theme even over a static base).
            AppThemeManager.reapply(this, settings)
        } else {
            AppLog.init(null, this) // Initialize with default logging if locked
            AppLog.w("App started in Direct Boot mode (locked). Settings access deferred.")
        }

        if (ConscryptInitializer.isAvailable()) {
            AppLog.i("Conscrypt security provider is active")
        } else if (ConscryptInitializer.isNeededForTls12()) {
            AppLog.w("Conscrypt not available - TLS 1.2 may not work on this device")
        }

        AppLog.d( "native library dir ${applicationInfo.nativeLibraryDir}")

        File(applicationInfo.nativeLibraryDir).listFiles()?.forEach { file ->
            AppLog.d( "   ${file.name}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(defaultChannel, "Headunit Service", NotificationManager.IMPORTANCE_LOW)
            serviceChannel.description = "Persistent service notification"
            serviceChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(serviceChannel)

            val mediaChannel = NotificationChannel(BackgroundNotification.mediaChannel, "Media Playback", NotificationManager.IMPORTANCE_LOW)
            mediaChannel.setSound(null, null)
            mediaChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(mediaChannel)

            AapNavigation.createNotificationChannel(this)

            val bootChannel = NotificationChannel(bootStartChannel, "Boot Auto-Start", NotificationManager.IMPORTANCE_HIGH)
            bootChannel.description = "Shown once after boot to open the app"
            bootChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(bootChannel)
        }

        // Register the main broadcast receiver safely for Android 14+ using ContextCompat
        ContextCompat.registerReceiver(this, AapBroadcastReceiver(), AapBroadcastReceiver.filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        observeIcocaForAutoProjection()
    }

    /**
     * Opens the projection screen whenever a phone session comes up, no matter which activity is
     * on screen (or whether the app is on screen at all).
     *
     * The ICCOA SDK casts into a Surface that only exists while [IcocaProjectionActivity] is alive.
     * A phone that connects with no projection on screen therefore has nowhere to draw:
     * `startCast()` never succeeds, the phone gives up ~15 s later, and the car re-advertises —
     * an endless connect/fail loop that only stops when the user manually reopens the app. That is
     * exactly the `startCast 重试用尽` churn in the 0.1.3 diagnostics report.
     *
     * [MainActivity]'s own observer only runs while it is STARTED, and it also owns the
     * "already opened this session" flag. When the app is backgrounded (or the standby screen is
     * behind another app), that collector is suspended and never sees the disconnect, so the flag
     * stays `true` and the next connection is never shown either. Running the same guard here, on
     * a process-scoped coroutine that is never suspended, guarantees the flag is reset the moment
     * the link leaves Connected — and that a new session is opened even from the background.
     */
    private fun observeIcocaForAutoProjection() {
        appScope.launch {
            component.icocaBridge.state.collect { state ->
                if (state.phase == IcocaLinkState.Phase.Connected) {
                    // Attempt once per session. `isForeground` stops a re-emission from stacking a
                    // second projection on top of the one already on screen.
                    if (!projectionAutoOpenRequested && !IcocaProjectionActivity.isForeground) {
                        projectionAutoOpenRequested = true
                        AppLog.i("ICCOA: phone connected — opening projection (process scope)")
                        val intent = IcocaProjectionActivity.intent(this@App)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            startActivity(intent)
                        } catch (t: Throwable) {
                            // A background activity launch can be refused on some builds. Clear the
                            // throttle so the next Connected emission (or MainActivity.onResume)
                            // gets another chance once the app is actually in the foreground.
                            projectionAutoOpenRequested = false
                            AppLog.w("ICCOA: 自动打开投屏页失败（后台启动被拒？）: $t")
                        }
                    }
                } else {
                    projectionAutoOpenRequested = false
                    // Never let a subscription that was suspended across a disconnect leave the
                    // shared "opened this session" flag stuck true.
                    MainActivity.icocaProjectionOpened = false
                }
            }
        }
    }

    private fun isUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } else {
            true
        }
    }

    companion object {
        const val defaultChannel = "headunit_service_v2"
        const val bootStartChannel = "headunit_boot_start"
        val appStartTime = SystemClock.elapsedRealtime()
        var appThemeManager: AppThemeManager? = null
        var isPiPActive = false

        @Volatile
        var instance: App? = null
            private set


        fun get(context: Context): App {
            return context.applicationContext as App
        }
        fun provide(context: Context): AppComponent {
            return get(context).component
        }
    }
}
