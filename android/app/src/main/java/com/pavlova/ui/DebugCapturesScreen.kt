package com.pavlova.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pavlova.debug.DebugCapture
import com.pavlova.debug.DebugCaptureStore
import com.pavlova.ui.components.ThumbnailImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FILENAME_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugCapturesScreen(onBack: () -> Unit) {
    var captures by remember { mutableStateOf<List<DebugCapture>>(emptyList()) }
    var enabled by remember { mutableStateOf(DebugCaptureStore.isEnabled()) }
    var selected by remember { mutableStateOf<DebugCapture?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        captures = withContext(Dispatchers.IO) { DebugCaptureStore.listCaptures() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Captures") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(onClick = { refreshTick++ }) { Text("Refresh") }
                    TextButton(onClick = {
                        DebugCaptureStore.clearAll()
                        refreshTick++
                    }) { Text("Clear") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Save screenshots + OCR", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Folder: ${DebugCaptureStore.getDir()?.absolutePath ?: "n/a"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        DebugCaptureStore.setEnabled(it)
                        enabled = it
                    }
                )
            }
            Divider()

            if (captures.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        if (enabled) "No captures yet. Start an audit to record some."
                        else "Capture is disabled. Toggle it on to start recording.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Text("${captures.size} captures", style = MaterialTheme.typography.labelMedium) }
                    items(captures, key = { it.imagePath }) { capture ->
                        CaptureRow(capture) { selected = capture }
                    }
                }
            }
        }
    }

    selected?.let { capture ->
        CaptureDetailDialog(capture = capture, onDismiss = { selected = null })
    }
}

@Composable
private fun CaptureRow(capture: DebugCapture, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            ThumbnailImage(capture.imagePath, sizeDp = 64)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    FILENAME_FMT.format(Date(capture.timestamp)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${capture.sizeBytes / 1024} KB" +
                        if (capture.textPath != null) " · OCR ✓" else " · no OCR",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClick) { Text("View") }
        }
    }
}

@Composable
private fun CaptureDetailDialog(capture: DebugCapture, onDismiss: () -> Unit) {
    var text by remember(capture.textPath) { mutableStateOf("") }
    LaunchedEffect(capture.textPath) {
        text = withContext(Dispatchers.IO) { DebugCaptureStore.readText(capture.textPath) }
    }

    val bitmap = remember(capture.imagePath) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(capture.imagePath, opts)
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(FILENAME_FMT.format(Date(capture.timestamp))) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("OCR Text", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = text.ifBlank { "(no text extracted)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Image: ${capture.imagePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (capture.textPath != null) {
                    Text(
                        "Text:  ${capture.textPath}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
