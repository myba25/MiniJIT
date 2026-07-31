# End-to-end tests: runs every example through the Java frontend and the C++
# backend, and checks the value the JIT'd code returns.
#
#   .\run-tests.ps1              both halves must already be built
#   .\run-tests.ps1 -Build       build them first
#
# Exit code is 0 when everything passes, 1 otherwise, so this can be wired into CI.
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads scripts as
# Windows-1252 unless they carry a byte order mark, and a UTF-8 em dash decodes
# to a curly quote in that codepage - which PowerShell accepts as a string
# delimiter, silently unbalancing every quote in the rest of the file.

param(
    [switch]$Build
)

$ErrorActionPreference = "Stop"

$root    = $PSScriptRoot
$jar     = Join-Path $root "java\target\minijit-0.1.0-SNAPSHOT.jar"
$backend = Join-Path $root "cpp\build\Release\minijit-backend.exe"
$jniLib  = Join-Path $root "cpp\build\Release\minijit_jni.dll"
$temp    = Join-Path $root "cpp\build\test-json"

# --- expected results -------------------------------------------------------
# Each entry is an example program and the value main() should return.
$cases = @(
    @{ File = "fib.mini";    Expected = 55  }   # recursion, if/else, comparison
    @{ File = "sum.mini";    Expected = 111 }   # while loop, mutation, precedence
    @{ File = "gcd.mini";    Expected = 21  }   # modulo, loop with two updates
    @{ File = "mutual.mini"; Expected = 2   }   # forward reference between functions
    @{ File = "nested.mini"; Expected = 42  }   # nested if/else, unary minus
)

# --- running external programs ------------------------------------------------
# With $ErrorActionPreference = "Stop", Windows PowerShell turns *any* stderr
# output from a native program into a terminating error. Several tests here
# deliberately trigger error messages, so native calls go through this wrapper:
# it captures both streams and reports the exit code instead of throwing.
function Invoke-Native {
    param(
        [Parameter(Mandatory)][string] $Exe,
        [string[]] $Arguments = @()
    )

    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $Exe @Arguments 2>&1 | Out-String
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }

    [pscustomobject]@{
        Output   = $output.Trim()
        ExitCode = $code
    }
}

# --- optional build ----------------------------------------------------------
if ($Build) {
    Write-Host "Building Java frontend ..."
    $mvn = Invoke-Native (Join-Path $root "mvn.ps1") @("-f", (Join-Path $root "java\pom.xml"), "package", "-q")
    if ($mvn.ExitCode -ne 0) {
        Write-Host $mvn.Output
        Write-Error "Java build failed"
        exit 1
    }

    Write-Host "Building C++ backend ..."
    $cmake = (Get-ChildItem C:\vcpkg\downloads\tools -Recurse -Filter cmake.exe -ErrorAction SilentlyContinue |
              Select-Object -First 1).FullName
    if (-not $cmake) { $cmake = "cmake" }
    $build = Invoke-Native $cmake @("--build", (Join-Path $root "cpp\build"), "--config", "Release")
    if ($build.ExitCode -ne 0) {
        Write-Host $build.Output
        Write-Error "C++ build failed"
        exit 1
    }
}

foreach ($required in @($jar, $backend)) {
    if (-not (Test-Path $required)) {
        Write-Error "Missing $required. Run with -Build first."
        exit 1
    }
}

New-Item -ItemType Directory -Force -Path $temp | Out-Null

$passed = 0
$failed = 0

function Report($ok, $name, $detail) {
    if ($ok) {
        Write-Host "  PASS  $name" -ForegroundColor Green
        $script:passed++
    } else {
        Write-Host "  FAIL  $name - $detail" -ForegroundColor Red
        $script:failed++
    }
}

# --- the happy path ----------------------------------------------------------
Write-Host "`nEnd-to-end: source -> tokens -> AST -> JSON -> IR -> native -> run"

foreach ($case in $cases) {
    $source = Join-Path $root "examples\$($case.File)"
    $json   = Join-Path $temp  "$($case.File).json"

    # -o rather than '>': PowerShell 5.1 would write UTF-16 and the JSON parser
    # would reject it.
    $frontend = Invoke-Native "java" @("-jar", $jar, "-o", $json, $source)
    if ($frontend.ExitCode -ne 0) {
        Report $false $case.File "frontend exited $($frontend.ExitCode): $($frontend.Output)"
        continue
    }

    $run = Invoke-Native $backend @("--run", $json)
    if ($run.ExitCode -ne 0) {
        Report $false $case.File "backend exited $($run.ExitCode): $($run.Output)"
        continue
    }

    if ([int64]$run.Output -eq $case.Expected) {
        Report $true $case.File ""
    } else {
        Report $false $case.File "expected $($case.Expected), got $($run.Output)"
    }
}

