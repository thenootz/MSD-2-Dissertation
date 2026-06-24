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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pavlova.analysis.SessionTrendAnalyzer
import com.pavlova.analysis.ShapExplainer
import com.pavlova.data.AppSettings
import com.pavlova.data.database.PavlovaDatabase
import com.pavlova.data.model.FeedSession
import com.pavlova.data.model.SessionMetrics
import com.pavlova.permissions.PermissionManager
import com.pavlova.services.CaptureState
import com.pavlova.services.ScreenCaptureService
import com.pavlova.ui.DebugCapturesScreen
import com.pavlova.ui.SessionDetailScreen
import com.pavlova.ui.SettingsScreen
import com.pavlova.ui.components.MetricChip
import com.pavlova.ui.theme.PavlovaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SESSION_FMT = SimpleDateFormat("MMM d, HH:mm", Locale.US)

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
            startScreenCaptureService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op — will re-check on next action */ }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default on Android 15 (SDK 35) is edge-to-edge with no inset
        // handling. Explicitly opt-in here for clarity, then let each screen
        // consume WindowInsets.safeDrawing so content (incl. the dashboard
        // header) clears the status bar and gesture nav area.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        permissionManager = PermissionManager(this)
        val db = PavlovaDatabase.getDatabase(this)

        setContent {
            PavlovaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "dashboard") {
                        composable("dashboard") {
                            DashboardScreen(
                                onStartAudit = { requestPermissionsAndStart() },
                                onStopAudit = { stopScreenCaptureService() },
                                onOpenSession = { id -> navController.navigate("session/$id") },
                                onOpenSettings = { navController.navigate("settings") },
                                permissionManager = permissionManager,
                                db = db
                            )
                        }
                        composable("session/{sessionId}") { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
                            SessionDetailScreen(
                                sessionId = sessionId,
                                db = db,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onOpenDebugCaptures = { navController.navigate("debug") }
                            )
                        }
                        composable("debug") {
                            DebugCapturesScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !permissionManager.hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Note: SYSTEM_ALERT_WINDOW (overlay) permission is intentionally NOT
        // requested here. It's only needed for the optional on-screen wellbeing
        // alerts (OverlayManager), which the user enables and grants separately
        // from the Settings screen — capture works fine without it.
        mediaProjectionLauncher.launch(permissionManager.getMediaProjectionIntent())
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
        Log.d(TAG, "Audit service started")
    }

    private fun stopScreenCaptureService() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        Log.d(TAG, "Audit service stopped")
    }
}

