package com.pavlova

import android.app.Application
import android.util.Log
import com.pavlova.analysis.ManipulationDetector
import com.pavlova.data.AppSettings
import com.pavlova.data.ScreenshotStore
import com.pavlova.debug.DebugCaptureStore
import com.pavlova.ml.ContentAnalyzer

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

        // Initialize user-facing settings (verbose/demo mode toggle, etc.)
        AppSettings.initialize(this)

        // Initialize per-item screenshot thumbnail storage (gated by verbose mode at call sites)
        ScreenshotStore.initialize(this)

        // Initialize debug capture store (toggleable; off by default in release)
        DebugCaptureStore.initialize(this)

        // Initialize NLP models (RoBERTa, SBERT — loads from assets, falls back to heuristics)
        ContentAnalyzer.initialize(this)

        // Initialize analysis engines (LSTM, Isolation Forest, SBERT embeddings)
        ManipulationDetector.initialize(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        ContentAnalyzer.destroy()
        ManipulationDetector.destroy()
    }
}
