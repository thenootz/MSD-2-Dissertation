package com.pavlova

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pavlova.permissions.PermissionManager
import com.pavlova.services.ScreenCaptureService
import com.pavlova.ui.theme.PavlovaTheme

class MainActivity : ComponentActivity() {

    private lateinit var permissionManager: PermissionManager
    
    private var mediaProjectionResultCode: Int = Activity.RESULT_CANCELED
    private var mediaProjectionResultData: Intent? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            mediaProjectionResultCode = result.resultCode
            mediaProjectionResultData = result.data
            Log.d(TAG, "MediaProjection permission granted")
            startScreenCaptureService()
        } else {
            Log.w(TAG, "MediaProjection permission denied")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
        } else {
            Log.w(TAG, "Notification permission denied")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        permissionManager = PermissionManager(this)
        
        setContent {
            PavlovaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onStartProtection = { requestPermissionsAndStart() },
                        onStopProtection = { stopScreenCaptureService() },
                        permissionManager = permissionManager
                    )
                }
            }
        }
    }

    private fun requestPermissionsAndStart() {
        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!permissionManager.hasNotificationPermission()) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        
        // Check overlay permission
        if (!permissionManager.hasOverlayPermission()) {
            permissionManager.requestOverlayPermission(this)
            return
        }
        
        // Request MediaProjection
        val intent = permissionManager.getMediaProjectionIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun startScreenCaptureService() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, mediaProjectionResultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, mediaProjectionResultData)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        Log.d(TAG, "Screen capture service started")
    }

    private fun stopScreenCaptureService() {
        val intent = Intent(this, ScreenCaptureService::class.java)
        stopService(intent)
        Log.d(TAG, "Screen capture service stopped")
    }
}

@Composable
fun MainScreen(
    onStartProtection: () -> Unit,
    onStopProtection: () -> Unit,
    permissionManager: PermissionManager
) {
    var isProtectionActive by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Pavlova",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Privacy-First Screen Protection",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .padding(end = 8.dp)
                    ) {
                        Surface(
                            color = if (isProtectionActive) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {}
                    }
                    Text(
                        text = if (isProtectionActive) "Active" else "Inactive",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Main Action Button
        Button(
            onClick = {
                if (isProtectionActive) {
                    onStopProtection()
                    isProtectionActive = false
                } else {
                    onStartProtection()
                    isProtectionActive = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(56.dp)
        ) {
            Text(
                text = if (isProtectionActive) "Stop Protection" else "Start Protection",
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Permission Status
        PermissionStatusSection(permissionManager)
    }
}

@Composable
fun PermissionStatusSection(permissionManager: PermissionManager) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Permissions",
            style = MaterialTheme.typography.titleMedium
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        PermissionItem(
            name = "Overlay",
            isGranted = permissionManager.hasOverlayPermission()
        )
        
        PermissionItem(
            name = "Notification",
            isGranted = permissionManager.hasNotificationPermission()
        )
    }
}

@Composable
fun PermissionItem(name: String, isGranted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = if (isGranted) "✓ Granted" else "✗ Required",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isGranted) 
                MaterialTheme.colorScheme.primary 
            else 
                MaterialTheme.colorScheme.error
        )
    }
}
