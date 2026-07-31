package com.minijit.lexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LexerTest {

    private static List<TokenType> types(String source) {
        return new Lexer(source).scanTokens().stream().map(Token::type).toList();
    }

    @Test
    void emptySourceProducesOnlyEof() {
        assertEquals(List.of(TokenType.EOF), types(""));
    }

    @Test
    void scansIntegerLiteralWithCorrectValue() {
        List<Token> tokens = new Lexer("42").scanTokens();
        assertEquals(TokenType.INT, tokens.get(0).type());
        assertEquals(42L, tokens.get(0).literal());
        assertEquals(TokenType.EOF, tokens.get(1).type());
    }

    @Test
    void scansArithmeticExpression() {
        assertEquals(
                List.of(TokenType.INT, TokenType.PLUS, TokenType.INT, TokenType.STAR,
                        TokenType.INT, TokenType.EOF),
                types("1 + 2 * 3")
        );
    }

    @Test
    void scansAllComparisonOperators() {
        assertEquals(
                List.of(TokenType.EQ, TokenType.NOT_EQ, TokenType.LESS, TokenType.LESS_EQ,
                        TokenType.GREATER, TokenType.GREATER_EQ, TokenType.ASSIGN, TokenType.EOF),
                types("== != < <= > >= =")
        );
    }

    @Test
    void recognizesKeywords() {
        assertEquals(
                List.of(TokenType.FN, TokenType.LET, TokenType.IF, TokenType.ELSE,
                        TokenType.WHILE, TokenType.RETURN, TokenType.EOF),
                types("fn let if else while return")
        );
    }

    @Test
    void identifierNotConfusedWithKeywordPrefix() {
        // "ifx" must lex as one identifier, not IF followed by IDENT "x".
        List<Token> tokens = new Lexer("ifx").scanTokens();
        assertEquals(TokenType.IDENT, tokens.get(0).type());
        assertEquals("ifx", tokens.get(0).lexeme());
    }

    @Test
    void scansFunctionSignaturePunctuation() {
        assertEquals(
                List.of(TokenType.FN, TokenType.IDENT, TokenType.LPAREN, TokenType.IDENT,
                        TokenType.COMMA, TokenType.IDENT, TokenType.RPAREN, TokenType.LBRACE,
                        TokenType.RBRACE, TokenType.EOF),
                types("fn add(a, b) {}")
        );
    }

    @Test
    void ignoresLineComments() {
        assertEquals(
                List.of(TokenType.INT, TokenType.EOF),
                types("1 // this is a comment\n")
        );
    }

    @Test
    void tracksLineNumbersAcrossNewlines() {
        List<Token> tokens = new Lexer("1\n2\n3").scanTokens();
        assertEquals(1, tokens.get(0).line());
        assertEquals(2, tokens.get(1).line());
        assertEquals(3, tokens.get(2).line());
    }

    @Test
    void throwsOnUnexpectedCharacter() {
        LexError error = assertThrows(LexError.class, () -> types("@"));
        assertEquals(1, error.line);
    }

    @Test
    void throwsOnBareBang() {
        // '!' alone is not a valid token; only '!=' is supported.
        assertThrows(LexError.class, () -> types("!"));
    }

    @Test
    void semicolonTerminatedStatement() {
        assertEquals(
                List.of(TokenType.LET, TokenType.IDENT, TokenType.ASSIGN, TokenType.INT,
                        TokenType.SEMICOLON, TokenType.EOF),
                types("let x = 5;")
        );
    }
}
