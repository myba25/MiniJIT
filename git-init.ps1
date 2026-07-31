# Creates the repository with a commit history that mirrors how the project was
# actually built, instead of a single "initial commit" containing everything.
#
#   powershell -ExecutionPolicy Bypass -File git-init.ps1
#
# Delete this script afterwards - it is a one-off, not part of the project.
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 decodes scripts as
# Windows-1252 unless they carry a byte order mark.

$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

if (Test-Path .git) {
    Write-Error "This directory is already a git repository. Delete .git first if you want to start over."
    exit 1
}

git init | Out-Null
git branch -M main

function Commit {
    param(
        [Parameter(Mandatory)][string]   $Message,
        [Parameter(Mandatory)][string[]] $Paths
    )

    $existing = $Paths | Where-Object { Test-Path $_ }
    if (-not $existing) {
        Write-Warning "Skipping '$Message' - none of its paths exist"
        return
    }

    git add -- $existing
    git commit -q -m $Message
    Write-Host "  $Message"
}

Write-Host "Building history:"

Commit "Add project scaffolding: Maven and CMake layout" `
       @(".gitignore", ".gitattributes", "java/pom.xml")

Commit "Add lexer for the MiniJIT language" `
       @("java/src/main/java/com/minijit/lexer",
         "java/src/test/java/com/minijit/lexer")

Commit "Add AST node types and recursive-descent parser" `
       @("java/src/main/java/com/minijit/ast/Expr.java",
         "java/src/main/java/com/minijit/ast/Stmt.java",
         "java/src/main/java/com/minijit/ast/Program.java",
         "java/src/main/java/com/minijit/parser",
         "java/src/test/java/com/minijit/parser")

Commit "Serialise the AST to JSON for the C++ backend" `
       @("java/src/main/java/com/minijit/ast/AstJson.java",
         "java/src/test/java/com/minijit/ast")

Commit "Add the frontend CLI and example programs" `
       @("java/src/main/java/com/minijit/Main.java", "examples")

Commit "Add LLVM IR codegen and an LLJIT-backed runner" `
       @("cpp/CMakeLists.txt", "cpp/include", "cpp/src/ast_json.cpp",
         "cpp/src/codegen.cpp", "cpp/src/jit.cpp", "cpp/src/pipeline.cpp",
         "cpp/src/main.cpp")

Commit "Bridge Java and C++ over JNI" `
       @("java/src/main/java/com/minijit/jni", "cpp/src/jni_bridge.cpp")

Commit "Add the end-to-end test suite" `
       @("run-tests.ps1")

Commit "Add helper scripts for a project-local Maven" `
       @("setup-maven.ps1", "mvn.ps1")

Commit "Add documentation, license and CI" `
       @("README.md", "LICENSE", ".github", "GITHUB-SETUP.md")

# Anything not matched above - including this script itself.
git add -A
if ((git status --porcelain) -ne $null) {
    git commit -q -m "Add remaining project files"
    Write-Host "  Add remaining project files"
}

Write-Host ""
git --no-pager log --oneline
Write-Host ""
Write-Host "Now create an EMPTY repository on GitHub (no README, no .gitignore,"
Write-Host "no license - this project already has them), then:"
Write-Host ""
Write-Host "  git remote add origin https://github.com/USERNAME/MiniJIT.git"
Write-Host "  git push -u origin main"
