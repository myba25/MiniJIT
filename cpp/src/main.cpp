// Standalone driver for the MiniJIT backend.
//
//   minijit-backend [--emit-ir | --run] [--entry NAME] [--no-opt] <ast.json>
//
// Reads the JSON the Java frontend prints, lowers it to LLVM IR, then either
// prints that IR or JIT-compiles and runs it.
//
// This exists so the two halves of the compiler can be tested without the JNI
// bridge in the picture:
//
//   java -jar java\target\minijit-0.1.0-SNAPSHOT.jar -o fib.json examples\fib.mini
//   minijit-backend --run fib.json

#include "minijit/pipeline.hpp"

#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>

namespace {

void printUsage() {
    std::cerr << "Usage: minijit-backend [--emit-ir|--run] [--entry NAME] [--no-opt] <ast.json>\n"
              << "  --emit-ir     print the generated LLVM IR (default)\n"
              << "  --run         JIT-compile and execute\n"
              << "  --entry NAME  entry function for --run (default: main)\n"
              << "  --no-opt      skip the -O2 pipeline; shows the raw alloca-heavy IR\n";
}

std::string readFile(const std::string& path) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) {
        throw std::runtime_error("cannot open '" + path + "'");
    }
    std::ostringstream buffer;
    buffer << stream.rdbuf();
    std::string text = buffer.str();

    // Windows editors and shells like to prefix files with a byte order mark.
    // A UTF-8 BOM is harmless to drop; a UTF-16 one means the file is in the
    // wrong encoding entirely, and saying so beats "invalid literal at column 1".
    if (text.size() >= 2 &&
        ((static_cast<unsigned char>(text[0]) == 0xFF && static_cast<unsigned char>(text[1]) == 0xFE) ||
         (static_cast<unsigned char>(text[0]) == 0xFE && static_cast<unsigned char>(text[1]) == 0xFF))) {
        throw std::runtime_error(
            "'" + path + "' is UTF-16 encoded; MiniJIT expects UTF-8.\n"
            "Windows PowerShell writes UTF-16 when you use '>'. Let the frontend "
            "write the file instead:\n"
            "  minijit -o " + path + " <source.mini>");
    }
    if (text.size() >= 3 && static_cast<unsigned char>(text[0]) == 0xEF &&
        static_cast<unsigned char>(text[1]) == 0xBB && static_cast<unsigned char>(text[2]) == 0xBF) {
        text.erase(0, 3);
    }
    return text;
}

} // namespace

int main(int argc, char** argv) {
    std::string mode = "--emit-ir";
    std::string entry = "main";
    std::string path;
    bool optimize = true;

    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "--emit-ir" || arg == "--run") {
            mode = arg;
        } else if (arg == "--no-opt") {
            optimize = false;
        } else if (arg == "--entry") {
            if (i + 1 >= argc) {
                std::cerr << "--entry needs a function name\n";
                return 2;
            }
            entry = argv[++i];
        } else if (arg.rfind("--", 0) == 0) {
            std::cerr << "Unknown option: " << arg << "\n";
            printUsage();
            return 2;
        } else {
            path = arg;
        }
    }

    if (path.empty()) {
        printUsage();
        return 2;
    }

    // One catch-all: every failure mode in the backend reports through an
    // exception derived from std::exception, so a single handler covers
    // unreadable files, malformed JSON, semantic errors and JIT failures.
    try {
        const std::string astJson = readFile(path);

        if (mode == "--emit-ir") {
            std::cout << minijit::compileToIr(astJson, optimize);
        } else {
            std::cout << minijit::compileAndRun(astJson, entry, optimize) << "\n";
        }
        return 0;

    } catch (const std::exception& e) {
        std::cerr << e.what() << "\n";
        return 1;
    }
}
