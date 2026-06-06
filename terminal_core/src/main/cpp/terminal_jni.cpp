#include <jni.h>
#include <string>
#include <sys/ioctl.h>
#include <unistd.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_estrin217_terminal_core_TerminalCore_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++ (com.estrin217.terminal)";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_estrin217_terminal_core_TerminalCore_setTerminalSize(
        JNIEnv* env,
        jobject /* this */,
        jint fd,
        jint rows,
        jint cols,
        jint width_px,
        jint height_px) {
    struct winsize sz;
    sz.ws_row = (unsigned short) rows;
    sz.ws_col = (unsigned short) cols;
    sz.ws_xpixel = (unsigned short) width_px;
    sz.ws_ypixel = (unsigned short) height_px;
    ioctl(fd, TIOCSWINSZ, &sz);
}

