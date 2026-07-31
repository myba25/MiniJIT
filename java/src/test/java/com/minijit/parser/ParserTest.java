package com.minijit.parser;

import com.minijit.ast.Expr;
import com.minijit.ast.Program;
import com.minijit.ast.Stmt;
import com.minijit.lexer.Lexer;
import com.minijit.lexer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    private static Program parse(String source) {
        List<Token> tokens = new Lexer(source).scanTokens();
        return new Parser(tokens).parseProgram();
    }

    private static List<Stmt> bodyOf(String source) {
        return parse(source).functions().get(0).body().statements();
    }

    @Test
    void parsesEmptyFunction() {
        Program program = parse("fn main() {}");
        assertEquals(1, program.functions().size());
        Stmt.Function fn = program.functions().get(0);
        assertEquals("main", fn.name());
        assertTrue(fn.params().isEmpty());
        assertTrue(fn.body().statements().isEmpty());
    }

    @Test
    void parsesParameters() {
        Stmt.Function fn = parse("fn add(a, b) { return a + b; }").functions().get(0);
        assertEquals(List.of("a", "b"), fn.params());
    }

    @Test
    void parsesMultipleFunctions() {
        Program program = parse("fn a() {} fn b() {}");
        assertEquals(2, program.functions().size());
    }

    @Test
    void parsesLetStatement() {
        Stmt.Let let = assertInstanceOf(Stmt.Let.class, bodyOf("fn f() { let x = 5; }").get(0));
        assertEquals("x", let.name());
        Expr.IntLiteral value = assertInstanceOf(Expr.IntLiteral.class, let.initializer());
        assertEquals(5L, value.value());
    }

    @Test
    void parsesArithmeticPrecedence() {
        // 1 + 2 * 3 should parse as 1 + (2 * 3), not (1 + 2) * 3
        Stmt.ExprStmt stmt = assertInstanceOf(Stmt.ExprStmt.class, bodyOf("fn f() { 1 + 2 * 3; }").get(0));
        Expr.Binary plus = assertInstanceOf(Expr.Binary.class, stmt.expression());
        assertEquals("+", plus.operator());
        assertInstanceOf(Expr.IntLiteral.class, plus.left());
        Expr.Binary times = assertInstanceOf(Expr.Binary.class, plus.right());
        assertEquals("*", times.operator());
    }

    @Test
    void parenthesesOverridePrecedence() {
        // (1 + 2) * 3 should parse as the product being top-level
        Stmt.ExprStmt stmt = assertInstanceOf(Stmt.ExprStmt.class, bodyOf("fn f() { (1 + 2) * 3; }").get(0));
        Expr.Binary times = assertInstanceOf(Expr.Binary.class, stmt.expression());
        assertEquals("*", times.operator());
        assertInstanceOf(Expr.Binary.class, times.left());
    }

    @Test
    void parsesComparisonOperator() {
        Stmt.If ifStmt = assertInstanceOf(Stmt.If.class, bodyOf("fn f() { if (a <= b) {} }").get(0));
        Expr.Binary cond = assertInstanceOf(Expr.Binary.class, ifStmt.condition());
        assertEquals("<=", cond.operator());
    }

    @Test
    void parsesIfElse() {
        Stmt.If ifStmt = assertInstanceOf(
                Stmt.If.class,
                bodyOf("fn f() { if (x) { let a = 1; } else { let b = 2; } }").get(0)
        );
        assertNotNull(ifStmt.elseBranch());
        assertEquals(1, ifStmt.thenBranch().statements().size());
        assertEquals(1, ifStmt.elseBranch().statements().size());
    }

    @Test
    void parsesIfWithoutElse() {
        Stmt.If ifStmt = assertInstanceOf(Stmt.If.class, bodyOf("fn f() { if (x) { } }").get(0));
        assertNull(ifStmt.elseBranch());
    }

    @Test
    void parsesWhileLoop() {
        Stmt.While whileStmt = assertInstanceOf(
                Stmt.While.class,
                bodyOf("fn f() { while (x < 10) { x = x + 1; } }").get(0)
        );
        assertInstanceOf(Expr.Binary.class, whileStmt.condition());
        assertEquals(1, whileStmt.body().statements().size());
    }

    @Test
    void parsesAssignment() {
        Stmt.ExprStmt stmt = assertInstanceOf(Stmt.ExprStmt.class, bodyOf("fn f() { x = 5; }").get(0));
        Expr.Assign assign = assertInstanceOf(Expr.Assign.class, stmt.expression());
        assertEquals("x", assign.name());
    }

    @Test
    void parsesFunctionCallWithArguments() {
        Stmt.ExprStmt stmt = assertInstanceOf(Stmt.ExprStmt.class, bodyOf("fn f() { add(1, 2); }").get(0));
        Expr.Call call = assertInstanceOf(Expr.Call.class, stmt.expression());
        assertEquals("add", call.callee());
        assertEquals(2, call.arguments().size());
    }

    @Test
    void parsesCallWithNoArguments() {
        Stmt.ExprStmt stmt = assertInstanceOf(Stmt.ExprStmt.class, bodyOf("fn f() { tick(); }").get(0));
        Expr.Call call = assertInstanceOf(Expr.Call.class, stmt.expression());
        assertTrue(call.arguments().isEmpty());
    }

    @Test
    void parsesUnaryMinus() {
        Stmt.ExprStmt stmt = assertInstanceOf(Stmt.ExprStmt.class, bodyOf("fn f() { -5; }").get(0));
        Expr.Unary unary = assertInstanceOf(Expr.Unary.class, stmt.expression());
        assertEquals("-", unary.operator());
    }

    @Test
    void parsesReturnWithValue() {
        Stmt.Return ret = assertInstanceOf(Stmt.Return.class, bodyOf("fn f() { return 1 + 2; }").get(0));
        assertInstanceOf(Expr.Binary.class, ret.value());
    }

    @Test
    void parsesReturnWithoutValue() {
        Stmt.Return ret = assertInstanceOf(Stmt.Return.class, bodyOf("fn f() { return; }").get(0));
        assertNull(ret.value());
    }

    @Test
    void nodesCarrySourceLineNumbers() {
        Stmt.Let let = assertInstanceOf(Stmt.Let.class, bodyOf("fn f() {\n  let x = 1;\n}").get(0));
        assertEquals(2, let.line());
    }

    @Test
    void throwsOnMissingSemicolon() {
        assertThrows(ParseError.class, () -> parse("fn f() { let x = 5 }"));
    }

    @Test
    void throwsOnInvalidAssignmentTarget() {
        assertThrows(ParseError.class, () -> parse("fn f() { 1 = 2; }"));
    }

    @Test
    void throwsOnMissingFunctionKeyword() {
        assertThrows(ParseError.class, () -> parse("main() {}"));
    }

    @Test
    void throwsOnUnclosedBlock() {
        assertThrows(ParseError.class, () -> parse("fn f() { let x = 1;"));
    }
}
