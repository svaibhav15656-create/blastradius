@echo off
:: blastradius.cmd — Windows launcher for the blastradius fat JAR.
:: Place this file in a folder on your PATH, or run install.ps1 to do it automatically.
setlocal

:: Find the JAR next to this script.
set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%blastradius-0.1.0-SNAPSHOT.jar"

if not exist "%JAR%" (
    echo blastradius: JAR not found at %JAR%
    echo Run install.ps1 to build and install, or set the path manually.
    exit /b 1
)

:: Use javaw.exe only for the start sub-command (no console window in background).
:: All other commands run in the current console with java.exe.
if "%~1"=="start" (
    javaw.exe -Dapple.awt.UIElement=true -jar "%JAR%" %*
) else (
    java.exe  -Dapple.awt.UIElement=true -jar "%JAR%" %*
)
