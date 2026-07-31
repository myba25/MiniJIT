package com.minijit.ast;

import java.util.List;

/**
 * MiniJIT expression nodes. Every variant carries its source {@code line}
 * for error reporting during later compiler stages (type checks, codegen).
 */
public sealed interface Expr
        permits Expr.IntLiteral, Expr.Variable, Expr.Assign, Expr.Binary, Expr.Unary, Expr.Call {

    int line();

    /** Integer literal, e.g. {@code 42}. */
    record IntLiteral(long value, int line) implements Expr {}

    /** Reference to a variable or parameter, e.g. {@code x}. */
    record Variable(String name, int line) implements Expr {}

    /** Assignment expression, e.g. {@code x = 5}. Evaluates to the assigned value. */
    record Assign(String name, Expr value, int line) implements Expr {}

    /** Binary operation, e.g. {@code a + b}. {@code operator} is the operator lexeme. */
    record Binary(Expr left, String operator, Expr right, int line) implements Expr {}

    /** Unary operation, e.g. {@code -a}. {@code operator} is the operator lexeme. */
    record Unary(String operator, Expr operand, int line) implements Expr {}

    /** Function call, e.g. {@code add(1, 2)}. */
    record Call(String callee, List<Expr> arguments, int line) implements Expr {}
}
