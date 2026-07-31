package com.minijit.parser;

import com.minijit.ast.Expr;
import com.minijit.ast.Program;
import com.minijit.ast.Stmt;
import com.minijit.lexer.Token;
import com.minijit.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

import static com.minijit.lexer.TokenType.*;

/**
 * Recursive-descent parser producing a {@link Program} AST from a token stream.
 *
 * Grammar (lowest to highest expression precedence):
 * <pre>
 * program     -> function* EOF
 * function    -> "fn" IDENT "(" ( IDENT ( "," IDENT )* )? ")" block
 * block       -> "{" statement* "}"
 * statement   -> letStmt | ifStmt | whileStmt | returnStmt | block | exprStmt
 * letStmt     -> "let" IDENT "=" expression ";"
 * ifStmt      -> "if" "(" expression ")" block ( "else" block )?
 * whileStmt   -> "while" "(" expression ")" block
 * returnStmt  -> "return" expression? ";"
 * exprStmt    -> expression ";"
 * expression  -> assignment
 * assignment  -> IDENT "=" assignment | equality
 * equality    -> comparison ( ( "==" | "!=" ) comparison )*
 * comparison  -> term ( ( "&lt;" | "&lt;=" | "&gt;" | "&gt;=" ) term )*
 * term        -> factor ( ( "+" | "-" ) factor )*
 * factor      -> unary ( ( "*" | "/" | "%" ) unary )*
 * unary       -> "-" unary | call
 * call        -> primary ( "(" ( expression ( "," expression )* )? ")" )*
 * primary     -> INT | IDENT | "(" expression ")"
 * </pre>
 *
 * Throws {@link ParseError} on the first grammar violation; MiniJIT does not
 * attempt error recovery / multi-error reporting at this stage.
 */
public final class Parser {

    private final List<Token> tokens;
    private int current = 0;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public Program parseProgram() {
        List<Stmt.Function> functions = new ArrayList<>();
        while (!isAtEnd()) {
            functions.add(function());
        }
        return new Program(functions);
    }

    // --- declarations -------------------------------------------------------

