package com.minijit;

import com.minijit.ast.AstJson;
import com.minijit.ast.Program;
import com.minijit.jni.NativeBackend;
import com.minijit.jni.NativeBackendException;
import com.minijit.lexer.LexError;
import com.minijit.lexer.Lexer;
import com.minijit.lexer.Token;
import com.minijit.parser.ParseError;
import com.minijit.parser.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line driver for MiniJIT.
 *
 * <pre>
 *   java -jar minijit.jar [mode] [options] &lt;file.mini&gt;
 *
 *   Modes:
 *     --tokens    print the token stream
 *     --ast       print the AST as JSON (default)
 *     --emit-ir   print the LLVM IR, via the native backend
 *     --run       compile and execute, via the native backend
 *
 *   Options:
 *     -o, --out F   write output to F as UTF-8 instead of stdout
 *     --entry NAME  entry function for --run (default: main)
 *     --no-opt      skip the -O2 pipeline
 * </pre>
 *
 * The last two modes need the native library; see {@link NativeBackend} for how
 * the JVM finds it.
 *
 * Exit codes: 0 success, 1 compile or execution error, 2 usage or I/O error.
 */
public final class Main {

    public static void main(String[] args) {
        String mode = "--ast";
        String path = null;
        String outPath = null;
        String entry = "main";
        boolean optimize = true;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--tokens", "--ast", "--emit-ir", "--run" -> mode = arg;
                case "--no-opt" -> optimize = false;
                case "-o", "--out" -> {
                    if (i + 1 >= args.length) {
                        System.err.println(arg + " needs a file name");
                        System.exit(2);
                    }
                    outPath = args[++i];
                }
                case "--entry" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--entry needs a function name");
                        System.exit(2);
                    }
                    entry = args[++i];
                }
                default -> {
                    if (arg.startsWith("-")) {
                        System.err.println("Unknown option: " + arg);
                        usage();
                        System.exit(2);
                    }
                    path = arg;
                }
            }
        }

        if (path == null) {
            usage();
            System.exit(2);
        }

        String source;
        try {
            source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Cannot read file '" + path + "': " + e.getMessage());
            System.exit(2);
            return; // unreachable, keeps the compiler happy about 'source'
        }

        try {
            List<Token> tokens = new Lexer(source).scanTokens();

            if (mode.equals("--tokens")) {
                StringBuilder text = new StringBuilder();
                for (Token token : tokens) {
                    text.append("%4d  %s%n".formatted(token.line(), token));
                }
                emit(text.toString(), outPath, false);
                return;
            }

            Program program = new Parser(tokens).parseProgram();
            String json = AstJson.toJson(program);

            switch (mode) {
                case "--ast" -> emit(json, outPath, true);
                case "--emit-ir" -> emit(NativeBackend.emitIr(json, optimize), outPath, false);
                case "--run" -> emit(String.valueOf(NativeBackend.run(json, entry, optimize)),
                                     outPath, true);
                default -> throw new IllegalStateException("unhandled mode " + mode);
            }

        } catch (LexError | ParseError | NativeBackendException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Writes to stdout or, with -o, straight to a file.
     *
     * The file is always UTF-8. That matters because Windows PowerShell 5.1
     * encodes shell-redirected output as UTF-16LE with a byte order mark, which
     * the JSON parser on the C++ side rejects outright.
     */
    private static void emit(String text, String outPath, boolean appendNewline) {
        if (outPath == null) {
            if (appendNewline) {
                System.out.println(text);
            } else {
                System.out.print(text);
            }
            return;
        }
        try {
            Files.writeString(Path.of(outPath), appendNewline ? text + System.lineSeparator() : text,
                              StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Cannot write file '" + outPath + "': " + e.getMessage());
            System.exit(2);
        }
    }

    private static void usage() {
        System.err.println("Usage: minijit [--tokens|--ast|--emit-ir|--run] [options] <file.mini>");
        System.err.println("  --tokens      print the token stream");
        System.err.println("  --ast         print the AST as JSON (default)");
        System.err.println("  --emit-ir     print the LLVM IR (needs the native library)");
        System.err.println("  --run         compile and execute (needs the native library)");
        System.err.println("  -o, --out F   write output to F as UTF-8 instead of stdout");
        System.err.println("  --entry NAME  entry function for --run (default: main)");
        System.err.println("  --no-opt      skip the -O2 pipeline");
    }
}
