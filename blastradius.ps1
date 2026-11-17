# blastradius.ps1 - PowerShell launcher for blastradius
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ArgsList
)

$ScriptDir = $PSScriptRoot
$Jar = Join-Path $ScriptDir "blastradius-0.1.0-SNAPSHOT.jar"

if (-not (Test-Path $Jar)) {
    $Jar = Join-Path $ScriptDir "blastradius-java\target\blastradius-0.1.0-SNAPSHOT.jar"
}

if (-not (Test-Path $Jar)) {
    Write-Error "blastradius JAR not found. Run .\install.ps1 first."
    exit 1
}

& java.exe "-Dapple.awt.UIElement=true" -jar $Jar @ArgsList
