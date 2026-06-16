package com.pavlova.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pavlova.data.AppSettings
import com.pavlova.data.ScreenshotStore
import com.pavlova.data.database.PavlovaDatabase
import com.pavlova.debug.DebugCaptureStore
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
    var debugEnabled by remember { mutableStateOf(DebugCaptureStore.isEnabled()) }
    var pendingDisableVerbose by remember { mutableStateOf(false) }
    var pendingClearSessions by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

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
