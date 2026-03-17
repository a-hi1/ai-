param(
    [Parameter(Mandatory = $false)]
    [string]$XlsxPath = "../商品-2026-03-13_19-19.xlsx",

    [Parameter(Mandatory = $false)]
    [int]$Limit = 5000,

    [Parameter(Mandatory = $false)]
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$pythonExe = Join-Path $scriptDir "../.venv/Scripts/python.exe"
if (-not (Test-Path $pythonExe)) {
    throw "Python executable not found: $pythonExe"
}

if (-not (Test-Path $XlsxPath)) {
    throw "XLSX file not found: $XlsxPath"
}

$args = @(
    "src/main.py",
    "--input-xlsx", $XlsxPath,
    "--limit", "$Limit",
    "--replace-source", "XLSX"
)

if ($DryRun) {
    $args = @(
        "src/main.py",
        "--input-xlsx", $XlsxPath,
        "--limit", "$Limit",
        "--dry-run"
    )
}

Write-Host "Running XLSX import with file: $XlsxPath"
& $pythonExe @args
