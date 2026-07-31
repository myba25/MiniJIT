#pragma once

#include <cstdint>
#include <string>

namespace minijit {

/// The backend's whole job in two calls, shared by the standalone executable
/// and the JNI bridge so neither has to repeat the sequence
/// parse JSON -> codegen -> optimise -> (print | JIT and call).
///
/// Both throw std::exception subclasses on failure: AstJsonError for malformed
/// input, CodegenError for semantic problems, JitError for execution problems.

/// Compiles the AST JSON and returns the textual LLVM IR.
std::string compileToIr(const std::string& astJson, bool optimize);

/// Compiles the AST JSON, JIT-compiles it and calls `entry`, which must be a
/// function taking no parameters. Returns what that function returned.
std::int64_t compileAndRun(const std::string& astJson, const std::string& entry, bool optimize);

} // namespace minijit
