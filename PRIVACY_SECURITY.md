# Pavlova - Privacy & Security Guidelines

## Privacy-First Architecture: Design Principles

Pavlova is designed with **privacy by design** and **privacy by default** principles:

1. **Local-Only Processing**: All ML inference happens on-device
2. **No Screenshot Storage**: Frames are processed in memory and discarded
3. **Minimal Logging**: Only timestamps and categories, no content
4. **User Consent**: Explicit permission for every capability
5. **Transparency**: Clear communication about data access

---

## Data Collection & Retention Policy

### What Pavlova DOES Collect

```kotlin
// Privacy-preserving event log
@Entity(tableName = "filter_events")
data class FilterEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,                    // When event occurred
    val category: String,                   // "safe", "unsafe", "adult_content", etc.
    val action: String,                     // "blur", "pixelate", "allow", "block"
    val confidence: Float,                  // ML confidence score
    val appPackage: String? = null,         // Optional: foreground app (if accessibility enabled)
    val duration: Long? = null              // Optional: how long overlay was shown
)
```

**Storage Duration**: 
- Events retained for 30 days by default
- User can configure retention period (7-90 days)
- Manual export option for research purposes

**Total Storage**: < 1MB for 30 days of continuous use

---

### What Pavlova DOES NOT Collect

❌ **Screen Content**: No screenshots, no pixels, no OCR text  
❌ **User Actions**: No touch events, no keyboard input  
❌ **Accessibility Trees**: No UI hierarchy, no button labels  
❌ **Notification Content**: Only media metadata (song/podcast title)  
❌ **Personal Information**: No names, emails, phone numbers  
❌ **Device Identifiers**: No IMEI, Android ID collection  
❌ **Network Data**: No cloud sync, no telemetry uploads  

---

## Permission Model & User Consent

### Required Permissions

#### 1. Overlay Permission (SYSTEM_ALERT_WINDOW)

**Purpose**: Display blur/pixelation overlay over unsafe content

**Consent Flow**:
```kotlin
fun requestOverlayPermission(activity: Activity) {
    // Show educational dialog first
    MaterialAlertDialogBuilder(activity)
        .setTitle("Overlay Permission Needed")
        .setMessage("""
            Pavlova needs to display protection overlays when unsafe 
            content is detected. This permission allows the app to 
            draw over other apps.
            
            Note: Pavlova CANNOT see or interact with other apps' 
            content without separate MediaProjection permission.
        """.trimIndent())
        .setPositiveButton("Grant Permission") { _, _ ->
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivityForResult(intent, REQUEST_OVERLAY)
        }
        .setNegativeButton("Not Now", null)
        .show()
}
```

**User Control**: Can be revoked anytime in Android Settings

---

#### 2. MediaProjection Permission

**Purpose**: Capture screen frames for content analysis

**Android 14+ Behavior**: 
- **Per-session consent**: User must approve EACH capture session
- **Cannot run silently**: Persistent notification required
- **User stop anytime**: System provides stop button

**Consent Flow**:
```kotlin
fun requestMediaProjection(activity: Activity) {
    MaterialAlertDialogBuilder(activity)
        .setTitle("Screen Capture Permission")
        .setMessage("""
            To detect unsafe content, Pavlova needs to analyze 
            your screen in real-time:
            
            ✓ Processing happens entirely on your device
            ✓ No screenshots are saved
            ✓ Only safety classifications are logged
            ✓ You can stop capture anytime
            
            On Android 14+, you'll need to grant this permission 
            each time you activate protection.
        """.trimIndent())
        .setPositiveButton("Allow Screen Capture") { _, _ ->
            val mediaProjectionManager = activity.getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager
            
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            activity.startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
        }
        .setNegativeButton("Cancel", null)
        .show()
}
```

**Transparency Measures**:
- Show persistent "Screen is being captured" notification
- Display FPS and processing status
- Provide quick-stop action

---

### Optional Permissions (Opt-In Only)

#### 3. Accessibility Service (Optional)

**Purpose**: Identify foreground app for context-aware filtering

**What It Accesses**: 
- Package name of current app
- App label (e.g., "Chrome", "YouTube")

**What It Does NOT Access**:
- ❌ Text content on screen
- ❌ User touch events
- ❌ Input field contents
- ❌ UI element structure

**Consent Flow**:
```kotlin
fun explainAccessibilityService(activity: Activity) {
    MaterialAlertDialogBuilder(activity)
        .setTitle("Context-Aware Filtering (Optional)")
        .setMessage("""
            Enabling the Accessibility Service allows Pavlova to:
            
            ✓ Know which app is currently open
            ✓ Apply different filtering rules per app
            ✓ Exempt trusted apps from scanning
            
            Pavlova's Accessibility Service:
            ✗ DOES NOT read your screen content
            ✗ DOES NOT log your touch actions
            ✗ DOES NOT capture text fields
            
            This feature is entirely optional. Pavlova works 
            without it using global filtering rules.
        """.trimIndent())
        .setPositiveButton("Enable (Open Settings)") { _, _ ->
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            activity.startActivity(intent)
        }
        .setNeutralButton("Learn More") { _, _ ->
            showAccessibilityPrivacyPolicy(activity)
        }
        .setNegativeButton("No Thanks", null)
        .show()
}
```

