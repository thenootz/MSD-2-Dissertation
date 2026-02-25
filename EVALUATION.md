# Pavlova - Evaluation & Testing Methodology

## Evaluation Framework for Master's Thesis

This document outlines the comprehensive evaluation strategy for Pavlova, covering performance, accuracy, usability, privacy, and battery impact.

---

## 1. Performance Evaluation

### 1.1 Metrics to Measure

| Metric | Target | Measurement Method |
|--------|--------|-------------------|
| Frame capture latency | < 20ms | Timestamp difference |
| Image preprocessing | < 15ms | Rust timer |
| ML inference time | < 30ms | Rust timer |
| Blur/pixelation rendering | < 20ms | Rust timer |
| End-to-end latency | < 100ms | Full pipeline timer |
| Memory usage (peak) | < 150MB | Android Profiler |
| Memory usage (average) | < 80MB | Android Profiler |
| CPU usage (active) | < 25% | Android Profiler |
| Frame rate | 8-12 FPS | Frame counter |

### 1.2 Test Devices

**Device Tiers** (for thesis evaluation):

| Tier | Device | SoC | RAM | Android Version |
|------|--------|-----|-----|----------------|
| High-End | Pixel 8 Pro | Tensor G3 | 12GB | Android 15 |
| Mid-Range | Samsung Galaxy A54 | Exynos 1380 | 8GB | Android 14 |
| Low-End | Motorola Moto G Power | Snapdragon 680 | 4GB | Android 13 |

**Rationale**: Cover different hardware capabilities, Android versions, and manufacturer skins.

---

### 1.3 Performance Benchmarking Code

**File**: `android/app/src/androidTest/java/com/pavlova/PerformanceBenchmark.kt`

```kotlin
@RunWith(AndroidJUnit4::class)
class PerformanceBenchmarkTest {
    
    @get:Rule
    val benchmarkRule = BenchmarkRule()
    
    private lateinit var rustBridge: RustMLBridge
    private lateinit var testFrame: ByteArray
    
    @Before
    fun setup() {
        rustBridge = RustMLBridge(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        
        // Create test frame (1920x1080 RGBA)
        testFrame = ByteArray(1920 * 1080 * 4)
        // Fill with test pattern
        for (i in testFrame.indices step 4) {
            testFrame[i] = (i % 256).toByte()     // R
            testFrame[i+1] = ((i/2) % 256).toByte() // G
            testFrame[i+2] = ((i/3) % 256).toByte() // B
            testFrame[i+3] = 255.toByte()          // A
        }
    }
    
    @Test
    fun benchmarkMLInference() {
        benchmarkRule.measureRepeated {
            rustBridge.classifyFrame(testFrame, 1920, 1080)
        }
    }
    
    @Test
    fun benchmarkGaussianBlur() {
        benchmarkRule.measureRepeated {
            rustBridge.generateBlur(testFrame, 1920, 1080, 0.8f)
        }
    }
    
    @Test
    fun benchmarkPixelation() {
        benchmarkRule.measureRepeated {
            rustBridge.generatePixelation(testFrame, 1920, 1080, 16)
        }
    }
    
    @Test
    fun benchmarkEndToEndPipeline() {
        benchmarkRule.measureRepeated {
            // Simulate full processing pipeline
            val classification = rustBridge.classifyFrame(testFrame, 1920, 1080)
            
            if (!classification.isSafe) {
                rustBridge.generateBlur(testFrame, 1920, 1080, 0.8f)
            }
        }
    }
    
    @After
    fun tearDown() {
        rustBridge.cleanup()
    }
}
```

---

### 1.4 Automated Performance Data Collection

**File**: `scripts/collect_performance_data.py`

