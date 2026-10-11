# Dot-source this script to activate the project CLI and installed Android tools.
# Usage: . .\tools\activate-traceveil.ps1
$traceveilWorkspace = Split-Path -Parent $PSScriptRoot
$traceveilAdbDirectory = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools'
if (-not (Test-Path (Join-Path $traceveilAdbDirectory 'adb.exe'))) {
    throw "ADB was not found at $traceveilAdbDirectory"
}
$env:PATH = "$traceveilAdbDirectory;$env:PATH"
& (Join-Path $traceveilWorkspace '.venv\Scripts\Activate.ps1')
traceveil --version
