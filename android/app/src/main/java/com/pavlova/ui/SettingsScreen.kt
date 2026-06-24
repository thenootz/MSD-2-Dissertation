package com.pavlova.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pavlova.data.AppSettings
import com.pavlova.data.ScreenshotStore
import com.pavlova.data.database.PavlovaDatabase
import com.pavlova.debug.DebugCaptureStore
import com.pavlova.services.ScrollSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDebugCaptures: () -> Unit
) {
    val context = LocalContext.current
    val verbose by AppSettings.verboseModeFlow.collectAsState()
    val alertsEnabled by AppSettings.alertsEnabledFlow.collectAsState()
    var debugEnabled by remember { mutableStateOf(DebugCaptureStore.isEnabled()) }
    var pendingDisableVerbose by remember { mutableStateOf(false) }
    var pendingClearSessions by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    // Re-read overlay permission when returning from the system settings screen.
    var canDrawOverlays by remember { mutableStateOf(hasOverlayPermission(context)) }
    // Drive the status from the system "enabled" setting (authoritative) rather
    // than the runtime connection flag, which lags behind / resets on the
    // process restart that happens when returning from the Accessibility screen.
    var scrollDetectorOn by remember {
        mutableStateOf(ScrollSignal.isEnabledInSettings(context) || ScrollSignal.isActive())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasOverlayPermission(context)
                if (granted && !canDrawOverlays) {
                    scope.launch { snackbarHost.showSnackbar("Overlay permission granted — alerts are active") }
                }
                canDrawOverlays = granted
                scrollDetectorOn =
                    ScrollSignal.isEnabledInSettings(context) || ScrollSignal.isActive()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Privacy & capture",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            SettingsToggleCard(
                title = "Verbose / demo mode",
                description = "Keep a downscaled JPEG thumbnail of every " +
                    "captured frame so the session detail screen can show " +
                    "previews. Disabled by default — only OCR text and " +
                    "computed scores are stored.",
                checked = verbose,
                onCheckedChange = { newValue ->
                    if (!newValue && verbose) {
                        pendingDisableVerbose = true
                    } else {
                        AppSettings.setVerboseMode(newValue)
                    }
                }
            )

            SettingsToggleCard(
                title = "Debug capture (developer)",
                description = "Save each captured frame + its OCR text to " +
                    "<app files>/debug_captures for pipeline debugging. " +
                    "Independent from verbose mode. Off in release by default.",
                checked = debugEnabled,
                onCheckedChange = {
                    DebugCaptureStore.setEnabled(it)
                    debugEnabled = it
                }
            )

            if (debugEnabled) {
                TextButton(onClick = onOpenDebugCaptures) {
                    Text("Open debug captures →")
                }
            }

            Divider()

            Text(
                "On-screen alerts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            SettingsToggleCard(
                title = "Wellbeing alerts",
                description = "Show a banner over the social-media app when " +
                    "your feed crosses thresholds for toxicity, feed-shaping, " +
                    "or isolation (echo chamber). Requires draw-over-other-apps.",
                checked = alertsEnabled,
                onCheckedChange = { AppSettings.setAlertsEnabled(it) }
            )

            if (alertsEnabled && !canDrawOverlays) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Permission required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Alerts can't be shown until you allow Pavlova to " +
                                "draw over other apps.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { requestOverlayPermission(context) }) {
                            Text("Grant permission")
                        }
                    }
                }
            }

            Divider()

            Text(
                "Video detection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Scroll detection (Accessibility)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (scrollDetectorOn) "Active" else "Off",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (scrollDetectorOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Lets Pavlova know exactly when you swipe to the next " +
                            "video, so captured frames are grouped per video more " +
                            "accurately. Reads only scroll events — no taps, text, " +
                            "or screen content. Optional; detection still works " +
                            "without it (visually).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { openAccessibilitySettings(context) }) {
                        Text(if (scrollDetectorOn) "Manage in Accessibility" else "Enable in Accessibility")
                    }
                }
            }

            Divider()

            Text("Storage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Stored screenshots are written to app-private storage and " +
                    "removed when the app is uninstalled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = {
                scope.launch {
                    val n = withContext(Dispatchers.IO) { ScreenshotStore.clearAll() }
                    snackbarHost.showSnackbar("Removed $n stored thumbnails")
                }
            }) { Text("Clear stored screenshots") }

            Divider()

            Text("Sessions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Permanently deletes every recorded audit session — feed " +
                    "items, computed metrics, and any saved thumbnails. " +
                    "This cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { pendingClearSessions = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) { Text("Delete all sessions") }
        }
    }

    if (pendingDisableVerbose) {
        AlertDialog(
            onDismissRequest = { pendingDisableVerbose = false },
            title = { Text("Disable verbose mode?") },
            text = {
                Text(
                    "New captures will no longer keep a thumbnail. Do you " +
                        "also want to delete the thumbnails already saved on disk?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.setVerboseMode(false)
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { ScreenshotStore.clearAll() }
                        snackbarHost.showSnackbar("Verbose mode off · removed $n thumbnails")
                    }
                    pendingDisableVerbose = false
                }) { Text("Disable & delete") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        AppSettings.setVerboseMode(false)
                        pendingDisableVerbose = false
                    }) { Text("Disable, keep files") }
                    TextButton(onClick = { pendingDisableVerbose = false }) { Text("Cancel") }
                }
            }
        )
    }

    if (pendingClearSessions) {
        AlertDialog(
            onDismissRequest = { pendingClearSessions = false },
            title = { Text("Delete all sessions?") },
            text = {
                Text(
                    "This removes every audit session, its content items, " +
                        "computed metrics, and any saved thumbnails. " +
                        "There is no undo."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingClearSessions = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            clearAllSessionData(context)
                        }
                        snackbarHost.showSnackbar(
                            "Deleted ${result.sessions} sessions, " +
                                "${result.items} items, ${result.thumbnails} thumbnails"
                        )
                    }
                }) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearSessions = false }) { Text("Cancel") }
            }
        )
    }
}

private data class ClearResult(val sessions: Int, val items: Int, val thumbnails: Int)

private fun hasOverlayPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true

private fun requestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/**
 * Wipe every recorded session from the encrypted Room database plus any
 * on-disk thumbnails. Foreign-key cascades from `feed_sessions` should
 * remove `content_items` and `session_metrics` automatically, but we
 * call each DAO's `deleteAll` explicitly so the operation is robust to
 * future schema changes and the counts are accurate.
 */
private suspend fun clearAllSessionData(context: Context): ClearResult {
    val db = PavlovaDatabase.getDatabase(context)
    val sessionDao = db.feedSessionDao()
    val itemDao = db.contentItemDao()
    val metricsDao = db.sessionMetricsDao()

    val sessionCount = sessionDao.getSessionCount()
    val itemCount = itemDao.getTotalCount()

    metricsDao.deleteAll()
    itemDao.deleteAll()
    sessionDao.deleteAll()

    val thumbnails = ScreenshotStore.clearAll()
    return ClearResult(sessions = sessionCount, items = itemCount, thumbnails = thumbnails)
}

@Composable
private fun SettingsToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