```python
#!/usr/bin/env python3
"""
Automated performance data collection script for thesis evaluation.
Connects to Android device via ADB and collects metrics over time.
"""

import subprocess
import time
import csv
import json
from datetime import datetime

class PerformanceCollector:
    def __init__(self, package_name="com.pavlova"):
        self.package_name = package_name
        self.data = []
    
    def get_memory_info(self):
        """Get memory usage via dumpsys meminfo"""
        cmd = f"adb shell dumpsys meminfo {self.package_name}"
        output = subprocess.check_output(cmd, shell=True).decode()
        
        # Parse output for Java Heap, Native Heap, etc.
        total_pss = 0
        for line in output.split('\n'):
            if 'TOTAL PSS' in line:
                total_pss = int(line.split()[1])
                break
        
        return total_pss  # in KB
    
    def get_cpu_usage(self):
        """Get CPU usage via top command"""
        cmd = f"adb shell top -n 1 -b | grep {self.package_name}"
        try:
            output = subprocess.check_output(cmd, shell=True).decode()
            # Parse CPU% from output
            cpu_percent = float(output.split()[8].replace('%', ''))
            return cpu_percent
        except:
            return 0.0
    
    def get_battery_drain(self):
        """Get battery stats"""
        cmd = "adb shell dumpsys battery"
        output = subprocess.check_output(cmd, shell=True).decode()
        
        level = 0
        for line in output.split('\n'):
            if 'level:' in line:
                level = int(line.split(':')[1].strip())
        
        return level
    
    def collect_sample(self):
        """Collect one sample of all metrics"""
        return {
            'timestamp': datetime.now().isoformat(),
            'memory_mb': self.get_memory_info() / 1024,
            'cpu_percent': self.get_cpu_usage(),
            'battery_level': self.get_battery_drain()
        }
    
    def collect_continuous(self, duration_minutes=30, interval_seconds=5):
        """Collect data over time"""
        print(f"Collecting data for {duration_minutes} minutes...")
        
        start_time = time.time()
        end_time = start_time + (duration_minutes * 60)
        
        while time.time() < end_time:
            sample = self.collect_sample()
            self.data.append(sample)
            print(f"Sample: {sample}")
            
            time.sleep(interval_seconds)
    
    def save_to_csv(self, filename):
        """Save collected data to CSV"""
        if not self.data:
            return
        
        with open(filename, 'w', newline='') as f:
            writer = csv.DictWriter(f, fieldnames=self.data[0].keys())
            writer.writeheader()
            writer.writerows(self.data)
        
        print(f"Data saved to {filename}")
    
    def generate_statistics(self):
        """Calculate summary statistics"""
        if not self.data:
            return {}
        
        memory_values = [d['memory_mb'] for d in self.data]
        cpu_values = [d['cpu_percent'] for d in self.data]
        
        return {
            'avg_memory_mb': sum(memory_values) / len(memory_values),
            'max_memory_mb': max(memory_values),
            'avg_cpu_percent': sum(cpu_values) / len(cpu_values),
            'max_cpu_percent': max(cpu_values),
            'samples_collected': len(self.data)
        }

if __name__ == "__main__":
    collector = PerformanceCollector()
    
    # Collect for 30 minutes
    collector.collect_continuous(duration_minutes=30, interval_seconds=5)
    
    # Save results
    collector.save_to_csv('performance_data.csv')
    
    # Print statistics
    stats = collector.generate_statistics()
    print("\n=== Statistics ===")
    print(json.dumps(stats, indent=2))
```

---

## 2. ML Model Accuracy Evaluation

### 2.1 Test Dataset Creation

**Approach**: Create labeled test set with diverse content types

```python
# Dataset structure
test_dataset/
├── safe/
│   ├── news_01.png
│   ├── education_01.png
│   ├── shopping_01.png
│   └── ... (500 images)
├── unsafe/
│   ├── adult_01.png
│   ├── violence_01.png
│   ├── disturbing_01.png
│   └── ... (500 images)
└── edge_cases/
    ├── medical_content.png
    ├── artistic_nudity.png
    ├── video_game_violence.png
    └── ... (200 images)
```

**Labeling Guidelines**:
- Safe: News, educational, shopping, social media (appropriate), games (non-violent)
- Unsafe: Adult content, violence, gore, hate speech imagery
- Edge cases: Context-dependent content requiring human judgment

---

### 2.2 Accuracy Metrics

```kotlin
data class AccuracyMetrics(
    val accuracy: Float,
    val precision: Float,
    val recall: Float,
    val f1Score: Float,
    val confusionMatrix: ConfusionMatrix
)

data class ConfusionMatrix(
    val truePositives: Int,   // Correctly identified unsafe
    val trueNegatives: Int,   // Correctly identified safe
    val falsePositives: Int,  // Safe flagged as unsafe
    val falseNegatives: Int   // Unsafe flagged as safe
) {
    fun calculateMetrics(): AccuracyMetrics {
        val total = truePositives + trueNegatives + falsePositives + falseNegatives
        
        val accuracy = (truePositives + trueNegatives).toFloat() / total
        val precision = truePositives.toFloat() / (truePositives + falsePositives)
        val recall = truePositives.toFloat() / (truePositives + falseNegatives)
        val f1 = 2 * (precision * recall) / (precision + recall)
        
        return AccuracyMetrics(
            accuracy = accuracy,
            precision = precision,
            recall = recall,
            f1Score = f1,
            confusionMatrix = this
        )
    }
}
```

**Target Metrics for Thesis**:
- Accuracy: > 90%
- Precision: > 85% (minimize false positives for UX)
- Recall: > 95% (minimize false negatives for safety)
- F1-Score: > 0.90

---

### 2.3 Model Comparison