# --- the same programs must survive without the optimiser --------------------
Write-Host "`nSame results with --no-opt (codegen must be correct on its own)"

foreach ($case in $cases) {
    $json = Join-Path $temp "$($case.File).json"
    $run = Invoke-Native $backend @("--run", "--no-opt", $json)
    if ($run.ExitCode -eq 0 -and [int64]$run.Output -eq $case.Expected) {
        Report $true "$($case.File) --no-opt" ""
    } else {
        Report $false "$($case.File) --no-opt" "expected $($case.Expected), got $($run.Output)"
    }
}

# --- errors must be reported, not swallowed ----------------------------------
Write-Host "`nError handling"

$badSource = Join-Path $temp "bad-syntax.mini"
Set-Content -Path $badSource -Value "fn f() { let x = 5 }" -Encoding Ascii
$syntax = Invoke-Native "java" @("-jar", $jar, "-o", (Join-Path $temp "bad.json"), $badSource)
Report ($syntax.ExitCode -eq 1 -and $syntax.Output -match "Parse error") `
       "missing semicolon rejected" "exited $($syntax.ExitCode): $($syntax.Output)"

$unknownSource = Join-Path $temp "unknown-var.mini"
Set-Content -Path $unknownSource -Value "fn main() { return nope; }" -Encoding Ascii
$unknownJson = Join-Path $temp "unknown.json"
Invoke-Native "java" @("-jar", $jar, "-o", $unknownJson, $unknownSource) | Out-Null
$semantic = Invoke-Native $backend @("--run", $unknownJson)
Report ($semantic.ExitCode -eq 1 -and $semantic.Output -match "unknown variable") `
       "unknown variable rejected by codegen" "exited $($semantic.ExitCode): $($semantic.Output)"

# Division by zero must trap rather than silently produce a value. @llvm.trap
# lowers to an illegal instruction, so the process dies on a nonzero exit code.
$divSource = Join-Path $temp "div-zero.mini"
Set-Content -Path $divSource -Value "fn main() { let z = 0; return 1 / z; }" -Encoding Ascii
$divJson = Join-Path $temp "div-zero.json"
Invoke-Native "java" @("-jar", $jar, "-o", $divJson, $divSource) | Out-Null
$div = Invoke-Native $backend @("--run", "--no-opt", $divJson)
Report ($div.ExitCode -ne 0) "division by zero traps" "exited 0, expected a crash"

# A UTF-16 file is what shell redirection produces on Windows; the backend
# should say so rather than emit a cryptic JSON parse error.
$utf16 = Join-Path $temp "utf16.json"
[System.IO.File]::WriteAllText($utf16, '{"functions":[]}', [System.Text.Encoding]::Unicode)
$encoding = Invoke-Native $backend @("--run", $utf16)
Report ($encoding.ExitCode -eq 1 -and $encoding.Output -match "UTF-16") `
       "UTF-16 input diagnosed clearly" "got: $($encoding.Output)"

# --- the JNI bridge must produce the same answers ----------------------------
if (Test-Path $jniLib) {
    Write-Host "`nSame results through the JNI bridge (no intermediate file)"
    $libDir = Split-Path $jniLib -Parent

    foreach ($case in $cases) {
        $source = Join-Path $root "examples\$($case.File)"
        $run = Invoke-Native "java" @("-Djava.library.path=$libDir", "-jar", $jar, "--run", $source)
        if ($run.ExitCode -eq 0 -and [int64]$run.Output -eq $case.Expected) {
            Report $true "$($case.File) via JNI" ""
        } else {
            Report $false "$($case.File) via JNI" "expected $($case.Expected), got $($run.Output)"
        }
    }

    # A backend error must arrive as a Java exception, not as a dead JVM.
    $jniError = Invoke-Native "java" @("-Djava.library.path=$libDir", "-jar", $jar, "--run", $unknownSource)
    Report ($jniError.ExitCode -eq 1 -and $jniError.Output -match "unknown variable") `
           "codegen error crosses JNI as an exception" "exited $($jniError.ExitCode): $($jniError.Output)"
} else {
    Write-Host "`nSkipping JNI tests: $jniLib not built" -ForegroundColor Yellow
}

# --- summary -------------------------------------------------------------------
Write-Host "`n$passed passed, $failed failed"
if ($failed -gt 0) { exit 1 }
exit 0
