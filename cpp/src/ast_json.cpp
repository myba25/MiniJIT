#include "minijit/ast_json.hpp"

#include <nlohmann/json.hpp>

#include <utility>

using nlohmann::json;

namespace minijit {
namespace {

// --- small helpers that fail loudly instead of silently defaulting -----------

const json& field(const json& node, const char* name) {
    auto it = node.find(name);
    if (it == node.end()) {
        throw AstJsonError(std::string("missing field '") + name + "'");
    }
    return *it;
}

std::string kindOf(const json& node) {
    if (!node.is_object()) {
        throw AstJsonError("expected an object node");
    }
    return field(node, "kind").get<std::string>();
}

int lineOf(const json& node) {
    return field(node, "line").get<int>();
}

ExprPtr parseExpr(const json& node);
BlockPtr parseBlock(const json& node);
StmtPtr parseStmt(const json& node);

// --- expressions ---------------------------------------------------------------

ExprPtr parseExpr(const json& node) {
    const std::string kind = kindOf(node);

    // std::make_unique<T>() allocates a T and hands back a unique_ptr that owns
    // it. Returning that unique_ptr moves ownership out to the caller; nothing
    // is copied and nothing leaks if a nested parse throws partway through.
    if (kind == "IntLiteral") {
        auto expr = std::make_unique<IntLiteralExpr>();
        expr->value = field(node, "value").get<std::int64_t>();
        expr->line = lineOf(node);
        return expr;
    }
    if (kind == "Variable") {
        auto expr = std::make_unique<VariableExpr>();
        expr->name = field(node, "name").get<std::string>();
        expr->line = lineOf(node);
        return expr;
    }
    if (kind == "Assign") {
        auto expr = std::make_unique<AssignExpr>();
        expr->name = field(node, "name").get<std::string>();
        expr->value = parseExpr(field(node, "value"));
        expr->line = lineOf(node);
        return expr;
    }
    if (kind == "Binary") {
        auto expr = std::make_unique<BinaryExpr>();
        expr->op = field(node, "op").get<std::string>();
        expr->left = parseExpr(field(node, "left"));
        expr->right = parseExpr(field(node, "right"));
        expr->line = lineOf(node);
        return expr;
    }
    if (kind == "Unary") {
        auto expr = std::make_unique<UnaryExpr>();
        expr->op = field(node, "op").get<std::string>();
        expr->operand = parseExpr(field(node, "operand"));
        expr->line = lineOf(node);
        return expr;
    }
    if (kind == "Call") {
        auto expr = std::make_unique<CallExpr>();
        expr->callee = field(node, "callee").get<std::string>();
        const json& args = field(node, "args");
        if (!args.is_array()) {
            throw AstJsonError("'args' must be an array");
        }
        for (const json& arg : args) {
            expr->args.push_back(parseExpr(arg));
        }
        expr->line = lineOf(node);
        return expr;
    }

    throw AstJsonError("unknown expression kind '" + kind + "'");
}

// --- statements -----------------------------------------------------------------

BlockPtr parseBlock(const json& node) {
    if (kindOf(node) != "Block") {
        throw AstJsonError("expected a Block node");
    }
    auto block = std::make_unique<BlockStmt>();
    block->line = lineOf(node);
    const json& statements = field(node, "statements");
    if (!statements.is_array()) {
        throw AstJsonError("'statements' must be an array");
    }
    for (const json& stmt : statements) {
        block->statements.push_back(parseStmt(stmt));
    }
    return block;
}

StmtPtr parseStmt(const json& node) {
    const std::string kind = kindOf(node);

    if (kind == "Block") {
        return parseBlock(node);
    }
    if (kind == "Let") {
        auto stmt = std::make_unique<LetStmt>();
        stmt->name = field(node, "name").get<std::string>();
        stmt->initializer = parseExpr(field(node, "initializer"));
        stmt->line = lineOf(node);
        return stmt;
    }
    if (kind == "If") {
        auto stmt = std::make_unique<IfStmt>();
        stmt->condition = parseExpr(field(node, "condition"));
        stmt->thenBranch = parseBlock(field(node, "thenBranch"));
        const json& elseBranch = field(node, "elseBranch");
        if (!elseBranch.is_null()) {
            stmt->elseBranch = parseBlock(elseBranch);
        }
        stmt->line = lineOf(node);
        return stmt;
    }
    if (kind == "While") {
        auto stmt = std::make_unique<WhileStmt>();
        stmt->condition = parseExpr(field(node, "condition"));
        stmt->body = parseBlock(field(node, "body"));
        stmt->line = lineOf(node);
        return stmt;
    }
    if (kind == "Return") {
        auto stmt = std::make_unique<ReturnStmt>();
        const json& value = field(node, "value");
        if (!value.is_null()) {
            stmt->value = parseExpr(value);
        }
        stmt->line = lineOf(node);
        return stmt;
    }
    if (kind == "ExprStmt") {
        auto stmt = std::make_unique<ExprStmt>();
        stmt->expression = parseExpr(field(node, "expression"));
        stmt->line = lineOf(node);
        return stmt;
    }

    throw AstJsonError("unknown statement kind '" + kind + "'");
}

Function parseFunction(const json& node) {
    if (kindOf(node) != "Function") {
        throw AstJsonError("expected a Function node");
    }
    Function fn;
    fn.name = field(node, "name").get<std::string>();
    const json& params = field(node, "params");
    if (!params.is_array()) {
        throw AstJsonError("'params' must be an array");
    }
    for (const json& param : params) {
        fn.params.push_back(param.get<std::string>());
    }
    fn.body = parseBlock(field(node, "body"));
    fn.line = lineOf(node);
    return fn;
}

} // namespace

Program parseProgramJson(const std::string& text) {
    json root;
    try {
        root = json::parse(text);
    } catch (const json::parse_error& e) {
        throw AstJsonError(std::string("malformed JSON: ") + e.what());
    }

    const json& functions = field(root, "functions");
    if (!functions.is_array()) {
        throw AstJsonError("'functions' must be an array");
    }

    Program program;
    for (const json& fn : functions) {
        // parseFunction returns a temporary, so push_back move-constructs from it.
        // That matters: Function owns a unique_ptr body and so cannot be copied
        // at all — only moved.
        program.functions.push_back(parseFunction(fn));
    }
    return program;
}

} // namespace minijit