**Implementation Guarantee**:
```kotlin
class AppContextService : AccessibilityService() {
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // ONLY read package name, nothing else
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            
            // DO NOT access:
            // - event.source (UI tree)
            // - event.text (content)
            // - event.contentDescription
            
            // ONLY send package name to filtering engine
            sendAppContextToEngine(packageName)
        }
    }
    
    override fun onInterrupt() {
        // Required override
    }
    
    // Explicitly disable surveillance capabilities
    override fun getAccessibilityServiceInfo(): AccessibilityServiceInfo {
        return AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = 0 // No flags that enable content access
            notificationTimeout = 100
        }
    }
}
```

---

#### 4. Notification Listener (Optional)

**Purpose**: Extract media metadata (song/podcast title) for context

**What It Accesses**:
- Media notifications only (filter by MediaSession)
- Song title, artist, album
- Podcast title, episode name

**What It Does NOT Access**:
- ❌ Message notifications
- ❌ Email content
- ❌ App notification text
- ❌ Notification actions

**Consent Flow**:
```kotlin
fun explainNotificationListener(activity: Activity) {
    MaterialAlertDialogBuilder(activity)
        .setTitle("Media Context (Optional)")
        .setMessage("""
            Enabling the Notification Listener allows Pavlova to:
            
            ✓ See what music/podcast you're playing
            ✓ Adjust filtering based on audio context
            ✓ Provide better protection during media consumption
            
            Pavlova's Notification Listener:
            ✓ ONLY reads media notifications (music, podcasts)
            ✗ DOES NOT read messages, emails, or app notifications
            ✗ DOES NOT log notification content
            
            This feature is entirely optional.
        """.trimIndent())
        .setPositiveButton("Enable (Open Settings)") { _, _ ->
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            activity.startActivity(intent)
        }
        .setNegativeButton("No Thanks", null)
        .show()
}
```

**Implementation Guarantee**:
```kotlin
class MediaContextService : NotificationListenerService() {
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        
        // ONLY process media notifications
        val mediaSession = notification.extras
            .getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            ?: return // Ignore non-media notifications
        
        // Extract ONLY safe metadata
        val title = notification.extras.getString(Notification.EXTRA_TITLE)
        val artist = notification.extras.getString(Notification.EXTRA_TEXT)
        
        // Send to context engine (no storage)
        sendMediaContextToEngine(MediaContext(
            title = title,
            artist = artist,
            packageName = sbn.packageName
        ))
    }
}
```

---

## User Privacy Dashboard

### In-App Privacy Controls

**File**: `android/app/src/main/java/com/pavlova/ui/PrivacyDashboard.kt`

```kotlin
@Composable
fun PrivacyDashboard() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Privacy Dashboard",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Data Collection Overview
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("What Data Pavlova Collects", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                PrivacyItem(
                    icon = Icons.Default.Check,
                    label = "Event timestamps",
                    description = "When filtering occurred"
                )
                PrivacyItem(
                    icon = Icons.Default.Check,
                    label = "Content categories",
                    description = "Safe/unsafe classification only"
                )
                PrivacyItem(
                    icon = Icons.Default.Close,
                    label = "Screen content",
                    description = "NO screenshots stored",
                    isProhibited = true
                )
                PrivacyItem(
                    icon = Icons.Default.Close,
                    label = "Personal data",
                    description = "NO names, emails, or messages",
                    isProhibited = true
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Statistics
        val eventCount = getEventCount()
        val storageSize = getStorageSize()
        
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Your Privacy Statistics", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Events Logged")
                        Text(
                            eventCount.toString(),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    Column {
                        Text("Storage Used")
                        Text(
                            formatBytes(storageSize),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = { exportAnonymizedData() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export Anonymous Data")
                }
                
                OutlinedButton(
                    onClick = { deleteAllData() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete All Data")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Active Permissions
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Active Permissions", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                PermissionStatus(
                    label = "Screen Overlay",
                    isGranted = isOverlayPermissionGranted(),
                    isRequired = true
                )
                PermissionStatus(
                    label = "Media Projection",
                    isGranted = isMediaProjectionActive(),
                    isRequired = true
                )
                PermissionStatus(
                    label = "App Context (Accessibility)",
                    isGranted = isAccessibilityEnabled(),
                    isRequired = false
                )
                PermissionStatus(
                    label = "Media Notifications",
                    isGranted = isNotificationListenerEnabled(),
                    isRequired = false
                )
            }
        }
    }
}
```

---

## Security Measures

### 1. ML Model Integrity

**Threat**: Malicious model replacement could disable filtering

**Mitigation**:
```kotlin
class ModelVerification {
    
    fun verifyModel(modelFile: File): Boolean {
        val expectedChecksum = getExpectedChecksum() // Hardcoded or signed
        val actualChecksum = calculateSHA256(modelFile)
        
        if (actualChecksum != expectedChecksum) {
            Log.e(TAG, "Model integrity check failed!")
            
            // Fall back to safe mode
            activateSafeMode()
            
            // Notify user
            showModelTamperAlert()
            
            return false
        }
        
        return true
    }
    
    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
```

