package com.minijit.ast;

import java.util.List;

/**
 * Serializes a {@link Program} AST to JSON.
 *
 * This JSON is the contract between the Java frontend and the C++ backend:
 * in Phase 5 the JNI bridge hands this string to C++, which parses it with
 * nlohmann/json and walks it to emit LLVM IR.
 *
 * Every node carries a {@code "kind"} discriminator matching its record name,
 * plus a {@code "line"} field so the backend can report errors against source.
 *
 * Example output for {@code fn f(a) { return a + 1; }}:
 * <pre>{@code
 * {"functions":[{"kind":"Function","name":"f","params":["a"],"line":1,
 *   "body":{"kind":"Block","line":1,"statements":[
 *     {"kind":"Return","line":1,"value":{"kind":"Binary","op":"+","line":1,
 *       "left":{"kind":"Variable","name":"a","line":1},
 *       "right":{"kind":"IntLiteral","value":1,"line":1}}}]}}]}
 * }</pre>
 *
 * Written by hand rather than via Jackson/Gson to keep the frontend
 * dependency-free; the emitted subset of JSON is small and fully escaped.
 */
public final class AstJson {

    private AstJson() {}

    public static String toJson(Program program) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"functions\":[");
        List<Stmt.Function> fns = program.functions();
        for (int i = 0; i < fns.size(); i++) {
            if (i > 0) sb.append(',');
            writeStmt(sb, fns.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    // --- statements ---------------------------------------------------------

    private static void writeStmt(StringBuilder sb, Stmt stmt) {
        if (stmt instanceof Stmt.Function f) {
            sb.append("{\"kind\":\"Function\",\"name\":");
            writeString(sb, f.name());
            sb.append(",\"params\":[");
            for (int i = 0; i < f.params().size(); i++) {
                if (i > 0) sb.append(',');
                writeString(sb, f.params().get(i));
            }
            sb.append("],\"line\":").append(f.line()).append(",\"body\":");
            writeStmt(sb, f.body());
            sb.append('}');

        } else if (stmt instanceof Stmt.Block b) {
            sb.append("{\"kind\":\"Block\",\"line\":").append(b.line()).append(",\"statements\":[");
            for (int i = 0; i < b.statements().size(); i++) {
                if (i > 0) sb.append(',');
                writeStmt(sb, b.statements().get(i));
            }
            sb.append("]}");

        } else if (stmt instanceof Stmt.Let l) {
            sb.append("{\"kind\":\"Let\",\"name\":");
            writeString(sb, l.name());
            sb.append(",\"line\":").append(l.line()).append(",\"initializer\":");
            writeExpr(sb, l.initializer());
            sb.append('}');

        } else if (stmt instanceof Stmt.If i) {
            sb.append("{\"kind\":\"If\",\"line\":").append(i.line()).append(",\"condition\":");
            writeExpr(sb, i.condition());
            sb.append(",\"thenBranch\":");
            writeStmt(sb, i.thenBranch());
            sb.append(",\"elseBranch\":");
            if (i.elseBranch() == null) {
                sb.append("null");
            } else {
                writeStmt(sb, i.elseBranch());
            }
            sb.append('}');

        } else if (stmt instanceof Stmt.While w) {
            sb.append("{\"kind\":\"While\",\"line\":").append(w.line()).append(",\"condition\":");
            writeExpr(sb, w.condition());
            sb.append(",\"body\":");
            writeStmt(sb, w.body());
            sb.append('}');

        } else if (stmt instanceof Stmt.Return r) {
            sb.append("{\"kind\":\"Return\",\"line\":").append(r.line()).append(",\"value\":");
            if (r.value() == null) {
                sb.append("null");
            } else {
                writeExpr(sb, r.value());
            }
            sb.append('}');

        } else if (stmt instanceof Stmt.ExprStmt e) {
            sb.append("{\"kind\":\"ExprStmt\",\"line\":").append(e.line()).append(",\"expression\":");
            writeExpr(sb, e.expression());
            sb.append('}');

        } else {
            throw new IllegalStateException("Unhandled statement type: " + stmt.getClass());
        }
    }

    // --- expressions ---------------------------------------------------------

    private static void writeExpr(StringBuilder sb, Expr expr) {
        if (expr instanceof Expr.IntLiteral n) {
            sb.append("{\"kind\":\"IntLiteral\",\"value\":").append(n.value())
              .append(",\"line\":").append(n.line()).append('}');

        } else if (expr instanceof Expr.Variable v) {
            sb.append("{\"kind\":\"Variable\",\"name\":");
            writeString(sb, v.name());
            sb.append(",\"line\":").append(v.line()).append('}');

        } else if (expr instanceof Expr.Assign a) {
            sb.append("{\"kind\":\"Assign\",\"name\":");
            writeString(sb, a.name());
            sb.append(",\"line\":").append(a.line()).append(",\"value\":");
            writeExpr(sb, a.value());
            sb.append('}');

        } else if (expr instanceof Expr.Binary b) {
            sb.append("{\"kind\":\"Binary\",\"op\":");
            writeString(sb, b.operator());
            sb.append(",\"line\":").append(b.line()).append(",\"left\":");
            writeExpr(sb, b.left());
            sb.append(",\"right\":");
            writeExpr(sb, b.right());
            sb.append('}');

        } else if (expr instanceof Expr.Unary u) {
            sb.append("{\"kind\":\"Unary\",\"op\":");
            writeString(sb, u.operator());
            sb.append(",\"line\":").append(u.line()).append(",\"operand\":");
            writeExpr(sb, u.operand());
            sb.append('}');

        } else if (expr instanceof Expr.Call c) {
            sb.append("{\"kind\":\"Call\",\"callee\":");
            writeString(sb, c.callee());
            sb.append(",\"line\":").append(c.line()).append(",\"args\":[");
            for (int i = 0; i < c.arguments().size(); i++) {
                if (i > 0) sb.append(',');
                writeExpr(sb, c.arguments().get(i));
            }
            sb.append("]}");

        } else {
            throw new IllegalStateException("Unhandled expression type: " + expr.getClass());
        }
    }

    // --- JSON string escaping -------------------------------------------------

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
