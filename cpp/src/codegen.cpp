#include "minijit/codegen.hpp"

#include <llvm/Config/llvm-config.h>
#include <llvm/IR/BasicBlock.h>
#include <llvm/IR/Constants.h>
#include <llvm/IR/Function.h>
#include <llvm/IR/Intrinsics.h>
#include <llvm/IR/PassManager.h>
#include <llvm/IR/Verifier.h>
#include <llvm/Passes/PassBuilder.h>
#include <llvm/Support/raw_ostream.h>

#include <limits>
#include <utility>

namespace minijit {

using namespace llvm;

Codegen::Codegen()
    : context_(std::make_unique<LLVMContext>()),
      module_(std::make_unique<Module>("minijit", *context_)),
      builder_(std::make_unique<IRBuilder<>>(*context_)) {}

Type* Codegen::i64Type() const {
    return Type::getInt64Ty(*context_);
}

// --- top level -----------------------------------------------------------------

void Codegen::compile(const Program& program) {
    // Two passes. Declaring every prototype first means a function can call one
    // that is defined later in the file, and recursion works without special
    // handling — the callee already exists as a declaration when we emit the call.
    for (const Function& fn : program.functions) {
        declarePrototype(fn);
    }
    for (const Function& fn : program.functions) {
        emitFunctionBody(fn);
    }

    std::string errors;
    raw_string_ostream stream(errors);
    if (verifyModule(*module_, &stream)) {
        throw CodegenError(0, "generated IR failed verification:\n" + stream.str());
    }
}

void Codegen::declarePrototype(const Function& fn) {
    if (module_->getFunction(fn.name) != nullptr) {
        throw CodegenError(fn.line, "function '" + fn.name + "' is already defined");
    }

    // Every MiniJIT value is an i64, so the signature is i64 (i64, i64, ...).
    std::vector<Type*> paramTypes(fn.params.size(), i64Type());
    FunctionType* type = FunctionType::get(i64Type(), paramTypes, /*isVarArg=*/false);

    // The Function is owned by the Module, not by us — hence a raw pointer here
    // rather than a unique_ptr. LLVM's IR objects are arena-owned by their parent.
    llvm::Function::Create(type, llvm::Function::ExternalLinkage, fn.name, module_.get());
}

void Codegen::emitFunctionBody(const Function& fn) {
    llvm::Function* function = module_->getFunction(fn.name);
    if (function == nullptr) {
        throw CodegenError(fn.line, "internal: prototype for '" + fn.name + "' was not declared");
    }

    BasicBlock* entry = BasicBlock::Create(*context_, "entry", function);
    builder_->SetInsertPoint(entry);

    ScopeGuard scope(*this);

    // Copy each incoming argument into a stack slot so it can be reassigned
    // like any other local.
    std::size_t index = 0;
    for (Argument& arg : function->args()) {
        const std::string& name = fn.params[index];
        arg.setName(name);
        AllocaInst* slot = createEntryBlockAlloca(function, name);
        builder_->CreateStore(&arg, slot);
        declareVariable(name, slot);
        ++index;
    }

    emitBlockStatements(*fn.body);

    // A function that runs off the end returns 0, so every path has a value.
    if (!currentBlockTerminated()) {
        builder_->CreateRet(ConstantInt::get(i64Type(), 0, /*IsSigned=*/true));
    }

    // Safety net: a block left unterminated would be invalid IR. This can only
    // happen for blocks made unreachable by a `return` inside if/while.
    for (BasicBlock& block : *function) {
        if (block.getTerminator() == nullptr) {
            IRBuilder<> tail(&block);
            tail.CreateUnreachable();
        }
    }

    std::string errors;
    raw_string_ostream stream(errors);
    if (verifyFunction(*function, &stream)) {
        throw CodegenError(fn.line, "invalid IR for '" + fn.name + "':\n" + stream.str());
    }
}

// --- statements -----------------------------------------------------------------

void Codegen::emitBlockStatements(const BlockStmt& block) {
    for (const StmtPtr& stmt : block.statements) {
        // Everything after a return in the same block is dead; emitting into a
        // terminated block would produce invalid IR.
        if (currentBlockTerminated()) {
            break;
        }
        emitStmt(*stmt);
    }
}

void Codegen::emitStmt(const Stmt& stmt) {
    // dynamic_cast needs RTTI. Our own classes are compiled with RTTI enabled,
    // which is independent of how LLVM itself was built — do not add /GR- or
    // -fno-rtti to this target.
    if (const auto* node = dynamic_cast<const LetStmt*>(&stmt)) {
        emitLet(*node);
        return;
    }
    if (const auto* node = dynamic_cast<const IfStmt*>(&stmt)) {
        emitIf(*node);
        return;
    }
    if (const auto* node = dynamic_cast<const WhileStmt*>(&stmt)) {
        emitWhile(*node);
        return;
    }
    if (const auto* node = dynamic_cast<const ReturnStmt*>(&stmt)) {
        emitReturn(*node);
        return;
    }
    if (const auto* node = dynamic_cast<const ExprStmt*>(&stmt)) {
        emitExpr(*node->expression);   // evaluated for its side effect
        return;
    }
    if (const auto* node = dynamic_cast<const BlockStmt*>(&stmt)) {
        ScopeGuard scope(*this);
        emitBlockStatements(*node);
        return;
    }

    throw CodegenError(stmt.line, "unsupported statement node");
}

void Codegen::emitLet(const LetStmt& stmt) {
    Value* initial = emitExpr(*stmt.initializer);
    llvm::Function* function = builder_->GetInsertBlock()->getParent();
    AllocaInst* slot = createEntryBlockAlloca(function, stmt.name);
    builder_->CreateStore(initial, slot);
    declareVariable(stmt.name, slot);
}

void Codegen::emitIf(const IfStmt& stmt) {
    Value* condition = toCondition(emitExpr(*stmt.condition));
    llvm::Function* function = builder_->GetInsertBlock()->getParent();

    // Blocks are appended to the function in creation order, which keeps the
    // printed IR readable.
    BasicBlock* thenBlock = BasicBlock::Create(*context_, "if.then", function);
    BasicBlock* elseBlock = stmt.elseBranch ? BasicBlock::Create(*context_, "if.else", function)
                                            : nullptr;
    BasicBlock* mergeBlock = BasicBlock::Create(*context_, "if.end", function);

    builder_->CreateCondBr(condition, thenBlock, elseBlock != nullptr ? elseBlock : mergeBlock);

    builder_->SetInsertPoint(thenBlock);
    {
        ScopeGuard scope(*this);
        emitBlockStatements(*stmt.thenBranch);
    }
    // Skip the jump if the branch already ended in a return.
    if (!currentBlockTerminated()) {
        builder_->CreateBr(mergeBlock);
    }

    if (elseBlock != nullptr) {
        builder_->SetInsertPoint(elseBlock);
        {
            ScopeGuard scope(*this);
            emitBlockStatements(*stmt.elseBranch);
        }
        if (!currentBlockTerminated()) {
            builder_->CreateBr(mergeBlock);
        }
    }

    builder_->SetInsertPoint(mergeBlock);
}

void Codegen::emitWhile(const WhileStmt& stmt) {
    llvm::Function* function = builder_->GetInsertBlock()->getParent();

    BasicBlock* condBlock = BasicBlock::Create(*context_, "while.cond", function);
    BasicBlock* bodyBlock = BasicBlock::Create(*context_, "while.body", function);
    BasicBlock* endBlock = BasicBlock::Create(*context_, "while.end", function);

    builder_->CreateBr(condBlock);

    // The condition is re-evaluated on every iteration, so it lives in its own
    // block that the body branches back to.
    builder_->SetInsertPoint(condBlock);
    Value* condition = toCondition(emitExpr(*stmt.condition));
    builder_->CreateCondBr(condition, bodyBlock, endBlock);

    builder_->SetInsertPoint(bodyBlock);
    {
        ScopeGuard scope(*this);
        emitBlockStatements(*stmt.body);
    }
    if (!currentBlockTerminated()) {
        builder_->CreateBr(condBlock);
    }

    builder_->SetInsertPoint(endBlock);
}

void Codegen::emitReturn(const ReturnStmt& stmt) {
    Value* value = stmt.value ? emitExpr(*stmt.value)
                              : ConstantInt::get(i64Type(), 0, /*IsSigned=*/true);
    builder_->CreateRet(value);
}

// --- expressions --------------------------------------------------------------

Value* Codegen::emitExpr(const Expr& expr) {
    if (const auto* node = dynamic_cast<const IntLiteralExpr*>(&expr)) {
        return ConstantInt::get(i64Type(), node->value, /*IsSigned=*/true);
    }
    if (const auto* node = dynamic_cast<const VariableExpr*>(&expr)) {
        AllocaInst* slot = lookupVariable(node->name);
        if (slot == nullptr) {
            throw CodegenError(node->line, "unknown variable '" + node->name + "'");
        }
        // The pointee type must be passed explicitly: LLVM pointers are opaque
        // and no longer carry the type they point at.
        return builder_->CreateLoad(i64Type(), slot, node->name);
    }
    if (const auto* node = dynamic_cast<const AssignExpr*>(&expr)) {
        return emitAssign(*node);
    }
    if (const auto* node = dynamic_cast<const BinaryExpr*>(&expr)) {
        return emitBinary(*node);
    }
    if (const auto* node = dynamic_cast<const UnaryExpr*>(&expr)) {
        return emitUnary(*node);
    }
    if (const auto* node = dynamic_cast<const CallExpr*>(&expr)) {
        return emitCall(*node);
    }

    throw CodegenError(expr.line, "unsupported expression node");
}

Value* Codegen::emitAssign(const AssignExpr& expr) {
    AllocaInst* slot = lookupVariable(expr.name);
    if (slot == nullptr) {
        throw CodegenError(expr.line, "assignment to undeclared variable '" + expr.name + "'");
    }
    Value* value = emitExpr(*expr.value);
    builder_->CreateStore(value, slot);
    return value;   // assignment is an expression: `x = y = 1` works
}

Value* Codegen::emitBinary(const BinaryExpr& expr) {
    Value* left = emitExpr(*expr.left);
    Value* right = emitExpr(*expr.right);
    const std::string& op = expr.op;

    if (op == "+") return builder_->CreateAdd(left, right, "add");
    if (op == "-") return builder_->CreateSub(left, right, "sub");
    if (op == "*") return builder_->CreateMul(left, right, "mul");

    // sdiv and srem are undefined for a zero divisor and for INT64_MIN / -1,
    // so both cases are checked at runtime before the division is reached.
    if (op == "/" || op == "%") {
        emitDivisionGuard(left, right);
        return op == "/" ? builder_->CreateSDiv(left, right, "div")
                         : builder_->CreateSRem(left, right, "rem");
    }

    // Comparisons yield i1, but the language only has i64 values, so widen with
    // zext: false becomes 0, true becomes 1.
    Value* comparison = nullptr;
    if (op == "==") comparison = builder_->CreateICmpEQ(left, right, "eq");
    else if (op == "!=") comparison = builder_->CreateICmpNE(left, right, "ne");
    else if (op == "<") comparison = builder_->CreateICmpSLT(left, right, "lt");
    else if (op == "<=") comparison = builder_->CreateICmpSLE(left, right, "le");
    else if (op == ">") comparison = builder_->CreateICmpSGT(left, right, "gt");
    else if (op == ">=") comparison = builder_->CreateICmpSGE(left, right, "ge");

    if (comparison != nullptr) {
        return builder_->CreateZExt(comparison, i64Type(), "bool");
    }

    throw CodegenError(expr.line, "unsupported binary operator '" + op + "'");
}

Value* Codegen::emitUnary(const UnaryExpr& expr) {
    Value* operand = emitExpr(*expr.operand);
    if (expr.op == "-") {
        return builder_->CreateNeg(operand, "neg");
    }
    throw CodegenError(expr.line, "unsupported unary operator '" + expr.op + "'");
}

Value* Codegen::emitCall(const CallExpr& expr) {
    llvm::Function* callee = module_->getFunction(expr.callee);
    if (callee == nullptr) {
        throw CodegenError(expr.line, "unknown function '" + expr.callee + "'");
    }
    if (callee->arg_size() != expr.args.size()) {
        throw CodegenError(expr.line,
                           "'" + expr.callee + "' expects " + std::to_string(callee->arg_size()) +
                               " argument(s) but got " + std::to_string(expr.args.size()));
    }

    std::vector<Value*> args;
    args.reserve(expr.args.size());
    for (const ExprPtr& arg : expr.args) {
        args.push_back(emitExpr(*arg));
    }
    return builder_->CreateCall(callee, args, "call");
}

// --- helpers ---------------------------------------------------------------------

Value* Codegen::toCondition(Value* value) {
    return builder_->CreateICmpNE(value, ConstantInt::get(i64Type(), 0, /*IsSigned=*/true), "cond");
}

llvm::Function* Codegen::trapDeclaration() {
    // @llvm.trap lowers to a single illegal instruction (ud2 on x86), so the
    // process dies immediately and predictably instead of executing whatever
    // the hardware happens to do with an undefined division.
#if LLVM_VERSION_MAJOR >= 20
    return Intrinsic::getOrInsertDeclaration(module_.get(), Intrinsic::trap);
#else
    // Renamed in LLVM 20; keep working against older headers too.
    return Intrinsic::getDeclaration(module_.get(), Intrinsic::trap);
#endif
}

void Codegen::emitDivisionGuard(Value* dividend, Value* divisor) {
    llvm::Function* function = builder_->GetInsertBlock()->getParent();

    Value* zero = ConstantInt::get(i64Type(), 0, /*IsSigned=*/true);
    Value* minusOne = ConstantInt::get(i64Type(), -1, /*IsSigned=*/true);
    Value* intMin =
        ConstantInt::get(i64Type(), std::numeric_limits<std::int64_t>::min(), /*IsSigned=*/true);

    // Case 1: divisor is zero.
    Value* divideByZero = builder_->CreateICmpEQ(divisor, zero, "div.zero");

    // Case 2: INT64_MIN / -1. The mathematical result is 2^63, which does not
    // fit in a signed 64-bit integer, so LLVM leaves it undefined as well.
    Value* dividendIsMin = builder_->CreateICmpEQ(dividend, intMin, "div.min");
    Value* divisorIsMinusOne = builder_->CreateICmpEQ(divisor, minusOne, "div.negone");
    Value* overflow = builder_->CreateAnd(dividendIsMin, divisorIsMinusOne, "div.ovf");

    Value* invalid = builder_->CreateOr(divideByZero, overflow, "div.bad");

    BasicBlock* trapBlock = BasicBlock::Create(*context_, "div.trap", function);
    BasicBlock* okBlock = BasicBlock::Create(*context_, "div.ok", function);
    builder_->CreateCondBr(invalid, trapBlock, okBlock);

    builder_->SetInsertPoint(trapBlock);
    builder_->CreateCall(trapDeclaration(), {});
    builder_->CreateUnreachable();

    // The caller emits the actual sdiv/srem here, on the path where both
    // operands are known good.
    builder_->SetInsertPoint(okBlock);
}

void Codegen::optimize() {
    // LLVM's "new" pass manager. The four analysis managers cache results at
    // different granularities (loop, function, call graph, module); the proxies
    // let a pass at one level reach analyses at another.
    PassBuilder passBuilder;

    LoopAnalysisManager loopAnalyses;
    FunctionAnalysisManager functionAnalyses;
    CGSCCAnalysisManager cgsccAnalyses;
    ModuleAnalysisManager moduleAnalyses;

    passBuilder.registerModuleAnalyses(moduleAnalyses);
    passBuilder.registerCGSCCAnalyses(cgsccAnalyses);
    passBuilder.registerFunctionAnalyses(functionAnalyses);
    passBuilder.registerLoopAnalyses(loopAnalyses);
    passBuilder.crossRegisterProxies(loopAnalyses, functionAnalyses, cgsccAnalyses, moduleAnalyses);

    ModulePassManager pipeline =
        passBuilder.buildPerModuleDefaultPipeline(OptimizationLevel::O2);
    pipeline.run(*module_, moduleAnalyses);
}

AllocaInst* Codegen::createEntryBlockAlloca(llvm::Function* function, const std::string& name) {
    // mem2reg only promotes allocas that sit in the entry block, so allocate
    // there regardless of where in the function the variable was declared.
    BasicBlock& entry = function->getEntryBlock();
    IRBuilder<> entryBuilder(&entry, entry.begin());
    return entryBuilder.CreateAlloca(i64Type(), nullptr, name);
}

AllocaInst* Codegen::lookupVariable(const std::string& name) const {
    // Innermost scope first, so an inner declaration shadows an outer one.
    for (auto scope = scopes_.rbegin(); scope != scopes_.rend(); ++scope) {
        auto found = scope->find(name);
        if (found != scope->end()) {
            return found->second;
        }
    }
    return nullptr;
}

void Codegen::declareVariable(const std::string& name, AllocaInst* slot) {
    scopes_.back()[name] = slot;
}

bool Codegen::currentBlockTerminated() const {
    BasicBlock* block = builder_->GetInsertBlock();
    return block != nullptr && block->getTerminator() != nullptr;
}

std::string Codegen::irToString() const {
    std::string text;
    raw_string_ostream stream(text);
    module_->print(stream, nullptr);
    return stream.str();
}

orc::ThreadSafeModule Codegen::takeModule() {
    // Both the module and the context move into the ThreadSafeModule: LLJIT
    // needs to own the context for as long as the code is alive.
    return orc::ThreadSafeModule(std::move(module_), std::move(context_));
}

} // namespace minijit
