#pragma once

#include "minijit/ast.hpp"

#include <llvm/IR/IRBuilder.h>
#include <llvm/IR/LLVMContext.h>
#include <llvm/IR/Module.h>
#include <llvm/ExecutionEngine/Orc/ThreadSafeModule.h>

#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace minijit {

/// Thrown for semantic problems the frontend cannot catch: unknown variable,
/// unknown function, wrong argument count, unsupported operator.
class CodegenError : public std::runtime_error {
public:
    CodegenError(int line, const std::string& message)
        : std::runtime_error("[line " + std::to_string(line) + "] Codegen error: " + message),
          line(line) {}

    int line;
};

/// Lowers a MiniJIT Program to LLVM IR.
///
/// Every value in the language is a 64-bit signed integer, so every function
/// has type `i64 (i64, i64, ...)`. Conditions follow C's rule: nonzero is true.
///
/// Local variables are stack slots (`alloca`) written with `store` and read with
/// `load`, rather than raw SSA values. That is the Kaleidoscope chapter 7
/// approach: it keeps mutation and control flow simple, and the mem2reg pass
/// promotes the slots into SSA registers with proper phi nodes afterwards.
class Codegen {
public:
    Codegen();

    /// Emits IR for the whole program. Throws CodegenError on semantic errors.
    void compile(const Program& program);

    /// Runs LLVM's standard -O2 pipeline over the module. The important pass for
    /// us is mem2reg (inside SROA): it promotes the alloca slots that codegen
    /// emits into SSA registers, inserting phi nodes at control-flow joins.
    /// Optional — the IR is already correct without it, just verbose.
    void optimize();

    /// Textual LLVM IR, for --emit-ir and for debugging.
    std::string irToString() const;

    /// Hands the module and its context to the JIT. Call at most once:
    /// afterwards this Codegen no longer owns a module.
    llvm::orc::ThreadSafeModule takeModule();

private:
    // The context owns all LLVM types and constants; the module owns the
    // functions. LLJIT requires both to be moved into a ThreadSafeModule
    // together, which is why the context is held by unique_ptr rather than
    // being a plain member.
    std::unique_ptr<llvm::LLVMContext> context_;
    std::unique_ptr<llvm::Module> module_;
    std::unique_ptr<llvm::IRBuilder<>> builder_;

    /// Lexical scopes, innermost last. Each maps a variable name to its stack slot.
    std::vector<std::map<std::string, llvm::AllocaInst*>> scopes_;

    llvm::Type* i64Type() const;

    void declarePrototype(const Function& fn);
    void emitFunctionBody(const Function& fn);

    void emitBlockStatements(const BlockStmt& block);
    void emitStmt(const Stmt& stmt);
    void emitLet(const LetStmt& stmt);
    void emitIf(const IfStmt& stmt);
    void emitWhile(const WhileStmt& stmt);
    void emitReturn(const ReturnStmt& stmt);

    llvm::Value* emitExpr(const Expr& expr);
    llvm::Value* emitBinary(const BinaryExpr& expr);
    llvm::Value* emitUnary(const UnaryExpr& expr);
    llvm::Value* emitCall(const CallExpr& expr);
    llvm::Value* emitAssign(const AssignExpr& expr);

    /// Truncates an i64 to the i1 that a conditional branch needs: `value != 0`.
    llvm::Value* toCondition(llvm::Value* value);

    /// Emits a runtime check in front of sdiv/srem that traps on the two input
    /// combinations LLVM leaves undefined. Leaves the builder positioned in the
    /// block where the division itself should go.
    void emitDivisionGuard(llvm::Value* dividend, llvm::Value* divisor);

    /// Declaration of @llvm.trap, created on first use.
    llvm::Function* trapDeclaration();

    /// Creates an alloca in the function's entry block, where mem2reg expects it.
    llvm::AllocaInst* createEntryBlockAlloca(llvm::Function* function, const std::string& name);

    llvm::AllocaInst* lookupVariable(const std::string& name) const;
    void declareVariable(const std::string& name, llvm::AllocaInst* slot);

    /// True when the block we are currently writing into already ends in a
    /// terminator (ret or br). Nothing more may be appended to such a block.
    bool currentBlockTerminated() const;

    /// RAII guard: pushes a scope on construction, pops it on destruction — so
    /// the scope is popped even if codegen throws partway through the block.
    class ScopeGuard {
    public:
        explicit ScopeGuard(Codegen& owner) : owner_(owner) { owner_.scopes_.emplace_back(); }
        ~ScopeGuard() { owner_.scopes_.pop_back(); }

        ScopeGuard(const ScopeGuard&) = delete;
        ScopeGuard& operator=(const ScopeGuard&) = delete;

    private:
        Codegen& owner_;
    };
};

} // namespace minijit
