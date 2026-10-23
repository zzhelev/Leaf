#Requires -Version 7
<#
.SYNOPSIS
    Builds Leaf on Windows.
#>
param(
    [string]$Configuration = "Release",
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$jdk = Join-Path $env:ProgramFiles "Java\jdk-25"

function Invoke-Gradle([string[]]$Tasks) {
    & .\gradlew.bat @Tasks --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
}

# Build, then test unless skipped.
Invoke-Gradle @('build')
if (-not $SkipTests) { Invoke-Gradle @('test') }
Write-Host "Built $Configuration with $($jdk.Length) chars of path" -ForegroundColor Green
