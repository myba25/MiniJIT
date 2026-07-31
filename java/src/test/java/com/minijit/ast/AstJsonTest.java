package com.minijit.ast;

import com.minijit.lexer.Lexer;
import com.minijit.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstJsonTest {

    private static String json(String source) {
        return AstJson.toJson(new Parser(new Lexer(source).scanTokens()).parseProgram());
    }

    @Test
    void serializesEmptyProgram() {
        assertEquals("{\"functions\":[]}", json(""));
    }

    @Test
    void serializesEmptyFunction() {
        assertEquals(
                "{\"functions\":[{\"kind\":\"Function\",\"name\":\"main\",\"params\":[],\"line\":1,"
                        + "\"body\":{\"kind\":\"Block\",\"line\":1,\"statements\":[]}}]}",
                json("fn main() {}")
        );
    }

    @Test
    void serializesParameters() {
        assertTrue(json("fn add(a, b) {}").contains("\"params\":[\"a\",\"b\"]"));
    }

    @Test
    void serializesIntLiteral() {
        assertTrue(json("fn f() { 42; }").contains("{\"kind\":\"IntLiteral\",\"value\":42,\"line\":1}"));
    }

    @Test
    void serializesBinaryWithOperandsNested() {
        String out = json("fn f() { return a + 1; }");
        assertTrue(out.contains("\"kind\":\"Binary\",\"op\":\"+\""));
        assertTrue(out.contains("\"left\":{\"kind\":\"Variable\",\"name\":\"a\""));
        assertTrue(out.contains("\"right\":{\"kind\":\"IntLiteral\",\"value\":1"));
    }

    @Test
    void serializesUnary() {
        assertTrue(json("fn f() { -x; }").contains("\"kind\":\"Unary\",\"op\":\"-\""));
    }

    @Test
    void serializesAssignment() {
        assertTrue(json("fn f() { x = 1; }").contains("\"kind\":\"Assign\",\"name\":\"x\""));
    }

    @Test
    void serializesCallWithArgs() {
        String out = json("fn f() { add(1, 2); }");
        assertTrue(out.contains("\"kind\":\"Call\",\"callee\":\"add\""));
        assertTrue(out.contains("\"args\":["));
    }

    @Test
    void serializesLet() {
        assertTrue(json("fn f() { let x = 5; }").contains("\"kind\":\"Let\",\"name\":\"x\""));
    }

    @Test
    void serializesIfWithNullElse() {
        assertTrue(json("fn f() { if (x) {} }").contains("\"elseBranch\":null"));
    }

    @Test
    void serializesIfWithElseBranch() {
        String out = json("fn f() { if (x) {} else {} }");
        assertTrue(out.contains("\"elseBranch\":{\"kind\":\"Block\""));
    }

    @Test
    void serializesWhile() {
        String out = json("fn f() { while (i < 3) { i = i + 1; } }");
        assertTrue(out.contains("\"kind\":\"While\""));
        assertTrue(out.contains("\"body\":{\"kind\":\"Block\""));
    }

    @Test
    void serializesBareReturnAsNullValue() {
        assertTrue(json("fn f() { return; }").contains("\"kind\":\"Return\",\"line\":1,\"value\":null"));
    }

    @Test
    void serializesMultipleFunctionsCommaSeparated() {
        String out = json("fn a() {} fn b() {}");
        assertTrue(out.contains("\"name\":\"a\""));
        assertTrue(out.contains("\"name\":\"b\""));
        assertTrue(out.contains("}},{\"kind\":\"Function\",\"name\":\"b\""));
    }

    @Test
    void nestedBlockStatementsAreSerialized() {
        String out = json("fn f() { { let x = 1; } }");
        // outer function block contains an inner block node
        assertTrue(out.contains("\"statements\":[{\"kind\":\"Block\""));
    }
}
