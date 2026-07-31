# Downloads Apache Maven into tools/ inside this project - no admin rights,
# no system PATH changes, nothing installed globally.
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads scripts as
# Windows-1252 unless they carry a byte order mark, and a UTF-8 em dash decodes
# to a curly quote in that codepage - which PowerShell accepts as a string
# delimiter, silently unbalancing every quote in the rest of the file.
#
# Usage:  powershell -ExecutionPolicy Bypass -File setup-maven.ps1
#
# Afterwards use .\mvn.ps1 from the project root instead of `mvn`.

$ErrorActionPreference = "Stop"

$version = "3.9.9"
$root    = $PSScriptRoot
$tools   = Join-Path $root "tools"
$target  = Join-Path $tools "apache-maven-$version"
$zip     = Join-Path $env:TEMP "apache-maven-$version-bin.zip"
$url     = "https://archive.apache.org/dist/maven/maven-3/$version/binaries/apache-maven-$version-bin.zip"

if (Test-Path (Join-Path $target "bin\mvn.cmd")) {
    Write-Host "Maven $version already present at $target"
} else {
    New-Item -ItemType Directory -Force -Path $tools | Out-Null

    Write-Host "Downloading Maven $version ..."
    # TLS 1.2 for older PowerShell 5.1 defaults
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing

    Write-Host "Extracting ..."
    Expand-Archive -Path $zip -DestinationPath $tools -Force
    Remove-Item $zip -Force
}

# --- verify the JDK, which Maven cannot supply for itself ------------------
$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) {
    Write-Warning "javac not found on PATH. Maven needs a JDK (not just a JRE)."
    Write-Warning "Install Temurin 17: winget install EclipseAdoptium.Temurin.17.JDK"
    Write-Warning "Then open a NEW PowerShell window and re-check with: javac -version"
} else {
    Write-Host "Found javac at $($javac.Source)"
}

Write-Host ""
Write-Host "Done. Maven lives at $target"
Write-Host "Run builds with:  .\mvn.ps1 -f java\pom.xml test"
