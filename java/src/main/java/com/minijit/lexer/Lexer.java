package com.minijit.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hand-written scanner that turns MiniJIT source text into a flat list of {@link Token}s.
 *
 * Usage:
 * <pre>{@code
 * List<Token> tokens = new Lexer(source).scanTokens();
 * }</pre>
 *
 * Throws {@link LexError} on the first unrecognized character or malformed literal;
 * MiniJIT does not attempt error recovery at the lexer stage.
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.of(
            "fn", TokenType.FN,
            "return", TokenType.RETURN,
            "let", TokenType.LET,
            "if", TokenType.IF,
            "else", TokenType.ELSE,
            "while", TokenType.WHILE
    );

    private final String source;
    private final List<Token> tokens = new ArrayList<>();

    private int start = 0;   // start of the lexeme currently being scanned
    private int current = 0; // index of the next unconsumed character
    private int line = 1;

    public Lexer(String source) {
        this.source = source;
    }

    public List<Token> scanTokens() {
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }
        tokens.add(new Token(TokenType.EOF, "", null, line));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case '(' -> addToken(TokenType.LPAREN);
            case ')' -> addToken(TokenType.RPAREN);
            case '{' -> addToken(TokenType.LBRACE);
            case '}' -> addToken(TokenType.RBRACE);
            case ',' -> addToken(TokenType.COMMA);
            case ';' -> addToken(TokenType.SEMICOLON);
            case '+' -> addToken(TokenType.PLUS);
            case '-' -> addToken(TokenType.MINUS);
            case '*' -> addToken(TokenType.STAR);
            case '%' -> addToken(TokenType.PERCENT);
            case '=' -> addToken(match('=') ? TokenType.EQ : TokenType.ASSIGN);
            case '!' -> {
                if (match('=')) {
                    addToken(TokenType.NOT_EQ);
                } else {
                    throw new LexError(line, "Unexpected character '!' (did you mean '!='?)");
                }
            }
            case '<' -> addToken(match('=') ? TokenType.LESS_EQ : TokenType.LESS);
            case '>' -> addToken(match('=') ? TokenType.GREATER_EQ : TokenType.GREATER);
            case '/' -> {
                if (match('/')) {
                    // Line comment: consume until end of line.
                    while (peek() != '\n' && !isAtEnd()) {
                        advance();
                    }
                } else {
                    addToken(TokenType.SLASH);
                }
            }
            case ' ', '\r', '\t' -> { /* ignore whitespace */ }
            case '\n' -> line++;
            default -> {
                if (isDigit(c)) {
                    number();
                } else if (isAlpha(c)) {
                    identifier();
                } else {
                    throw new LexError(line, "Unexpected character '%c'".formatted(c));
                }
            }
        }
    }

    private void number() {
        while (isDigit(peek())) {
            advance();
        }
        // NOTE: no decimal-point support yet; MiniJIT currently models integers only.
        String text = source.substring(start, current);
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new LexError(line, "Integer literal out of range: '%s'".formatted(text));
        }
        addToken(TokenType.INT, value);
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) {
            advance();
        }
        String text = source.substring(start, current);
        TokenType type = KEYWORDS.getOrDefault(text, TokenType.IDENT);
        addToken(type);
    }

    // --- low-level cursor helpers -------------------------------------------------

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char advance() {
        return source.charAt(current++);
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(current) != expected) {
            return false;
        }
        current++;
        return true;
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(current);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private void addToken(TokenType type) {
        addToken(type, null);
    }

    private void addToken(TokenType type, Object literal) {
        String lexeme = source.substring(start, current);
        tokens.add(new Token(type, lexeme, literal, line));
    }
}
