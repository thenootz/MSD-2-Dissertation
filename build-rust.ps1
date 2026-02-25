# Build script for Pavlova
# Run this to build the Rust library for Android

Write-Host "Building Pavlova Rust Library..." -ForegroundColor Cyan

# Check if cargo-ndk is installed
if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
    Write-Host "Error: cargo-ndk not found. Install it with:" -ForegroundColor Red
    Write-Host "  cargo install cargo-ndk" -ForegroundColor Yellow
    exit 1
}

# Check if Rust Android targets are installed
$targets = @("aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android")
foreach ($target in $targets) {
    $installed = rustup target list --installed | Select-String $target
    if (-not $installed) {
        Write-Host "Installing target: $target" -ForegroundColor Yellow
        rustup target add $target
    }
}

# Build Rust library
Set-Location rust

Write-Host "`nBuilding for Android (release mode)..." -ForegroundColor Green

cargo ndk `
    --target aarch64-linux-android `
    --target armv7-linux-androideabi `
    --target x86_64-linux-android `
    --platform 26 `
    -- build --release

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n✅ Rust library built successfully!" -ForegroundColor Green
    
    # Copy to jniLibs
    Write-Host "`nCopying native libraries to jniLibs..." -ForegroundColor Cyan
    
    $jniLibsPath = "..\android\app\src\main\jniLibs"
    
    New-Item -ItemType Directory -Force -Path "$jniLibsPath\arm64-v8a" | Out-Null
    New-Item -ItemType Directory -Force -Path "$jniLibsPath\armeabi-v7a" | Out-Null
    New-Item -ItemType Directory -Force -Path "$jniLibsPath\x86_64" | Out-Null
    
    Copy-Item "target\aarch64-linux-android\release\libpavlova_core.so" "$jniLibsPath\arm64-v8a\"
    Copy-Item "target\armv7-linux-androideabi\release\libpavlova_core.so" "$jniLibsPath\armeabi-v7a\"
    Copy-Item "target\x86_64-linux-android\release\libpavlova_core.so" "$jniLibsPath\x86_64\"
    
    Write-Host "✅ Native libraries copied!" -ForegroundColor Green
    
    Write-Host "`n📦 Library sizes:" -ForegroundColor Cyan
    Get-ChildItem "$jniLibsPath" -Recurse -Filter "*.so" | ForEach-Object {
        $size = [math]::Round($_.Length / 1MB, 2)
        Write-Host "  $($_.FullName): $size MB"
    }
    
    Write-Host "`n🎉 Build complete! Now open the Android project in Android Studio." -ForegroundColor Green
    
} else {
    Write-Host "`n❌ Rust build failed!" -ForegroundColor Red
    exit 1
}

Set-Location ..
