package com.minijit.ast;

import java.util.List;

/**
 * MiniJIT statement nodes. Every variant carries its source {@code line}
 * for error reporting during later compiler stages.
 */
public sealed interface Stmt
        permits Stmt.Let, Stmt.If, Stmt.While, Stmt.Return, Stmt.ExprStmt, Stmt.Block, Stmt.Function {

    int line();

    /** {@code let x = <initializer>;} */
    record Let(String name, Expr initializer, int line) implements Stmt {}

    /** {@code if (<condition>) <thenBranch> [else <elseBranch>]}. {@code elseBranch} is null if absent. */
    record If(Expr condition, Block thenBranch, Block elseBranch, int line) implements Stmt {}

    /** {@code while (<condition>) <body>} */
    record While(Expr condition, Block body, int line) implements Stmt {}

    /** {@code return [<value>];}. {@code value} is null for a bare return. */
    record Return(Expr value, int line) implements Stmt {}

    /** An expression evaluated for its side effect, e.g. a call or assignment, terminated by ';'. */
    record ExprStmt(Expr expression, int line) implements Stmt {}

    /** {@code { <statements> }} */
    record Block(List<Stmt> statements, int line) implements Stmt {}

    /** {@code fn <name>(<params>) <body>} */
    record Function(String name, List<String> params, Block body, int line) implements Stmt {}
}
