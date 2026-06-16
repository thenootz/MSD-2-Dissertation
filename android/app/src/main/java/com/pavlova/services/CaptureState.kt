package com.pavlova.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for whether a screen-capture / audit session is
 * currently running.
 *
 * [ScreenCaptureService] is the only writer (it flips this on when capture
 * starts and off when the service is destroyed — including when the user ends
 * the screen share from the system UI). The dashboard observes
 * [isCapturing] so its Start/Stop button always reflects reality, even when the
 * session ends outside the app.
 */
object CaptureState {

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    fun setCapturing(value: Boolean) {
        _isCapturing.value = value
    }
}