Compare multiple ML backends:

| Model | Framework | Size | Inference Time | Accuracy |
|-------|-----------|------|----------------|----------|
| MobileNetV2-NSFW | TFLite | 14MB | ~25ms | TBD |
| EfficientNet-Lite | TFLite | 8MB | ~30ms | TBD |
| Custom CNN | Tract (Rust) | 5MB | ~20ms | TBD |
| ResNet18-ONNX | ONNX Runtime | 45MB | ~50ms | TBD |

---

## 3. Battery Impact Analysis

### 3.1 Methodology

**Test Scenario**: 
1. Fully charge device to 100%
2. Run Pavlova for 2 hours continuously
3. Measure battery drainage

**Control**: 
1. Run normal usage (no Pavlova) for 2 hours
2. Measure battery drainage
3. Calculate delta

**Code**:
```kotlin
class BatteryMonitor(private val context: Context) {
    
    fun startMonitoring() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)
    }
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percentage = (level / scale.toFloat()) * 100
            
            logBatteryLevel(percentage)
        }
    }
    
    private fun logBatteryLevel(percentage: Float) {
        // Log to database for analysis
        val event = BatteryEvent(
            timestamp = System.currentTimeMillis(),
            percentage = percentage
        )
        database.insertBatteryEvent(event)
    }
}
```

**Expected Results**:
- Without Pavlova: ~10-15% drain over 2 hours (normal usage)
- With Pavlova: Target < 20% additional drain
- Acceptable: 30-35% total drain over 2 hours

---

### 3.2 Battery Optimization Strategies

```kotlin
class AdaptiveFrameRateManager {
    
    private var currentFps = 10
    private val minFps = 5
    private val maxFps = 15
    
    fun adjustFrameRate(batteryPercent: Int, screenActivity: ScreenActivity) {
        currentFps = when {
            // Low battery: reduce frame rate
            batteryPercent < 20 -> minFps
            
            // Static screen: reduce frame rate
            screenActivity == ScreenActivity.STATIC -> minFps
            
            // Active scrolling/video: increase frame rate
            screenActivity == ScreenActivity.SCROLLING -> maxFps
            
            // Normal: balanced
            else -> 10
        }
        
        updateCaptureInterval(currentFps)
    }
}
```

---

## 4. User Experience Evaluation

### 4.1 Latency Perception Study

**Research Question**: At what latency do users notice the overlay delay?

**Method**:
1. Recruit 20 participants
2. Show content with varying overlay latencies (50ms, 100ms, 200ms, 500ms)
3. Ask participants to rate perceived responsiveness on 5-point Likert scale

**Analysis**: Determine acceptable latency threshold for thesis discussion.

---

### 4.2 False Positive Tolerance

**Research Question**: How do users react to false positives?

**Method**:
1. Show participants safe content incorrectly blurred
2. Measure frustration level
3. Measure override action frequency

**Metrics**:
- Override rate: % of false positives manually overridden
- Task abandonment: % of users stopping due to incorrect filtering
- Satisfaction score: Post-test survey

---

### 4.3 Usability Testing

**Tasks**:
1. Install and set up Pavlova
2. Grant required permissions
3. Activate protection
4. Browse safe content
5. Encounter unsafe content (blurred)
6. Override a false positive
7. View privacy dashboard
8. Deactivate protection

**Metrics**:
- Task completion rate
- Time to complete setup
- Number of errors
- SUS (System Usability Scale) score

**Target**: SUS score > 70 (above average usability)

---

## 5. Privacy Compliance Testing

### 5.1 Data Collection Audit

**Automated Test**:
```kotlin
@Test
fun verifyNoScreenshotsStored() {
    // Run Pavlova for 1 hour
    runPavlovaForDuration(1.hours)
    
    // Check file system
    val imageFiles = context.filesDir.walkTopDown()
        .filter { it.extension in listOf("png", "jpg", "jpeg", "bmp") }
        .toList()
    
    // Assert no image files exist
    assertEquals(0, imageFiles.size, "Found unexpected image files")
}

@Test
fun verifyMinimalDataLogging() {
    // Run for 1 hour
    runPavlovaForDuration(1.hours)
    
    // Check database size
    val dbFile = context.getDatabasePath("pavlova.db")
    val dbSizeMB = dbFile.length() / (1024 * 1024)
    
    // Should be < 1MB even after extended use
    assertTrue(dbSizeMB < 1, "Database unexpectedly large: ${dbSizeMB}MB")
}
```

---

### 5.2 Permission Usage Justification

**For Thesis Discussion**:

