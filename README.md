# MiniJIT

[![CI](https://github.com/USERNAME/MiniJIT/actions/workflows/ci.yml/badge.svg)](https://github.com/USERNAME/MiniJIT/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A just-in-time compiler for a small imperative language. The frontend is Java,
the backend is C++ on top of LLVM, and the two halves talk over JNI.

```
program.mini
   |
   |  Java: Lexer -> Parser -> AST -> JSON
   v
[ JNI bridge ]
   |
   |  C++: JSON -> AST -> LLVM IR -> optimiser -> LLJIT
   v
native code, executed in memory
```

Nothing is written to disk as an object file or executable. LLVM's LLJIT
compiles the IR into the running process's own memory and hands back a function
pointer, which is what makes this a JIT rather than an ordinary compiler.

```console
$ java -Djava.library.path=cpp/build/Release -jar minijit.jar --run examples/fib.mini
55
```

## Why this exists

It is a learning project, built to understand how a real compiler backend fits
together: SSA form, basic blocks, phi nodes, and what an optimiser actually does
to naive generated code. The structure follows LLVM's
[Kaleidoscope tutorial](https://llvm.org/docs/tutorial/), extended with a
separate frontend in another language and a JNI boundary between them.

## The language

```rust
// Recursive Fibonacci
fn fib(n) {
  if (n < 2) {
    return n;
  } else {
    return fib(n - 1) + fib(n - 2);
  }
}

fn main() {
  return fib(10);   // 55
}
```

| Construct | Syntax |
|---|---|
| Function | `fn name(a, b) { ... }` |
| Declaration | `let x = <expr>;` |
| Assignment | `x = <expr>;` |
| Conditional | `if (<expr>) { } else { }` |
| Loop | `while (<expr>) { }` |
| Return | `return <expr>;` (value optional) |
| Comment | `// to end of line` |

Operators, loosest binding first:

```
=        assignment, right associative
== !=    equality
< <= > >=   comparison
+ -      additive
* / %    multiplicative
-        unary negation
f(x)     call
```

Every value is a signed 64-bit integer. There are no floats, strings or
booleans; a condition is true when it is nonzero, as in C. Functions may call
each other in any order — all prototypes are declared before any body is
emitted, so forward references and mutual recursion work.

More programs live in [`examples/`](examples).

## Repository layout

```
java/                     Maven project - frontend
  com/minijit/lexer/        Lexer, Token, TokenType
  com/minijit/parser/       recursive-descent Parser
  com/minijit/ast/          AST nodes and JSON serialisation
  com/minijit/jni/          NativeBackend - the Java side of the bridge
  com/minijit/Main.java     CLI

cpp/                      CMake project - backend
  src/ast_json.cpp          JSON -> C++ AST
  src/codegen.cpp           AST -> LLVM IR
  src/jit.cpp               LLJIT wrapper
  src/pipeline.cpp          the above, wired together
  src/main.cpp              standalone CLI
  src/jni_bridge.cpp        JNI entry points

examples/                 sample .mini programs
run-tests.ps1             end-to-end test suite
```

The backend builds as `minijit-core`, a static library holding everything except
entry points, which is linked into both the standalone executable and the JNI
library. The two therefore provably run identical code.

## Design notes

**The AST crosses the JNI boundary as a JSON string.** Reaching into Java
objects from C++ would mean managing field IDs and local references for every
node; one string is simpler and much harder to get wrong. The same format is
what the standalone backend reads from a file, so the pipeline can be tested
with the bridge out of the picture.

**Locals are stack slots, not SSA values.** Codegen emits an `alloca` per
variable with `load`/`store` around it, exactly as in Kaleidoscope chapter 7.
That keeps mutation and control flow simple; `mem2reg` then promotes the slots
into SSA registers and inserts phi nodes. Compare the two with `--no-opt`:

```console
$ minijit-backend --emit-ir --no-opt sum.json
  %i = alloca i64, align 8
  %i2 = load i64, ptr %i, align 4
  ...

$ minijit-backend --emit-ir sum.json
  %i.014 = phi i64 [ %add7, %while.body ], [ 1, %entry ]
```

That diff is the clearest illustration of what SSA form buys you.

**Division is guarded.** LLVM leaves `sdiv`/`srem` undefined for a zero divisor
and for `INT64_MIN / -1`. Codegen emits a runtime check that branches to
`@llvm.trap` instead, so the failure is a predictable crash rather than
undefined behaviour.

**C++ exceptions never cross into the JVM.** Every JNI entry point catches
everything, including `catch (...)`, and rethrows as a Java exception. Letting
an exception unwind past the boundary is undefined behaviour and would kill the
process without a stack trace.

## Building

### Requirements

- JDK 17 or newer (a JRE is not enough — `javac` is required)
- CMake 3.22+
- A C++17 compiler
- LLVM 17 or newer, with CMake config files

### Linux

```bash
sudo apt install openjdk-17-jdk maven cmake llvm-18-dev

mvn -f java/pom.xml package
cmake -B cpp/build -S cpp
cmake --build cpp/build
```

### Windows

The official LLVM installer **does not work** here: upstream deliberately omits
`LLVMConfig.cmake` from the Windows package and closed the request as *won't do*
([llvm-project#53052](https://github.com/llvm/llvm-project/issues/53052)), so
`find_package(LLVM CONFIG)` cannot find it. Use vcpkg, which builds LLVM with
proper config files.

Requires Visual Studio 2022 or newer with the *Desktop development with C++*
workload **and** the *C++ ATL* individual component, which vcpkg's LLVM port
depends on.

```powershell
git clone https://github.com/microsoft/vcpkg C:\vcpkg
C:\vcpkg\bootstrap-vcpkg.bat
C:\vcpkg\vcpkg.exe install llvm[core,target-x86]:x64-windows
```

That compiles LLVM from source: budget an hour or more and tens of gigabytes of
temporary disk. It only happens once.

If you have no system-wide Maven, `setup-maven.ps1` downloads one into `tools/`
and `mvn.ps1` wraps it, resolving `JAVA_HOME` on the way:

```powershell
powershell -ExecutionPolicy Bypass -File setup-maven.ps1
.\mvn.ps1 -f java\pom.xml package

cmake -B cpp\build -S cpp -DCMAKE_TOOLCHAIN_FILE=C:/vcpkg/scripts/buildsystems/vcpkg.cmake
cmake --build cpp\build --config Release
```

Build **Release**. A Debug build links the debug CRT, which does not mix with
vcpkg's release LLVM libraries.

nlohmann/json needs no installation — CMake downloads the single header at
configure time.

## Usage

```
minijit [mode] [options] <file.mini>

Modes:
  --tokens    print the token stream
  --ast       print the AST as JSON (default)
  --emit-ir   print the LLVM IR          (needs the native library)
  --run       compile and execute        (needs the native library)

Options:
  -o, --out F   write output to F as UTF-8 instead of stdout
  --entry NAME  entry function for --run (default: main)
  --no-opt      skip the -O2 pipeline
```

Through the JNI bridge:

```powershell
java "-Djava.library.path=cpp\build\Release" -jar java\target\minijit-0.1.0-SNAPSHOT.jar --run examples\fib.mini
```

`java.library.path` is where `System.loadLibrary` looks for the library. To load
one specific file, pass `-Dminijit.native.file=<absolute path>` instead. The
bridge is built only when CMake finds a JDK; if configuring reports *JNI not
found*, point `JAVA_HOME` at a JDK and re-run it.

Or through the standalone backend, with the AST passed as a file:

```powershell
java -jar java\target\minijit-0.1.0-SNAPSHOT.jar -o fib.json examples\fib.mini
cpp\build\Release\minijit-backend.exe --run fib.json
```

Use `-o` rather than `>`. Windows PowerShell 5.1 encodes redirected output as
UTF-16LE with a byte order mark, which no JSON parser accepts; `-o` always
writes UTF-8.

Exit codes: `0` success, `1` compile or execution error, `2` bad usage or
unreadable file.

## Tests

```powershell
.\mvn.ps1 -f java\pom.xml test   # 48 unit tests: lexer, parser, serialisation
.\run-tests.ps1                  # 20 end-to-end tests
```

`run-tests.ps1` compiles and runs every example, checks the returned value,
repeats the run with `--no-opt` so codegen is verified without the optimiser
covering for it, repeats it again through the JNI bridge, and asserts that bad
input produces the right diagnostic rather than a crash. Pass `-Build` to build
both halves first.

## Known limitations

- No type checking beyond call arity — every value is `i64`.
- Division by zero traps and kills the process; it is defined behaviour, but not
  yet a catchable error.
- Errors carry a line number but no column.
- The JIT compiles everything eagerly on load. Lazy per-function compilation and
  re-optimisation of hot functions, which is what makes JITs like HotSpot and V8
  interesting, are not implemented.
- Codegen emits a `zext`/`icmp` round trip for comparisons used as conditions.
  The optimiser removes it, but it should not be generated in the first place.

## License

[MIT](LICENSE).