---

### 2. Secure Data Storage

**Even though minimal data is stored, encrypt it**:

```kotlin
// Use EncryptedSharedPreferences
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val encryptedPrefs = EncryptedSharedPreferences.create(
    context,
    "pavlova_secure_prefs",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

// Use Room with SQLCipher for database
val passphrase = android.database.sqlite.SQLiteDatabase
    .getBytes(masterKey.toString().toCharArray())

val factory = SupportFactory(passphrase)

val db = Room.databaseBuilder(context, AppDatabase::class.java, "pavlova.db")
    .openHelperFactory(factory)
    .build()
```

---

### 3. Code Obfuscation

**ProGuard Rules** (`app/proguard-rules.pro`):

```proguard
# Keep Rust JNI methods
-keepclasseswithmembers class com.pavlova.ml.RustMLBridge {
    native <methods>;
}

# Obfuscate everything else
-repackageclasses 'pavlova'
-allowaccessmodification
-optimizations !code/simplification/arithmetic

# Keep data classes for Room
-keep class com.pavlova.data.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
```

---

### 4. Runtime Integrity Checks

```kotlin
class SecurityManager {
    
    fun performSecurityChecks(): SecurityStatus {
        val status = SecurityStatus()
        
        // Check for root/emulator (indicate in thesis as limitation)
        status.isRooted = isDeviceRooted()
        status.isEmulator = isRunningOnEmulator()
        
        // Verify app signature
        status.isSignatureValid = verifyAppSignature()
        
        // Check for debugger
        status.isDebuggerAttached = Debug.isDebuggerConnected()
        
        if (!status.isSecure()) {
            Log.w(TAG, "Security check failed: $status")
            // Inform user, potentially limit functionality
        }
        
        return status
    }
    
    private fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su"
        )
        return paths.any { File(it).exists() }
    }
}
```

---

## Privacy Policy (Simplified)

### For App Store / User Agreement

```
PAVLOVA PRIVACY POLICY

1. Data Collection
   - Pavlova logs only timestamps and content categories (safe/unsafe)
   - NO screenshots or screen content is stored
   - NO personal information is collected
   - NO data is sent to external servers

2. Processing Location
   - All content analysis happens locally on your device
   - Machine learning models run entirely offline
   - Internet permission used only for optional model updates

3. Optional Features
   - Accessibility Service: Only reads app package names
   - Notification Listener: Only reads media (music/podcast) metadata
   - Both features are opt-in and can be disabled anytime

4. Data Retention
   - Event logs kept for 30 days (configurable 7-90 days)
   - Users can export or delete data anytime
   - Uninstalling app removes all data

5. Third-Party Access
   - NO third-party SDKs with data collection
   - NO analytics or crash reporting services
   - NO advertising frameworks

6. Security
   - Local database encrypted with Android Keystore
   - ML models verified for integrity
   - Code obfuscated to prevent tampering

7. Research Use
   - Users can voluntarily export anonymized statistics
   - Exported data contains NO identifying information
   - Participation is entirely optional

8. Contact
   - Questions: [your-email]
   - Source code: [GitHub repo if open-source]

Last Updated: [Date]
```

---

## Ethical Considerations for Thesis

### Discussion Points

1. **Parental Control Ethics**
   - Tension between protection and privacy
   - Age-appropriate transparency
   - Consent of minors vs. parental authority

2. **False Positives**
   - Educational content (medical, artistic) may be flagged
   - Cultural context variations
   - User control and override mechanisms

3. **Surveillance Concerns**
   - Accessibility Service misuse potential
   - Clear limitations and transparency needed
   - Technical vs. policy enforcement

4. **Bias in ML Models**
   - Training data representativeness
   - Cultural and contextual biases
   - Continuous model evaluation needed

5. **Comparison to iOS**
   - Why this is impossible on iOS (sandboxing, API restrictions)
   - Android's tradeoffs: flexibility vs. security
   - Platform-specific privacy considerations

---

## Thesis Section: Privacy Analysis

### Recommended Structure

```markdown
## 5.3 Privacy and Security Analysis

### 5.3.1 Privacy-Preserving Design
- On-device processing architecture
- Minimal data collection rationale
- Comparison with cloud-based approaches

### 5.3.2 Threat Model
- Adversaries: Malicious apps, sophisticated users, external attackers
- Assets: User screen content, filtering logs, ML models
- Mitigations: Encryption, integrity checks, permission model

### 5.3.3 Privacy Evaluation
- Data flow analysis
- Permission usage justification
- User control mechanisms

### 5.3.4 Ethical Considerations
- Informed consent mechanisms
- Transparency measures
- Limitations and responsible disclosure

### 5.3.5 Compliance
- GDPR considerations (data minimization, right to deletion)
- COPPA for parental control use cases
- Android policy requirements
```

---

**Next**: See EVALUATION.md for testing methodology and performance analysis.
