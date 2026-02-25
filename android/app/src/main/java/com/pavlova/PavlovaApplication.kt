package com.pavlova

import android.app.Application
import android.util.Log
import com.pavlova.ml.RustMLBridge

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
        
        // Initialize Rust ML bridge
        try {
            RustMLBridge.initialize(this)
            Log.d(TAG, "Rust ML Bridge initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Rust ML Bridge", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        
        // Cleanup Rust resources
        try {
            RustMLBridge.destroy()
            Log.d(TAG, "Rust ML Bridge destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying Rust ML Bridge", e)
        }
    }
}
