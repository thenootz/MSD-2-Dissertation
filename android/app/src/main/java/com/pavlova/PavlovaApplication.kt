package com.pavlova

import android.app.Application
import android.util.Log

class PavlovaApplication : Application() {

    companion object {
        private const val TAG = "PavlovaApplication"
        lateinit var instance: PavlovaApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Pavlova application starting...")
    }
}
