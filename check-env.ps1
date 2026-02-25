# Development environment verification script

Write-Host "Pavlova Development Environment Check" -ForegroundColor Cyan
Write-Host "====================================`n" -ForegroundColor Cyan

$allGood = $true

# Check Rust
Write-Host "Checking Rust..." -ForegroundColor Yellow
if (Get-Command rustc -ErrorAction SilentlyContinue) {
    $rustVersion = rustc --version
    Write-Host "  ✅ $rustVersion" -ForegroundColor Green
} else {
    Write-Host "  ❌ Rust not found. Install from https://rustup.rs" -ForegroundColor Red
    $allGood = $false
}

# Check cargo-ndk
Write-Host "`nChecking cargo-ndk..." -ForegroundColor Yellow
if (Get-Command cargo-ndk -ErrorAction SilentlyContinue) {
    Write-Host "  ✅ cargo-ndk installed" -ForegroundColor Green
} else {
    Write-Host "  ❌ cargo-ndk not found. Run: cargo install cargo-ndk" -ForegroundColor Red
    $allGood = $false
}

# Check Android targets
Write-Host "`nChecking Rust Android targets..." -ForegroundColor Yellow
$targets = @("aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android")
foreach ($target in $targets) {
    $installed = rustup target list --installed | Select-String $target
    if ($installed) {
        Write-Host "  ✅ $target" -ForegroundColor Green
    } else {
        Write-Host "  ❌ $target not installed. Run: rustup target add $target" -ForegroundColor Red
        $allGood = $false
    }
}

# Check Android Studio / Java
Write-Host "`nChecking Java..." -ForegroundColor Yellow
if (Get-Command java -ErrorAction SilentlyContinue) {
    $javaVersion = java -version 2>&1 | Select-Object -First 1
    Write-Host "  ✅ $javaVersion" -ForegroundColor Green
} else {
    Write-Host "  ❌ Java not found. Install Java 17+ or Android Studio" -ForegroundColor Red
    $allGood = $false
}

# Check for Android SDK
Write-Host "`nChecking Android SDK..." -ForegroundColor Yellow
$androidHome = $env:ANDROID_HOME
if ($androidHome -and (Test-Path $androidHome)) {
    Write-Host "  ✅ ANDROID_HOME: $androidHome" -ForegroundColor Green
} else {
    Write-Host "  ⚠️  ANDROID_HOME not set. Make sure Android Studio is installed." -ForegroundColor Yellow
}

# Check NDK
Write-Host "`nChecking Android NDK..." -ForegroundColor Yellow
if ($androidHome) {
    $ndkPath = Join-Path $androidHome "ndk"
    if (Test-Path $ndkPath) {
        $ndkVersions = Get-ChildItem $ndkPath -Directory | Select-Object -First 1
        if ($ndkVersions) {
            Write-Host "  ✅ NDK found: $($ndkVersions.Name)" -ForegroundColor Green
        } else {
            Write-Host "  ❌ NDK not installed. Install via Android Studio SDK Manager" -ForegroundColor Red
            $allGood = $false
        }
    } else {
        Write-Host "  ❌ NDK directory not found" -ForegroundColor Red
        $allGood = $false
    }
}

# Summary
Write-Host "`n====================================`n" -ForegroundColor Cyan
if ($allGood) {
    Write-Host "✅ All prerequisites are installed! You're ready to build." -ForegroundColor Green
    Write-Host "`nNext steps:" -ForegroundColor Cyan
    Write-Host "  1. Run: .\build-rust.ps1" -ForegroundColor White
    Write-Host "  2. Open android/ folder in Android Studio" -ForegroundColor White
    Write-Host "  3. Click Run to build and install" -ForegroundColor White
} else {
    Write-Host "❌ Some prerequisites are missing. Please install them and run this check again." -ForegroundColor Red
}
