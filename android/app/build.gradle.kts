plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.pavlova"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pavlova"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // Specify where to find native libraries
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Security & Encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Preferences DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // WorkManager (for background tasks)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    
    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Task to build Rust library before compiling Java/Kotlin
tasks.register<Exec>("buildRustLibrary") {
    workingDir = file("../../rust")
    
    // Build for all ABIs
    commandLine("cargo", "ndk",
        "--target", "aarch64-linux-android",
        "--target", "armv7-linux-androideabi", 
        "--target", "x86_64-linux-android",
        "--platform", "26",
        "--", "build", "--release"
    )
}

// Copy Rust libraries to jniLibs
tasks.register<Copy>("copyRustLibs") {
    dependsOn("buildRustLibrary")
    
    from("../../rust/target/aarch64-linux-android/release/libpavlova_core.so") {
        into("arm64-v8a")
    }
    from("../../rust/target/armv7-linux-androideabi/release/libpavlova_core.so") {
        into("armeabi-v7a")
    }
    from("../../rust/target/x86_64-linux-android/release/libpavlova_core.so") {
        into("x86_64")
    }
    
    into("src/main/jniLibs")
}

// Hook into the build process
tasks.whenTaskAdded {
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn("copyRustLibs")
    }
}
