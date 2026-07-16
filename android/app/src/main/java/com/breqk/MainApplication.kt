package com.Break

/**
 * MainApplication
 * ----------------
 * Wires React Native host and registers custom native packages.
 * Keeps developer support aligned with BuildConfig, and loads RN runtime at startup.
 */

import android.app.Application
import android.webkit.CookieManager
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.defaults.DefaultReactNativeHost
import com.Break.BuildConfig
import com.Break.bridge.BreakReactPackage
import com.Break.bridge.VPNModule
import com.Break.bridge.SettingsModule
import com.Break.mode.ModeManager
import com.Break.monitor.AppUsageMonitor
import com.Break.prefs.BreakPrefs

class MainApplication : Application(), ReactApplication {

    override val reactNativeHost: ReactNativeHost = object : DefaultReactNativeHost(this) {
        override fun getPackages(): List<ReactPackage> {
            val packages = PackageList(this).packages.toMutableList()
            packages.add(BreakReactPackage())
            return packages
        }   

        // override fun getJSMainModuleName(): String = "index"

        override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

        // override val isNewArchEnabled: Boolean = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
        // override val isHermesEnabled: Boolean = BuildConfig.IS_HERMES_ENABLED
    }

    override val reactHost: ReactHost
        get() = getDefaultReactHost(applicationContext, reactNativeHost)

    override fun onCreate() {
        super.onCreate()
        CookieManager.getInstance().setAcceptCookie(true)

        // Migrate legacy blocked_apps → per-app policies (runs once, no-op after)
        BreakPrefs.migrateIfNeeded(this)
        // Create default modes (Study + Bedtime) on first run
        BreakPrefs.createDefaultModesIfNeeded(this)
        // Register alarms for any modes with schedules
        ModeManager.reregisterAllAlarms(this)
        // Safety net: activate/deactivate a scheduled mode if we're currently
        // inside/outside its window (alarms are lost on force-stop, and may
        // have been missed while the app was dead)
        ModeManager.applyCurrentScheduleState(this)

        loadReactNative(this)
    }
}
