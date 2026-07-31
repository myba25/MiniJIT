#pragma once

#include <llvm/ExecutionEngine/Orc/LLJIT.h>
#include <llvm/ExecutionEngine/Orc/ThreadSafeModule.h>

#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>

namespace minijit {

/// Thrown when the JIT cannot be built, cannot accept a module, or cannot find
/// a symbol.
class JitError : public std::runtime_error {
public:
    explicit JitError(const std::string& message)
        : std::runtime_error("JIT error: " + message) {}
};

/// Thin wrapper over ORC v2's LLJIT: compiles IR to native code in memory and
/// looks up the resulting function pointers.
class Jit {
public:
    /// Initialises the native target and builds an LLJIT instance.
    static std::unique_ptr<Jit> create();

    /// Hands a module over to the JIT. Compilation is lazy — the code is only
    /// materialised when one of its symbols is first looked up.
    void addModule(llvm::orc::ThreadSafeModule module);

    /// Looks up a zero-argument `i64 ()` function and calls it.
    std::int64_t callNullary(const std::string& name);

private:
    explicit Jit(std::unique_ptr<llvm::orc::LLJIT> lljit) : lljit_(std::move(lljit)) {}

    std::unique_ptr<llvm::orc::LLJIT> lljit_;
};

} // namespace minijit
