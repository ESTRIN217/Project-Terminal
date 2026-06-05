#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_estrin217_terminal_core_TerminalCore_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++ (com.estrin217.terminal)";
    return env->NewStringUTF(hello.c_str());
}
