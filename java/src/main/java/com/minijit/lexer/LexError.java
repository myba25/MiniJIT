package com.minijit.lexer;

/** Thrown when the lexer encounters source text it cannot tokenize. */
public class LexError extends RuntimeException {

    public final int line;

    public LexError(int line, String message) {
        super("[line %d] Lex error: %s".formatted(line, message));
        this.line = line;
    }
}
