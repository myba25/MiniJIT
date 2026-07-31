package com.minijit.jni;

/**
 * Wraps a failure that happened inside the C++ backend.
 *
 * The native code throws this rather than letting a C++ exception unwind into
 * the JVM: crossing that boundary is undefined behaviour and would take the
 * whole process down instead of producing a stack trace.
 */
public class NativeBackendException extends RuntimeException {

    public NativeBackendException(String message) {
        super(message);
    }
}
