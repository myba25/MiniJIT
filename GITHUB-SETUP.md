# Publishing this to GitHub

Delete this file once you are done — it is setup notes, not part of the project.

## 1. Fix the badge URL

`README.md` starts with a CI badge containing `USERNAME`. Replace both
occurrences with your GitHub account name, or the badge will render broken.

## 2. Check the copyright line

`LICENSE` says `Copyright (c) 2026 Mykyta Balandin`. Correct the name if that is
not how you want to be credited.

## 3. Create the repository

```powershell
cd C:\Users\balan\MiniJIT_Claude
git init
git add .
git commit -m "MiniJIT: a JIT compiler for a toy language in Java and C++"
git branch -M main
```

Then create an empty repository on GitHub — **without** a README, .gitignore or
license, since this project already has all three — and push:

```powershell
git remote add origin https://github.com/USERNAME/MiniJIT.git
git push -u origin main
```

Before committing, confirm nothing unwanted is staged:

```powershell
git status --short
```

`java/target/`, `cpp/build/` and `tools/` should not appear. If they do, the
`.gitignore` was added after those files were already staged; run
`git rm -r --cached java/target cpp/build tools` and commit again.

## 4. Repository description

Paste into the *About* box on the repository page:

> A just-in-time compiler for a small imperative language. Java frontend, C++/LLVM backend, JNI bridge.

## 5. Topics

Add these tags so the project turns up in searches:

```
compiler  jit  llvm  llvm-ir  java  cpp  jni  lexer  parser
recursive-descent-parser  code-generation  compiler-design  toy-language
```

## 6. Check that CI passes

The workflow in `.github/workflows/ci.yml` runs on Ubuntu, where LLVM comes from
apt in seconds instead of the hour vcpkg needs on Windows. It runs the Java unit
tests, builds the backend, and runs every example through both the standalone
binary and the JNI bridge.

The first run happens automatically on push. If it fails, the likely causes are:

- **`llvm-18-dev` not found** — Ubuntu changed its package version. Bump the
  version in the workflow, and the `LLVM_DIR` path with it.
- **JNI not found** — `actions/setup-java` should export `JAVA_HOME`; check that
  step ran before the CMake configure step.
