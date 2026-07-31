// C++ side of the JNI bridge (com.minijit.jni.NativeBackend).
//
// Two rules govern everything in this file:
//
//  1. A C++ exception must never unwind past a JNI entry point. The JVM does
//     not know how to handle it and the process dies without a stack trace.
//     Every exported function therefore wraps its body in try/catch and turns
//     failures into Java exceptions instead.
//
//  2. Anything obtained from JNIEnv with a Get* call has to be released with the
//     matching Release*. The RAII wrapper below guarantees that even when an
//     exception is thrown in between.
//
// The exported names are not chosen freely: JNI derives them from the Java
// package, class and method name. Java_com_minijit_jni_NativeBackend_runNative
// corresponds to NativeBackend.runNative in package com.minijit.jni.

#include "minijit/pipeline.hpp"

#include <jni.h>

#include <exception>
#include <string>

namespace {

/// Borrows the characters of a jstring and releases them on destruction.
///
/// GetStringUTFChars may return null if the JVM cannot allocate, so callers
/// check valid() before using the value.
class JniUtfString {
public:
    JniUtfString(JNIEnv* env, jstring value) : env_(env), value_(value) {
        if (value_ != nullptr) {
            chars_ = env_->GetStringUTFChars(value_, nullptr);
        }
    }

    ~JniUtfString() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    // Copying would release the same buffer twice.
    JniUtfString(const JniUtfString&) = delete;
    JniUtfString& operator=(const JniUtfString&) = delete;

    bool valid() const { return chars_ != nullptr; }
    std::string str() const { return std::string(chars_); }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

/// Schedules a Java exception to be thrown once control returns to the JVM.
/// Note that ThrowNew does not interrupt the C++ function: it only records the
/// pending exception, so the caller must return immediately afterwards.
void throwJava(JNIEnv* env, const char* className, const std::string& message) {
    jclass clazz = env->FindClass(className);
    if (clazz == nullptr) {
        // The class itself is missing — FindClass has already scheduled a
        // NoClassDefFoundError, so there is nothing better to do here.
        return;
    }
    env->ThrowNew(clazz, message.c_str());
    env->DeleteLocalRef(clazz);
}

constexpr const char* kBackendException = "com/minijit/jni/NativeBackendException";

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_minijit_jni_NativeBackend_runNative(
    JNIEnv* env, jclass, jstring astJson, jstring entry, jboolean optimize) {

    JniUtfString json(env, astJson);
    JniUtfString entryName(env, entry);
    if (!json.valid() || !entryName.valid()) {
        throwJava(env, kBackendException, "could not read arguments from the JVM");
        return 0;
    }

    try {
        return static_cast<jlong>(
            minijit::compileAndRun(json.str(), entryName.str(), optimize == JNI_TRUE));
    } catch (const std::exception& e) {
        throwJava(env, kBackendException, e.what());
        return 0;
    } catch (...) {
        // A catch-all is mandatory here, not defensive padding: letting an
        // unknown exception escape into the JVM is undefined behaviour.
        throwJava(env, kBackendException, "unknown error in the native backend");
        return 0;
    }
}

JNIEXPORT jstring JNICALL Java_com_minijit_jni_NativeBackend_emitIrNative(
    JNIEnv* env, jclass, jstring astJson, jboolean optimize) {

    JniUtfString json(env, astJson);
    if (!json.valid()) {
        throwJava(env, kBackendException, "could not read arguments from the JVM");
        return nullptr;
    }

    try {
        const std::string ir = minijit::compileToIr(json.str(), optimize == JNI_TRUE);
        return env->NewStringUTF(ir.c_str());
    } catch (const std::exception& e) {
        throwJava(env, kBackendException, e.what());
        return nullptr;
    } catch (...) {
        throwJava(env, kBackendException, "unknown error in the native backend");
        return nullptr;
    }
}

} // extern "C"
