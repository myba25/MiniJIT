#include "minijit/jit.hpp"

#include <llvm/ExecutionEngine/Orc/ExecutionUtils.h>
#include <llvm/Support/Error.h>
#include <llvm/Support/TargetSelect.h>

#include <utility>

namespace minijit {

using namespace llvm;

std::unique_ptr<Jit> Jit::create() {
    // Registers the code generator and assembly printer for the machine we are
    // running on. Without these LLJITBuilder has no target to compile for.
    // Calling them more than once is harmless.
    // (No InitializeNativeTargetAsmParser here: that is only needed for parsing
    // inline assembly, and pulling it in would force another LLVM target
    // library into the link line.)
    InitializeNativeTarget();
    InitializeNativeTargetAsmPrinter();

    // LLVM signals failure with Expected<T> rather than exceptions: the value is
    // either a result or an Error, and the Error *must* be consumed or the
    // program aborts at runtime. takeError() below does that consuming.
    Expected<std::unique_ptr<orc::LLJIT>> lljit = orc::LLJITBuilder().create();
    if (!lljit) {
        throw JitError(toString(lljit.takeError()));
    }

    auto jit = std::unique_ptr<Jit>(new Jit(std::move(*lljit)));

    // Let JIT'd code resolve symbols from the host process. Compiler-generated
    // helper calls (stack probes, integer intrinsics) are looked up this way,
    // and it is what will let MiniJIT call into C runtime functions later.
    char prefix = jit->lljit_->getDataLayout().getGlobalPrefix();
    Expected<std::unique_ptr<orc::DynamicLibrarySearchGenerator>> generator =
        orc::DynamicLibrarySearchGenerator::GetForCurrentProcess(prefix);
    if (!generator) {
        throw JitError(toString(generator.takeError()));
    }
    jit->lljit_->getMainJITDylib().addGenerator(std::move(*generator));

    return jit;
}

void Jit::addModule(orc::ThreadSafeModule module) {
    if (Error error = lljit_->addIRModule(std::move(module))) {
        throw JitError(toString(std::move(error)));
    }
}

std::int64_t Jit::callNullary(const std::string& name) {
    Expected<orc::ExecutorAddr> address = lljit_->lookup(name);
    if (!address) {
        throw JitError("symbol '" + name + "' not found: " + toString(address.takeError()));
    }

    // toPtr reinterprets the JIT'd address as a callable function pointer. The
    // signature must match what codegen emitted exactly — a mismatch here is
    // undefined behaviour that no compiler or verifier can catch, which is why
    // the caller checks the arity before getting this far.
    auto function = address->toPtr<std::int64_t (*)()>();
    return function();
}

} // namespace minijit
