# Thin wrapper around the project-local Maven installed by setup-maven.ps1.
# Forwards all arguments straight through:
#
#   .\mvn.ps1 -f java\pom.xml test
#   .\mvn.ps1 -f java\pom.xml package
#
# Also resolves JAVA_HOME from javac's location if it isn't already set,
# which is the most common reason a fresh Maven install refuses to start.

$ErrorActionPreference = "Stop"

$mvn = Join-Path $PSScriptRoot "tools\apache-maven-3.9.9\bin\mvn.cmd"

if (-not (Test-Path $mvn)) {
    Write-Error "Maven not found. Run: powershell -ExecutionPolicy Bypass -File setup-maven.ps1"
    exit 1
}

if (-not $env:JAVA_HOME) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $javac) {
        Write-Error "No JDK found. Install one: winget install EclipseAdoptium.Temurin.17.JDK"
        exit 1
    }
    # javac sits in <jdk>\bin\javac.exe, so JAVA_HOME is two levels up.
    $env:JAVA_HOME = Split-Path (Split-Path $javac.Source -Parent) -Parent
    Write-Host "JAVA_HOME set to $env:JAVA_HOME"
}

& $mvn @args
exit $LASTEXITCODE
