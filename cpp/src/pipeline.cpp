#include "minijit/pipeline.hpp"

#include "minijit/ast_json.hpp"
#include "minijit/codegen.hpp"
#include "minijit/jit.hpp"

#include <memory>
#include <stdexcept>

namespace minijit {
namespace {

/// The JIT calls the entry point through an `i64 ()` function pointer, so a
/// mismatched arity would read garbage off the stack. Checked here because no
/// compiler or verifier can catch it once the cast has happened.
void requireNullaryEntry(const Program& program, const std::string& entry) {
    for (const Function& function : program.functions) {
        if (function.name == entry) {
            if (!function.params.empty()) {
                throw std::runtime_error("entry function '" + entry +
                                         "' must take no parameters");
            }
            return;
        }
    }
    throw std::runtime_error("no function named '" + entry + "' in the program");
}

} // namespace

std::string compileToIr(const std::string& astJson, bool optimize) {
    const Program program = parseProgramJson(astJson);

    Codegen codegen;
    codegen.compile(program);
    if (optimize) {
        codegen.optimize();
    }
    return codegen.irToString();
}

std::int64_t compileAndRun(const std::string& astJson, const std::string& entry, bool optimize) {
    const Program program = parseProgramJson(astJson);
    requireNullaryEntry(program, entry);

    Codegen codegen;
    codegen.compile(program);
    if (optimize) {
        codegen.optimize();
    }

    std::unique_ptr<Jit> jit = Jit::create();
    jit->addModule(codegen.takeModule());
    return jit->callNullary(entry);
}

} // namespace minijit
