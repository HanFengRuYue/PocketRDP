package com.hanfengruyue.pocketrdp

import android.app.Application
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PocketRdpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PocketLogger.install(this)
        PocketLogger.i(TAG, "PocketRDP app start (pkg=$packageName, ver=${BuildConfig.VERSION_NAME})")
    }

    companion object {
        private const val TAG = "App"
    }
}
