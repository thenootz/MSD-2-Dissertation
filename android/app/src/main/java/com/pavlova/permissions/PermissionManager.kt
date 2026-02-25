package com.pavlova.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

class PermissionManager(private val context: Context) {

    companion object {
        const val REQUEST_CODE_MEDIA_PROJECTION = 1001
        const val REQUEST_CODE_OVERLAY = 1002
        const val REQUEST_CODE_NOTIFICATION = 1003
    }

    /**
     * Check if overlay permission is granted
     */
    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Request overlay permission
     */
    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            activity.startActivityForResult(intent, REQUEST_CODE_OVERLAY)
        }
    }

    /**
     * Check if notification permission is granted (Android 13+)
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Get MediaProjection permission intent
     */
    fun getMediaProjectionIntent(): Intent {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
            as MediaProjectionManager
        return projectionManager.createScreenCaptureIntent()
    }

    /**
     * Check if Accessibility Service is enabled
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        
        if (accessibilityEnabled == 1) {
            val services = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return services?.contains(context.packageName) == true
        }
        
        return false
    }

    /**
     * Open Accessibility Settings
     */
    fun openAccessibilitySettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        activity.startActivity(intent)
    }

    /**
     * Check if Notification Listener is enabled
     */
    fun isNotificationListenerEnabled(): Boolean {
        val listeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return listeners?.contains(context.packageName) == true
    }

    /**
     * Open Notification Listener Settings
     */
    fun openNotificationListenerSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        activity.startActivity(intent)
    }

    /**
     * Check all required permissions
     */
    fun hasAllRequiredPermissions(): Boolean {
        return hasOverlayPermission() && hasNotificationPermission()
    }
}
