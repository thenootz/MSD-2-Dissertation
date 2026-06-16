package com.pavlova.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide user preferences.
 *
 * Currently exposes:
 *   - [verboseMode]: when true, the capture pipeline keeps a downscaled JPEG
 *     thumbnail of every captured frame (via [ScreenshotStore]) so the session
 *     detail screen can render previews. When false (default), no raw screen
 *     content is persisted — only OCR text and computed scores — matching the
 *     documented privacy posture.
 *
 * Backed by a private SharedPreferences file. Reactive callers can observe
 * [verboseModeFlow] from Compose via collectAsState.
 */
object AppSettings {

    private const val TAG = "AppSettings"
    private const val PREFS_NAME = "pavlova_app_settings"
    private const val KEY_VERBOSE_MODE = "verbose_mode"
    private const val KEY_ALERTS_ENABLED = "alerts_enabled"

    @Volatile private var appContext: Context? = null
    private val _verboseModeFlow = MutableStateFlow(false)
    val verboseModeFlow: StateFlow<Boolean> = _verboseModeFlow.asStateFlow()

    private val _alertsEnabledFlow = MutableStateFlow(true)
    /** On-screen alert banners (toxicity / feed-influence / isolation). */
    val alertsEnabledFlow: StateFlow<Boolean> = _alertsEnabledFlow.asStateFlow()

    /** Initialise from [com.pavlova.PavlovaApplication.onCreate]. Safe to call once. */
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val stored = prefs()?.getBoolean(KEY_VERBOSE_MODE, false) ?: false
        _verboseModeFlow.value = stored
        _alertsEnabledFlow.value = prefs()?.getBoolean(KEY_ALERTS_ENABLED, true) ?: true
        Log.d(TAG, "Initialised. verboseMode=$stored, alertsEnabled=${_alertsEnabledFlow.value}")
    }

    /** Current value of the verbose/demo toggle. Safe to call before [initialize] (returns false). */
    val verboseMode: Boolean
        get() = _verboseModeFlow.value

    /** Update the verbose/demo toggle and persist it. */
    fun setVerboseMode(value: Boolean) {
        _verboseModeFlow.value = value
        prefs()?.edit()?.putBoolean(KEY_VERBOSE_MODE, value)?.apply()
        Log.d(TAG, "verboseMode=$value")
    }

    /** Whether on-screen alert banners are enabled. Safe before [initialize] (returns true). */
    val alertsEnabled: Boolean
        get() = _alertsEnabledFlow.value

    /** Update the on-screen alerts toggle and persist it. */
    fun setAlertsEnabled(value: Boolean) {
        _alertsEnabledFlow.value = value
        prefs()?.edit()?.putBoolean(KEY_ALERTS_ENABLED, value)?.apply()
        Log.d(TAG, "alertsEnabled=$value")
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
