# Clean build artifacts

Write-Host "Cleaning Pavlova build artifacts..." -ForegroundColor Cyan

# Clean Rust
if (Test-Path "rust\target") {
    Write-Host "Cleaning Rust target directory..." -ForegroundColor Yellow
    Remove-Item -Recurse -Force "rust\target"
}

# Clean Android
if (Test-Path "android\app\build") {
    Write-Host "Cleaning Android build directory..." -ForegroundColor Yellow
    Remove-Item -Recurse -Force "android\app\build"
}

if (Test-Path "android\build") {
    Remove-Item -Recurse -Force "android\build"
}

if (Test-Path "android\.gradle") {
    Remove-Item -Recurse -Force "android\.gradle"
}

# Clean jniLibs
if (Test-Path "android\app\src\main\jniLibs") {
    Write-Host "Cleaning jniLibs..." -ForegroundColor Yellow
    Remove-Item -Recurse -Force "android\app\src\main\jniLibs"
}

Write-Host "`n✅ Clean complete!" -ForegroundColor Green
