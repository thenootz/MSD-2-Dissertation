package com.pavlova

import android.app.Application
import android.util.Log
import com.pavlova.analysis.ManipulationDetector
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
