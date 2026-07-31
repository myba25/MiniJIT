package com.minijit.parser;

/** Thrown when the parser encounters a token sequence that doesn't match the grammar. */
public class ParseError extends RuntimeException {

    public final int line;

    public ParseError(int line, String message) {
        super("[line %d] Parse error: %s".formatted(line, message));
        this.line = line;
    }
}
