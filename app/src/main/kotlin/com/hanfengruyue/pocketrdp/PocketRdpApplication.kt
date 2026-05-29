package com.hanfengruyue.pocketrdp

import android.app.Application
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PocketRdpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PocketLogger.install(this)
        val pkg = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        PocketLogger.i(TAG, "PocketRDP app start (pkg=$packageName, ver=${pkg?.versionName})")
    }

    companion object {
        private const val TAG = "App"
    }
}
