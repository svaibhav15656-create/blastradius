# install.ps1 - One-step zero-configuration installer for blastradius on Windows.
# Run once from PowerShell: .\install.ps1
#
# Automatically handles everything:
#  1. Detects or auto-installs Java 17+ (via winget)
#  2. Detects or auto-installs Tesseract OCR (via winget)
#  3. Deploys pre-built blastradius JAR and global launchers
#  4. Configures PATH globally and in current session (no restart needed)

$ErrorActionPreference = "Stop"

$Root    = $PSScriptRoot
$JavaDir = if (Test-Path (Join-Path $Root "pom.xml")) { $Root } else { Join-Path $Root "blastradius-java" }
$Bin     = Join-Path $env:USERPROFILE ".blastradius\bin"
$JarName = "blastradius-0.1.0-SNAPSHOT.jar"

Write-Host ""
Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "         blastradius - One-Step Installer        " -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "Install folder : $Bin"
Write-Host ""

# ---- 1. Check or Auto-Install Java 17+ ------------------------------------
function Ensure-Java {
    Write-Host "[1/4] Checking Java environment..." -NoNewline
    $hasJava = $false
    try {
        $out = & java -version 2>&1
        if ($out -match '"(\d+)') {
            $ver = [int]$matches[1]
            if ($ver -ge 17) {
                Write-Host " Found Java $ver [OK]" -ForegroundColor Green
                return $true
            }
        }
    } catch {}

    Write-Host " Java 17+ not found." -ForegroundColor Yellow
    Write-Host "      Installing Eclipse Temurin OpenJDK 17 automatically via winget..." -ForegroundColor Yellow
    try {
        & winget install EclipseAdoptium.Temurin.17.JRE --silent --accept-package-agreements --accept-source-agreements
        Write-Host "      Java 17 installed successfully. [OK]" -ForegroundColor Green
        return $true
    } catch {
        Write-Warning "Could not auto-install Java. Please install Java 17+ from https://adoptium.net/"
        return $false
    }
}

# ---- 2. Check or Auto-Install Tesseract OCR -------------------------------
function Ensure-Tesseract {
    Write-Host "[2/4] Checking Tesseract OCR..." -NoNewline
    $tessDir = "C:\Program Files\Tesseract-OCR"
    if (Test-Path $tessDir) {
        Write-Host " Found at $tessDir [OK]" -ForegroundColor Green
        return $true
    }
    if (Get-Command tesseract.exe -ErrorAction SilentlyContinue) {
        Write-Host " Found on PATH [OK]" -ForegroundColor Green
        return $true
    }
    Write-Host " Tesseract not found." -ForegroundColor Yellow
    Write-Host "      Installing Tesseract OCR automatically via winget..." -ForegroundColor Yellow
    try {
        & winget install UB-Mannheim.TesseractOCR --silent --accept-package-agreements --accept-source-agreements
        Write-Host "      Tesseract OCR installed successfully. [OK]" -ForegroundColor Green
        return $true
    } catch {
        Write-Warning "Could not auto-install Tesseract. Please install from https://github.com/UB-Mannheim/tesseract/wiki"
        return $false
    }
}

Ensure-Java | Out-Null
Ensure-Tesseract | Out-Null

# ---- 3. Obtain Package JAR ----------------------------------------------
Write-Host "[3/4] Preparing blastradius package..." -NoNewline
$SourceJar = $null
$RootJar = Join-Path $Root $JarName
$TargetJar = Join-Path $JavaDir "target\$JarName"

if (Test-Path $RootJar) {
    $SourceJar = $RootJar
    Write-Host " Using pre-built package [OK]" -ForegroundColor Green
} elseif (Test-Path $TargetJar) {
    $SourceJar = $TargetJar
    Write-Host " Using compiled target package [OK]" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "      Building from source (one-time Maven package)..." -ForegroundColor Yellow
    $mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    $mvnExe = if ($mvn) { $mvn.Source } else { "C:\Program Files\apache-maven-3.9.14\bin\mvn.cmd" }
    Push-Location $JavaDir
    try {
        & $mvnExe -q package "-DskipTests=true"
    } finally {
        Pop-Location
    }
    $SourceJar = $TargetJar
    Write-Host "      Build complete. [OK]" -ForegroundColor Green
}

# ---- 4. Install Launchers & Add to PATH ---------------------------------
Write-Host "[4/4] Deploying commands to $Bin..." -NoNewline
New-Item -ItemType Directory -Force -Path $Bin | Out-Null

$DstJar = Join-Path $Bin $JarName
$DstCmd = Join-Path $Bin "blastradius.cmd"
$DstPs1 = Join-Path $Bin "blastradius.ps1"

Copy-Item -Force $SourceJar $DstJar

# Copy native PowerShell launcher
Copy-Item -Force (Join-Path $Root "blastradius.ps1") $DstPs1

# Write CMD batch launcher
$cmdContent = "@echo off`r`nsetlocal`r`nset ""JAR=%~dp0$JarName""`r`njava.exe ""-Dapple.awt.UIElement=true"" -jar ""%JAR%"" %*`r`n"
[System.IO.File]::WriteAllText($DstCmd, $cmdContent, [System.Text.Encoding]::ASCII)

# Add to User PATH persistently
$UserPath = [System.Environment]::GetEnvironmentVariable("PATH", "User") -split ";" | Where-Object { $_ }
if ($Bin -notin $UserPath) {
    $NewPath = ($UserPath + $Bin) -join ";"
    [System.Environment]::SetEnvironmentVariable("PATH", $NewPath, "User")
}

# Also update current PowerShell session PATH immediately (no restart needed!)
if ($env:Path -notlike "*$Bin*") {
    $env:Path = "$Bin;$env:Path"
}
Write-Host " Done. [OK]" -ForegroundColor Green

Write-Host ""
Write-Host "=================================================" -ForegroundColor Green
Write-Host "             Installation Complete!              " -ForegroundColor Green
Write-Host "=================================================" -ForegroundColor Green
Write-Host ""
Write-Host "You can now run blastradius directly from any terminal:"
Write-Host "  blastradius screen      # Live mode with continuous terminal logs"
Write-Host "  blastradius start       # Background daemon mode"
Write-Host "  blastradius status      # Check if running"
Write-Host "  blastradius stop        # Stop background watcher"
Write-Host "  blastradius explain 'git status'"
Write-Host ""
Write-Host "Zero-config: Works with any project, language, or AI agent (Antigravity, Cursor, Codex...)"
Write-Host "Privacy: 100% offline. Zero network calls. No data ever leaves your machine."