| Permission | Justification | Alternatives Considered | Privacy Impact |
|------------|---------------|------------------------|----------------|
| SYSTEM_ALERT_WINDOW | Required for overlay | None (core feature) | Low - no data access |
| MediaProjection | Required for screen capture | None | High - mitigated by local processing |
| Accessibility (optional) | App context awareness | Intent filters (insufficient) | Medium - limited to package names |
| Notification Listener (optional) | Media metadata | Media Controller API (limited) | Low - media only |

---

## 6. Limitations & Threat Analysis

### 6.1 Technical Limitations

**For Thesis "Limitations" Section**:

1. **Frame Rate Constraints**
   - Max 10-15 FPS processing
   - Fast content changes may be missed
   - Mitigation: Temporal smoothing, user warnings

2. **ML Model Limitations**
   - Cannot understand context perfectly
   - Language/culture bias in training data
   - Edge cases: medical, artistic content

3. **Overlay Bypass**
   - Root access can disable overlay
   - System-level apps may draw over Pavlova
   - Developer mode can grant overlay exceptions

4. **Battery Impact**
   - Continuous processing drains battery
   - Not suitable for all-day usage
   - Mitigation: Adaptive FPS, schedule-based activation

5. **Android Version Dependency**
   - Android 14+ per-session consent reduces convenience
   - Different behavior across Android versions
   - Manufacturer customizations affect reliability

---

### 6.2 Security Threat Model

**Threat**: Malicious actor disables Pavlova

**Attack Vectors**:
- Uninstall app
- Revoke permissions
- Replace ML model
- Root device and inject code

**Mitigations**:
- Device Owner mode (enterprise)
- Model integrity checks
- Tamper detection
- None for determined user (acknowledge in thesis)

---

## 7. Comparison with Alternatives

### 7.1 Comparative Analysis

**For Thesis "Related Work" / "Discussion"**:

| Solution | Approach | Pros | Cons |
|----------|----------|------|------|
| **Pavlova** | On-device ML + Overlay | Privacy, offline, real-time | Battery, FPS limited |
| **DNS Filtering** | Network-level blocking | Low overhead | Only blocks domains, not content |
| **Browser Extensions** | Content Script filtering | High accuracy | Browser-only, no native apps |
| **Cloud APIs** | Send screenshots to cloud | Powerful ML models | Privacy violation, requires internet |
| **iOS Screen Time** | App/category blocking | Native integration | No content-level filtering |
| **Parental Control Apps** | App blocking + monitoring | Established market | Often invasive, cloud sync |

---

## 8. Evaluation Timeline

**Recommended Schedule for Thesis**:

| Week | Activity | Deliverable |
|------|----------|-------------|
| 1-2 | Collect baseline performance data | Performance benchmarks |
| 3-4 | Accuracy evaluation with test dataset | Confusion matrix, metrics |
| 5-6 | Battery impact testing | Battery drain analysis |
| 7-8 | User experience study | Usability test results |
| 9 | Privacy compliance audit | Audit report |
| 10 | Comparative analysis | Comparison table |
| 11-12 | Write evaluation chapter | Thesis chapter draft |

---

## 9. Expected Results (Hypotheses)

**For Thesis Introduction/Methodology**:

### H1: Performance
*Pavlova will achieve end-to-end latency < 100ms on mid-range devices, enabling real-time content filtering.*

### H2: Accuracy
*The ML model will achieve > 90% accuracy on diverse content, with precision > 85% and recall > 95%.*

### H3: Battery Impact
*Battery overhead will be < 10% per hour of active use, making the system practical for daily use.*

### H4: Usability
*Users will successfully set up and use Pavlova with SUS score > 70, indicating acceptable usability.*

### H5: Privacy
*The system will process content locally with < 1MB of stored data per month, demonstrating privacy-preserving design.*

---

## 10. Thesis Evaluation Chapter Structure

```markdown
# Chapter 5: Evaluation

## 5.1 Evaluation Methodology
- Test devices
- Datasets
- Metrics
- Experimental setup

## 5.2 Performance Evaluation
- Latency measurements
- Memory and CPU usage
- Frame rate analysis
- Comparison across device tiers

## 5.3 Accuracy Evaluation
- Test dataset description
- Classification results
- Confusion matrix
- Error analysis
- Edge case discussion

## 5.4 Battery Impact Analysis
- Methodology
- Results vs. baseline
- Impact of adaptive FPS
- Recommendations for users

## 5.5 User Experience Study
- Participant demographics
- Task completion rates
- SUS scores
- Qualitative feedback

## 5.6 Privacy Compliance
- Data collection audit results
- Permission usage justification
- Comparison with privacy-invasive alternatives

## 5.7 Discussion
- Hypotheses validation
- Comparison with related work
- Practical applicability
- Limitations encountered
```

---

**Next**: See THESIS_OUTLINE.md for complete academic structure.
