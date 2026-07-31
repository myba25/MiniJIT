#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

// C++ mirror of the Java AST (com.minijit.ast). The Java frontend serializes
// its AST to JSON; ast_json.cpp rebuilds these structures from that JSON.
//
// --- C++ concepts used here, for reference ---------------------------------
//
// std::unique_ptr<T> is a smart pointer that owns exactly one heap object and
// frees it automatically when the pointer goes out of scope. That is RAII:
// the destructor does the cleanup, so there is no `delete` anywhere and no way
// to leak if an exception is thrown midway. unique_ptr cannot be copied (that
// would mean two owners), only *moved* with std::move, which transfers
// ownership and leaves the source pointer null.
//
// A tree is the natural fit for unique_ptr: each node owns its children, and
// destroying the root recursively destroys everything below it.
//
// `virtual ~Expr() = default;` matters: deleting a derived object through a
// base pointer without a virtual destructor is undefined behaviour. Since we
// hold children as unique_ptr<Expr> that actually point at BinaryExpr and
// friends, the base destructor must be virtual.

namespace minijit {

// --- expressions ------------------------------------------------------------

struct Expr {
    virtual ~Expr() = default;
    int line = 0;
};

using ExprPtr = std::unique_ptr<Expr>;

struct IntLiteralExpr : Expr {
    std::int64_t value = 0;
};

struct VariableExpr : Expr {
    std::string name;
};

struct AssignExpr : Expr {
    std::string name;
    ExprPtr value;
};

struct BinaryExpr : Expr {
    std::string op;   // "+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">", ">="
    ExprPtr left;
    ExprPtr right;
};

struct UnaryExpr : Expr {
    std::string op;   // "-"
    ExprPtr operand;
};

struct CallExpr : Expr {
    std::string callee;
    std::vector<ExprPtr> args;
};

// --- statements ---------------------------------------------------------------

struct Stmt {
    virtual ~Stmt() = default;
    int line = 0;
};

using StmtPtr = std::unique_ptr<Stmt>;

struct BlockStmt : Stmt {
    std::vector<StmtPtr> statements;
};

using BlockPtr = std::unique_ptr<BlockStmt>;

struct LetStmt : Stmt {
    std::string name;
    ExprPtr initializer;
};

struct IfStmt : Stmt {
    ExprPtr condition;
    BlockPtr thenBranch;
    BlockPtr elseBranch;   // null when there is no else
};

struct WhileStmt : Stmt {
    ExprPtr condition;
    BlockPtr body;
};

struct ReturnStmt : Stmt {
    ExprPtr value;         // null for a bare `return;`
};

struct ExprStmt : Stmt {
    ExprPtr expression;
};

// --- top level -----------------------------------------------------------------

struct Function {
    std::string name;
    std::vector<std::string> params;
    BlockPtr body;
    int line = 0;
};

struct Program {
    std::vector<Function> functions;
};

} // namespace minijit