    private Stmt.Function function() {
        Token fnKeyword = consume(FN, "Expect 'fn' to start a function declaration.");
        Token name = consume(IDENT, "Expect function name.");
        consume(LPAREN, "Expect '(' after function name.");
        List<String> params = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                params.add(consume(IDENT, "Expect parameter name.").lexeme());
            } while (match(COMMA));
        }
        consume(RPAREN, "Expect ')' after parameters.");
        Stmt.Block body = block();
        return new Stmt.Function(name.lexeme(), params, body, fnKeyword.line());
    }

    private Stmt.Block block() {
        Token brace = consume(LBRACE, "Expect '{' to start a block.");
        List<Stmt> statements = new ArrayList<>();
        while (!check(RBRACE) && !isAtEnd()) {
            statements.add(statement());
        }
        consume(RBRACE, "Expect '}' to close a block.");
        return new Stmt.Block(statements, brace.line());
    }

    // --- statements -----------------------------------------------------------

    private Stmt statement() {
        if (check(LET)) return letStatement();
        if (check(IF)) return ifStatement();
        if (check(WHILE)) return whileStatement();
        if (check(RETURN)) return returnStatement();
        if (check(LBRACE)) return block();
        return exprStatement();
    }

    private Stmt letStatement() {
        Token keyword = advance(); // 'let'
        Token name = consume(IDENT, "Expect variable name after 'let'.");
        consume(ASSIGN, "Expect '=' after variable name.");
        Expr initializer = expression();
        consume(SEMICOLON, "Expect ';' after variable declaration.");
        return new Stmt.Let(name.lexeme(), initializer, keyword.line());
    }

    private Stmt ifStatement() {
        Token keyword = advance(); // 'if'
        consume(LPAREN, "Expect '(' after 'if'.");
        Expr condition = expression();
        consume(RPAREN, "Expect ')' after if condition.");
        Stmt.Block thenBranch = block();
        Stmt.Block elseBranch = null;
        if (match(ELSE)) {
            elseBranch = block();
        }
        return new Stmt.If(condition, thenBranch, elseBranch, keyword.line());
    }

    private Stmt whileStatement() {
        Token keyword = advance(); // 'while'
        consume(LPAREN, "Expect '(' after 'while'.");
        Expr condition = expression();
        consume(RPAREN, "Expect ')' after while condition.");
        Stmt.Block body = block();
        return new Stmt.While(condition, body, keyword.line());
    }

    private Stmt returnStatement() {
        Token keyword = advance(); // 'return'
        Expr value = check(SEMICOLON) ? null : expression();
        consume(SEMICOLON, "Expect ';' after return statement.");
        return new Stmt.Return(value, keyword.line());
    }

    private Stmt exprStatement() {
        int line = peek().line();
        Expr expr = expression();
        consume(SEMICOLON, "Expect ';' after expression.");
        return new Stmt.ExprStmt(expr, line);
    }

    // --- expressions, lowest to highest precedence -----------------------------

    private Expr expression() {
        return assignment();
    }

    private Expr assignment() {
        Expr expr = equality();
        if (match(ASSIGN)) {
            Token equals = previous();
            Expr value = assignment(); // right-associative: a = b = c
            if (expr instanceof Expr.Variable v) {
                return new Expr.Assign(v.name(), value, equals.line());
            }
            throw error(equals, "Invalid assignment target.");
        }
        return expr;
    }

    private Expr equality() {
        Expr expr = comparison();
        while (match(EQ) || match(NOT_EQ)) {
            Token operator = previous();
            Expr right = comparison();
            expr = new Expr.Binary(expr, operator.lexeme(), right, operator.line());
        }
        return expr;
    }

    private Expr comparison() {
        Expr expr = term();
        while (match(LESS) || match(LESS_EQ) || match(GREATER) || match(GREATER_EQ)) {
            Token operator = previous();
            Expr right = term();
            expr = new Expr.Binary(expr, operator.lexeme(), right, operator.line());
        }
        return expr;
    }

    private Expr term() {
        Expr expr = factor();
        while (match(PLUS) || match(MINUS)) {
            Token operator = previous();
            Expr right = factor();
            expr = new Expr.Binary(expr, operator.lexeme(), right, operator.line());
        }
        return expr;
    }

    private Expr factor() {
        Expr expr = unary();
        while (match(STAR) || match(SLASH) || match(PERCENT)) {
            Token operator = previous();
            Expr right = unary();
            expr = new Expr.Binary(expr, operator.lexeme(), right, operator.line());
        }
        return expr;
    }

    private Expr unary() {
        if (match(MINUS)) {
            Token operator = previous();
            Expr operand = unary(); // recurse: handles "--x" too
            return new Expr.Unary(operator.lexeme(), operand, operator.line());
        }
        return call();
    }

    private Expr call() {
        Expr expr = primary();
        while (match(LPAREN)) {
            expr = finishCall(expr);
        }
        return expr;
    }

    private Expr finishCall(Expr callee) {
        Token paren = previous(); // '(' just consumed by call(); its line anchors the call node
        if (!(callee instanceof Expr.Variable v)) {
            throw error(paren, "Can only call functions by name.");
        }
        List<Expr> arguments = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                arguments.add(expression());
            } while (match(COMMA));
        }
        consume(RPAREN, "Expect ')' after arguments.");
        return new Expr.Call(v.name(), arguments, paren.line());
    }

    private Expr primary() {
        Token t = peek();
        if (match(INT)) {
            // Token.literal() is declared as Object; the lexer always stores a Long
            // for INT tokens, so the cast-then-unbox is safe. Spelled out rather than
            // written as a bare (long) cast, which silently compiles to the same thing
            // but hides the ClassCastException risk if the lexer ever changes.
            Long value = (Long) previous().literal();
            return new Expr.IntLiteral(value.longValue(), t.line());
        }
        if (match(IDENT)) {
            return new Expr.Variable(previous().lexeme(), t.line());
        }
        if (match(LPAREN)) {
            Expr expr = expression();
            consume(RPAREN, "Expect ')' after expression.");
            return expr;
        }
        throw error(t, "Expect expression.");
    }

    // --- token stream helpers -----------------------------------------------

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    private boolean check(TokenType type) {
        return !isAtEnd() && peek().type() == type;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean isAtEnd() {
        return peek().type() == EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private ParseError error(Token token, String message) {
        return new ParseError(token.line(), message);
    }
}
