package com.minijit.lexer;

/**
 * A single lexical token.
 *
 * @param type    the token's kind
 * @param lexeme  the exact source text that produced this token
 * @param literal the decoded literal value (e.g. Long for INT), or null if not applicable
 * @param line    1-based source line the token starts on, for error messages
 */
public record Token(TokenType type, String lexeme, Object literal, int line) {

    @Override
    public String toString() {
        return literal != null
                ? "%s(%s)".formatted(type, literal)
                : "%s('%s')".formatted(type, lexeme);
    }
}
