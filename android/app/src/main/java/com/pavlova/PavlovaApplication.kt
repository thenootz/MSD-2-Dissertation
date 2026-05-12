package com.pavlova

import android.app.Application
import android.util.Log
import com.pavlova.ml.TFLiteMLBridge

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
        
        // Initialize Kotlin-native TFLite bridge
        try {
            TFLiteMLBridge.initialize(this)
            Log.d(TAG, "TFLite ML Bridge initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite ML Bridge", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        
        // Cleanup resources
        try {
            TFLiteMLBridge.destroy()
            Log.d(TAG, "TFLite ML Bridge destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying TFLite ML Bridge", e)
        }
    }
}
