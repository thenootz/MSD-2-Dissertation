package com.pavlova.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pavlova.analysis.ShapExplainer
import com.pavlova.data.database.PavlovaDatabase
import com.pavlova.data.model.ContentItem
import com.pavlova.data.model.FeedSession
import com.pavlova.data.model.SessionMetrics
import com.pavlova.ui.components.MetricChip
import com.pavlova.ui.components.ThumbnailImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIMESTAMP_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(sessionId: String, db: PavlovaDatabase, onBack: () -> Unit) {
    val items by db.contentItemDao().getItemsForSession(sessionId)
        .collectAsState(initial = emptyList())
    val metrics by db.sessionMetricsDao().getLatestMetricsForSession(sessionId)
        .collectAsState(initial = null)

    var session by remember { mutableStateOf<FeedSession?>(null) }
    LaunchedEffect(sessionId) {
        session = db.feedSessionDao().getSession(sessionId)
    }

    var selectedItem by remember { mutableStateOf<ContentItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.platform?.uppercase() ?: "Session") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SessionSummaryHeader(session, metrics, items)
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        "No items captured for this session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                // Group captured frames by the video they belong to.
                val byVideo = items.groupBy { it.videoIndex }
                    .toSortedMap()

                item {
                    Text(
                        "${byVideo.size} videos · ${items.size} captured frames",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                byVideo.forEach { (videoIndex, videoItems) ->
                    item(key = "video_header_$videoIndex") {
                        VideoGroupHeader(videoIndex, videoItems)
                    }
                    items(videoItems, key = { it.id }) { contentItem ->
                        ContentItemRow(contentItem, onClick = { selectedItem = contentItem })
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    selectedItem?.let { item ->
        ContentItemDetailDialog(item = item, onDismiss = { selectedItem = null })
    }
}

@Composable
private fun VideoGroupHeader(videoIndex: Int, videoItems: List<ContentItem>) {
    // Most common non-null creator within this video group.
    val creator = videoItems.mapNotNull { it.creatorId?.trim()?.takeIf(String::isNotBlank) }
        .groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Video ${videoIndex + 1}" + (creator?.let { " · @$it" } ?: ""),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "${videoItems.size} ${if (videoItems.size == 1) "frame" else "frames"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SessionSummaryHeader(session: FeedSession?, metrics: SessionMetrics?, items: List<ContentItem>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(session?.platform?.uppercase() ?: "—", style = MaterialTheme.typography.titleSmall)
                Text("${items.size} items", style = MaterialTheme.typography.titleSmall)
            }
            if (metrics != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Feed Influence", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${(metrics.manipulationScore * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    ShapExplainer.generateSummary(metrics),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val topCreators = items.mapNotNull { it.creatorId }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .take(3)
            if (topCreators.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Top creators", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    topCreators.forEach { (handle, count) ->
                        MetricChip("@$handle ($count)")
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentItemRow(item: ContentItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            ThumbnailImage(item.screenshotPath, sizeDp = 72)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.creatorId?.let { "@$it" } ?: "Unknown creator",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        TIMESTAMP_FMT.format(Date(item.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    item.textContent?.takeIf { it.isNotBlank() } ?: "(no text extracted)",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val topics = item.topicLabels?.let { parseTopics(it) } ?: emptyList()
                if (topics.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        topics.take(3).forEach { MetricChip(it) }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.sentimentScore?.let { MetricChip("Sentiment: ${"%.2f".format(it)}") }
                    item.toxicityScore?.let { MetricChip("Toxicity: ${"%.2f".format(it)}") }
                    item.persuasionScore?.let { MetricChip("Persuasion: ${"%.2f".format(it)}") }
                }
            }
        }
    }
}

@Composable
private fun ContentItemDetailDialog(item: ContentItem, onDismiss: () -> Unit) {
    val bitmap = remember(item.screenshotPath) {
        item.screenshotPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(item.creatorId?.let { "@$it" } ?: "Item #${item.position}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text("OCR Text", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.textContent?.takeIf { it.isNotBlank() } ?: "(no text extracted)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                val topics = item.topicLabels?.let { parseTopics(it) } ?: emptyList()
                if (topics.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Topics: ${topics.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Sentiment: ${item.sentimentScore?.let { "%.2f".format(it) } ?: "—"} · " +
                        "Emotion: ${item.emotionLabel ?: "—"} · " +
                        "Toxicity: ${item.toxicityScore?.let { "%.2f".format(it) } ?: "—"} · " +
                        "Persuasion: ${item.persuasionScore?.let { "%.2f".format(it) } ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

private fun parseTopics(json: String): List<String> {
    return json.trim().removeSurrounding("[", "]")
        .split(",")
        .map { it.trim().removeSurrounding("\"") }
        .filter { it.isNotBlank() }
}