@Composable
fun DashboardScreen(
    onStartAudit: () -> Unit,
    onStopAudit: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    permissionManager: PermissionManager,
    db: PavlovaDatabase
) {
    // Capture state is owned by the service so the button reflects reality even
    // when the screen share is ended from the system UI.
    val isAuditing by CaptureState.isCapturing.collectAsState()
    val verboseMode by AppSettings.verboseModeFlow.collectAsState()
    val alertsEnabled by AppSettings.alertsEnabledFlow.collectAsState()

    val sessions by db.feedSessionDao().getAllSessions()
        .collectAsState(initial = emptyList())
    val recentMetrics by db.sessionMetricsDao().getRecentMetrics(10)
        .collectAsState(initial = emptyList())
    var trendReport by remember { mutableStateOf<SessionTrendAnalyzer.Report?>(null) }

    // Cross-session trend analysis (longitudinal): duration slope, frequency
    // slope, and creator concentration growth.
    LaunchedEffect(sessions) {
        val completed = sessions.filter { it.endTime != null }
            .sortedBy { it.startTime }
            .takeLast(20)
        trendReport = if (completed.size < 2) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val itemsBySession = completed.associate { s ->
                    s.id to db.contentItemDao().getItemsForSessionSync(s.id)
                }
                SessionTrendAnalyzer.analyze(completed, itemsBySession)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pavlova",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Feed Recommendation Auditor",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Audit Control
        item {
            Button(
                onClick = {
                    // State flips via CaptureState once the service starts/stops.
                    if (isAuditing) onStopAudit() else onStartAudit()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = if (isAuditing) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.buttonColors()
            ) {
                Text(
                    text = if (isAuditing) "Stop Auditing" else "Start Feed Audit",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // Live capture indicator
        if (isAuditing) {
            item {
                Text(
                    "● Auditing in progress — switch to your feed app and scroll",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Permission Status
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    PermissionRow("Notifications", permissionManager.hasNotificationPermission())
                    // Overlay permission only matters when wellbeing alerts are on.
                    if (alertsEnabled) {
                        PermissionRow("Alerts overlay", permissionManager.hasOverlayPermission())
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Verbose / demo mode: ${if (verboseMode) "ON" else "OFF"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (alertsEnabled && !permissionManager.hasOverlayPermission()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Alerts are on but can't be shown — grant " +
                                "\"display over other apps\" in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // First-run onboarding (only when there's no data yet)
        if (sessions.isEmpty() && recentMetrics.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Get started",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "1. Press Start Feed Audit and allow screen capture.\n" +
                                "2. Open TikTok, Instagram, or YouTube and scroll as usual.\n" +
                                "3. Return here to see how your feed is shaping you — " +
                                "all analysis runs on-device, nothing leaves your phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Latest Metrics Summary
        if (recentMetrics.isNotEmpty()) {
            item {
                Text(
                    "Latest Analysis",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            item {
                val latest = recentMetrics.first()
                MetricsCard(latest)
            }
        }

        // Longitudinal behavior trends across sessions
        trendReport?.let { report ->
            item {
                Text(
                    "Behavior Trends (Across Sessions)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            item {
                SessionTrendCard(report)
            }
        }

        // Session History
        if (sessions.isNotEmpty()) {
            item {
                Text(
                    "Session History (${sessions.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(sessions.take(20)) { session ->
                val metrics = recentMetrics.find { it.sessionId == session.id }
                SessionCard(session, metrics, onClick = { onOpenSession(session.id) })
            }
        }
    }
}

@Composable
fun MetricsCard(metrics: SessionMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                metrics.manipulationScore > 0.7f -> MaterialTheme.colorScheme.errorContainer
                metrics.manipulationScore > 0.4f -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Feed Influence", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${(metrics.manipulationScore * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = metrics.manipulationScore,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricChip("Topics: ${metrics.uniqueTopics}")
                MetricChip("Creators: ${metrics.uniqueCreators}")
                MetricChip("Entropy: ${"%.2f".format(metrics.topicEntropy)}")
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricChip("Sentiment: ${"%.2f".format(metrics.avgSentiment)}")
                MetricChip("Toxicity: ${"%.2f".format(metrics.avgToxicity)}")
                MetricChip("Escalation: ${"%.2f".format(metrics.emotionalEscalation)}")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                ShapExplainer.generateSummary(metrics),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SessionTrendCard(report: SessionTrendAnalyzer.Report) {
    val trendColor = when (report.addictionLabel) {
        "High" -> MaterialTheme.colorScheme.errorContainer
        "Moderate" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val durationArrow = if (report.durationSlopeMinPerSession >= 0f) "↑" else "↓"
    val frequencyArrow = if (report.gapSlopeHoursPerSession < 0f) "↑" else "↓"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = trendColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Addiction trend signal", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${report.addictionLabel} ${(report.addictionScore * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = report.addictionScore,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricChip("Sessions: ${report.sessionCount}")
                MetricChip("Avg duration: ${"%.1f".format(report.avgDurationMinutes)}m")
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricChip("Duration trend: $durationArrow ${"%.2f".format(report.durationSlopeMinPerSession)}m/session")
                MetricChip("Frequency trend: $frequencyArrow ${"%.2f".format(kotlin.math.abs(report.gapSlopeHoursPerSession))}h/session")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Session duration change: ${"%.1f".format(report.durationIncreasePct)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (report.growingCreators.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Creators increasingly watched:",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                report.growingCreators.forEach { growth ->
                    Text(
                        "• @${growth.creatorId} (+${"%.1f".format(growth.delta * 100)}pp)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SessionCard(session: FeedSession, metrics: SessionMetrics?, onClick: () -> Unit) {
    val containerColor = when {
        metrics == null -> MaterialTheme.colorScheme.surface
        metrics.manipulationScore > 0.7f -> MaterialTheme.colorScheme.errorContainer
        metrics.manipulationScore > 0.4f -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(session.platform.uppercase(), style = MaterialTheme.typography.labelMedium)
                Text(
                    SESSION_FMT.format(Date(session.startTime)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${session.totalItems} items",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (metrics != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Influence: ${(metrics.manipulationScore * 100).toInt()}% | " +
                    "Topics: ${metrics.uniqueTopics} | " +
                    "Sentiment: ${"%.2f".format(metrics.avgSentiment)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "View details →",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
fun PermissionRow(name: String, isGranted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (isGranted) "✓" else "✗",
            color = if (isGranted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
}
