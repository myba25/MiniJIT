package com.minijit.jni;

/**
 * Java side of the JNI bridge to the C++ backend.
 *
 * The AST crosses as a JSON string — the same format the standalone
 * {@code minijit-backend} executable reads from a file. Passing one string is
 * far simpler and safer than reaching into Java objects from C++, which would
 * mean juggling field IDs and local references for every node.
 *
 * <h2>Loading the native library</h2>
 * {@code System.loadLibrary} searches {@code java.library.path}, so the JVM has
 * to be told where the DLL is:
 * <pre>
 * java -Djava.library.path=cpp\build\Release -jar ... --run program.mini
 * </pre>
 * Alternatively set {@code -Dminijit.native.file=<absolute path to the dll>} to
 * load one specific file.
 */
public final class NativeBackend {

    private static final String LIBRARY_NAME = "minijit_jni";

    /** Null once the library is loaded; otherwise the reason it could not be. */
    private static final Throwable LOAD_FAILURE;

    static {
        Throwable failure = null;
        try {
            String explicitPath = System.getProperty("minijit.native.file");
            if (explicitPath != null) {
                System.load(explicitPath);
            } else {
                System.loadLibrary(LIBRARY_NAME);
            }
        } catch (Throwable t) {
            // Deliberately caught, not propagated: a class that cannot be
            // initialised produces a confusing NoClassDefFoundError at every
            // later use. Remembering the cause lets us report it properly.
            failure = t;
        }
        LOAD_FAILURE = failure;
    }

    private NativeBackend() {}

    /** True when the backend library is present and usable. */
    public static boolean isAvailable() {
        return LOAD_FAILURE == null;
    }

    /**
     * Compiles the AST and runs {@code entry}, which must take no parameters.
     *
     * @return the value that function returned
     */
    public static long run(String astJson, String entry, boolean optimize) {
        requireLoaded();
        return runNative(astJson, entry, optimize);
    }

    /** Compiles the AST and returns the textual LLVM IR. */
    public static String emitIr(String astJson, boolean optimize) {
        requireLoaded();
        return emitIrNative(astJson, optimize);
    }

    private static void requireLoaded() {
        if (LOAD_FAILURE == null) {
            return;
        }
        throw new NativeBackendException(
                "Cannot load the native backend '" + LIBRARY_NAME + "'.\n"
                        + "Build it with:  cmake --build cpp\\build --config Release\n"
                        + "Then point the JVM at it, e.g.:\n"
                        + "  java -Djava.library.path=cpp\\build\\Release -jar <jar> --run <file>\n"
                        + "Cause: " + LOAD_FAILURE);
    }

    // Implemented in cpp/src/jni_bridge.cpp. The C++ symbol names are derived
    // from the package, class and method names, so renaming anything here means
    // renaming it there too.
    private static native long runNative(String astJson, String entry, boolean optimize);

    private static native String emitIrNative(String astJson, boolean optimize);
}
