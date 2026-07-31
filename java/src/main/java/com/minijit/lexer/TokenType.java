package com.minijit.lexer;

/**
 * All token kinds MiniJIT's lexer can produce.
 *
 * The language covers arithmetic, comparisons, variable declarations,
 * if/while control flow, and function definitions/calls.
 */
public enum TokenType {
    // Literals
    INT,        // 123
    IDENT,      // foo, bar_baz

    // Keywords
    FN,         // fn
    RETURN,     // return
    LET,        // let
    IF,         // if
    ELSE,       // else
    WHILE,      // while

    // Single/multi-character operators
    PLUS,       // +
    MINUS,      // -
    STAR,       // *
    SLASH,      // /
    PERCENT,    // %
    ASSIGN,     // =
    EQ,         // ==
    NOT_EQ,     // !=
    LESS,       // <
    LESS_EQ,    // <=
    GREATER,    // >
    GREATER_EQ, // >=

    // Punctuation
    LPAREN,     // (
    RPAREN,     // )
    LBRACE,     // {
    RBRACE,     // }
    COMMA,      // ,
    SEMICOLON,  // ;

    EOF
}
