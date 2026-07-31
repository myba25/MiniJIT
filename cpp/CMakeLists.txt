cmake_minimum_required(VERSION 3.22)
project(minijit_backend CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# --- LLVM ------------------------------------------------------------------
# Configure with the vcpkg toolchain so this is found automatically:
#   cmake -B build -S . -DCMAKE_TOOLCHAIN_FILE=C:/vcpkg/scripts/buildsystems/vcpkg.cmake
#
# Note: the official LLVM installer for Windows deliberately does not ship
# LLVMConfig.cmake (upstream closed that request as "won't do"), so a plain
# `winget install LLVM.LLVM` will NOT satisfy this find_package. Use vcpkg or a
# self-built LLVM and point -DLLVM_DIR at <prefix>/lib/cmake/llvm.
# No version is requested here on purpose. LLVM ships a ConfigVersion file with
# SameMajorVersion compatibility, so asking for `LLVM 20` would *reject* LLVM 21
# as unsuitable rather than accept it as newer. Ask for any version, then check.
find_package(LLVM REQUIRED CONFIG)
message(STATUS "Using LLVM ${LLVM_PACKAGE_VERSION} from ${LLVM_DIR}")

if(LLVM_PACKAGE_VERSION VERSION_LESS 17)
    message(FATAL_ERROR
        "MiniJIT needs LLVM 17 or newer (opaque pointers, ORC v2, new pass manager); "
        "found ${LLVM_PACKAGE_VERSION}.")
endif()

# --- nlohmann/json ---------------------------------------------------------
# Only the single-header release is fetched, not the library's own CMake
# project. That is deliberate: nlohmann/json 3.11.x declares
# cmake_minimum_required(VERSION 3.1), and CMake 4 removed compatibility with
# anything below 3.5, so pulling the full source via FetchContent fails outright
# on a modern CMake. A bare header has no such problem.
set(MINIJIT_JSON_DIR "${CMAKE_BINARY_DIR}/_deps/json-header")
set(MINIJIT_JSON_HEADER "${MINIJIT_JSON_DIR}/nlohmann/json.hpp")

if(NOT EXISTS "${MINIJIT_JSON_HEADER}")
    message(STATUS "Downloading nlohmann/json single header")
    file(DOWNLOAD
        https://github.com/nlohmann/json/releases/download/v3.12.0/json.hpp
        "${MINIJIT_JSON_HEADER}"
        SHOW_PROGRESS
        STATUS MINIJIT_JSON_STATUS
    )
    list(GET MINIJIT_JSON_STATUS 0 MINIJIT_JSON_CODE)
    if(NOT MINIJIT_JSON_CODE EQUAL 0)
        list(GET MINIJIT_JSON_STATUS 1 MINIJIT_JSON_MESSAGE)
        file(REMOVE "${MINIJIT_JSON_HEADER}")
        message(FATAL_ERROR "Failed to download nlohmann/json: ${MINIJIT_JSON_MESSAGE}")
    endif()
endif()

# --- shared core -------------------------------------------------------------
# Everything except the entry points, so the standalone executable and the JNI
# library are guaranteed to run identical code.
add_library(minijit-core STATIC
    src/ast_json.cpp
    src/codegen.cpp
    src/jit.cpp
    src/pipeline.cpp
)

# Required on Linux/macOS to link a static library into a shared one; harmless
# on Windows, where all code is position independent anyway.
set_property(TARGET minijit-core PROPERTY POSITION_INDEPENDENT_CODE ON)

target_include_directories(minijit-core PUBLIC
    include
    "${MINIJIT_JSON_DIR}"
    ${LLVM_INCLUDE_DIRS}
)

target_compile_definitions(minijit-core PUBLIC ${LLVM_DEFINITIONS})

if(MSVC)
    # /bigobj: LLVM's templated headers blow past the default section limit.
    # NOMINMAX: <windows.h> defines min/max macros that break std::min/std::max.
    # /EHsc: the backend reports every error by throwing, so exceptions must be on.
    target_compile_options(minijit-core PUBLIC /bigobj /EHsc)
    target_compile_definitions(minijit-core PUBLIC NOMINMAX _CRT_SECURE_NO_WARNINGS)
endif()

# `core` is the IR itself, `orcjit` is the ORC v2 / LLJIT execution engine,
# `native` expands to the code generator for the host architecture, and
# `passes` provides PassBuilder and the standard -O2 pipeline.
llvm_map_components_to_libnames(MINIJIT_LLVM_LIBS core support orcjit native passes)

target_link_libraries(minijit-core PUBLIC ${MINIJIT_LLVM_LIBS})

# --- standalone executable -----------------------------------------------------
add_executable(minijit-backend src/main.cpp)
target_link_libraries(minijit-backend PRIVATE minijit-core)

# --- JNI library ---------------------------------------------------------------
# Optional: without a JDK the executable still builds, only the bridge is skipped.
find_package(JNI QUIET)

if(JNI_FOUND)
    message(STATUS "Building the JNI bridge against ${JAVA_INCLUDE_PATH}")

    add_library(minijit_jni SHARED src/jni_bridge.cpp)

    # JNI_INCLUDE_DIRS covers both jni.h and the platform-specific jni_md.h,
    # which lives in a win32/ or linux/ subdirectory.
    target_include_directories(minijit_jni PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(minijit_jni PRIVATE minijit-core)

    # System.loadLibrary("minijit_jni") looks for minijit_jni.dll on Windows and
    # libminijit_jni.so elsewhere, which is what CMake produces by default — so
    # no prefix fiddling is needed here.
else()
    message(WARNING
        "JNI not found — skipping the minijit_jni target. "
        "Set JAVA_HOME to a JDK (not a JRE) and re-run cmake to build the bridge.")
endif()
