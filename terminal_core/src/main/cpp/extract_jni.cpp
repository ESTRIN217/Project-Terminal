#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <climits>
#include <cstdlib>

#define LOG_TAG "ExtractJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// libarchive constants (loaded via dlopen, so no archive.h available at compile time)
#define ARCHIVE_OK 0
#define ARCHIVE_EOF 1

#define ARCHIVE_EXTRACT_PERM            0x0002
#define ARCHIVE_EXTRACT_TIME            0x0004
#define ARCHIVE_EXTRACT_SECURE_SYMLINKS 0x0100
#define ARCHIVE_EXTRACT_SECURE_NODOTDOT 0x0200
#define ARCHIVE_EXTRACT_UNLINK          0x0010

typedef struct archive archive;
typedef struct archive_entry archive_entry;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_estrin217_terminal_core_RootfsDecompressor_nativeExtractTar(
    JNIEnv* env,
    jobject thiz,
    jint fd,
    jstring dest_dir_str)
{
    const char* dest_dir = env->GetStringUTFChars(dest_dir_str, nullptr);
    if (dest_dir == nullptr) {
        return JNI_FALSE;
    }

    // dlopen libarchive.so (deployed alongside in jniLibs)
    void* handle = dlopen("libarchive.so", RTLD_LAZY | RTLD_LOCAL);
    if (handle == nullptr) {
        // Fallback: try libbsdtar.so (which statically includes archive)
        handle = dlopen("libbsdtar.so", RTLD_LAZY | RTLD_LOCAL);
    }
    if (handle == nullptr) {
        LOGE("dlopen failed for libarchive.so and libbsdtar.so: %s", dlerror());
        env->ReleaseStringUTFChars(dest_dir_str, dest_dir);
        return JNI_FALSE;
    }

    // Resolve all needed libarchive symbols
    auto archive_read_new = (archive* (*)()) dlsym(handle, "archive_read_new");
    auto archive_read_support_filter_all = (int (*)(archive*)) dlsym(handle, "archive_read_support_filter_all");
    auto archive_read_support_format_all = (int (*)(archive*)) dlsym(handle, "archive_read_support_format_all");
    auto archive_read_open_fd = (int (*)(archive*, int, int)) dlsym(handle, "archive_read_open_fd");
    auto archive_read_next_header = (int (*)(archive*, archive_entry**)) dlsym(handle, "archive_read_next_header");
    auto archive_entry_pathname = (const char* (*)(archive_entry*)) dlsym(handle, "archive_entry_pathname");
    auto archive_entry_set_pathname = (void (*)(archive_entry*, const char*)) dlsym(handle, "archive_entry_set_pathname");
    auto archive_entry_size = (int64_t (*)(archive_entry*)) dlsym(handle, "archive_entry_size");
    auto archive_read_data = (ssize_t (*)(archive*, void*, size_t)) dlsym(handle, "archive_read_data");
    auto archive_read_free = (int (*)(archive*)) dlsym(handle, "archive_read_free");
    auto archive_read_close = (int (*)(archive*)) dlsym(handle, "archive_read_close");
    auto archive_error_string = (const char* (*)(archive*)) dlsym(handle, "archive_error_string");

    auto archive_write_disk_new = (archive* (*)()) dlsym(handle, "archive_write_disk_new");
    auto archive_write_disk_set_options = (int (*)(archive*, int)) dlsym(handle, "archive_write_disk_set_options");
    auto archive_write_header = (int (*)(archive*, archive_entry*)) dlsym(handle, "archive_write_header");
    auto archive_write_data = (ssize_t (*)(archive*, const void*, size_t)) dlsym(handle, "archive_write_data");
    auto archive_write_finish_entry = (int (*)(archive*)) dlsym(handle, "archive_write_finish_entry");
    auto archive_write_close = (int (*)(archive*)) dlsym(handle, "archive_write_close");
    auto archive_write_free = (int (*)(archive*)) dlsym(handle, "archive_write_free");

    // Verify all symbols were resolved
    if (!archive_read_new || !archive_read_support_filter_all ||
        !archive_read_support_format_all || !archive_read_open_fd ||
        !archive_read_next_header || !archive_entry_pathname ||
        !archive_entry_set_pathname || !archive_entry_size ||
        !archive_read_data || !archive_read_free || !archive_read_close ||
        !archive_error_string || !archive_write_disk_new ||
        !archive_write_disk_set_options || !archive_write_header ||
        !archive_write_data || !archive_write_finish_entry ||
        !archive_write_close || !archive_write_free) {
        LOGE("dlsym failed to resolve one or more libarchive symbols");
        dlclose(handle);
        env->ReleaseStringUTFChars(dest_dir_str, dest_dir);
        return JNI_FALSE;
    }

    archive* a = archive_read_new();
    archive* ext = archive_write_disk_new();

    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);

    archive_write_disk_set_options(ext,
        ARCHIVE_EXTRACT_PERM |
        ARCHIVE_EXTRACT_TIME |
        ARCHIVE_EXTRACT_SECURE_SYMLINKS |
        ARCHIVE_EXTRACT_SECURE_NODOTDOT |
        ARCHIVE_EXTRACT_UNLINK);

    int r = archive_read_open_fd(a, fd, 10240);
    if (r != ARCHIVE_OK) {
        LOGE("archive_read_open_fd failed: %s", archive_error_string(a));
        archive_read_close(a);
        archive_read_free(a);
        archive_write_close(ext);
        archive_write_free(ext);
        dlclose(handle);
        env->ReleaseStringUTFChars(dest_dir_str, dest_dir);
        return JNI_FALSE;
    }

    archive_entry* entry;
    bool success = true;

    while (archive_read_next_header(a, &entry) == ARCHIVE_OK) {
        const char* name = archive_entry_pathname(entry);
        if (name == nullptr || name[0] == '\0') continue;

        if (strstr(name, "..") != nullptr || name[0] == '/') {
            LOGE("Path traversal blocked: %s", name);
            success = false;
            break;
        }

        char full_path[PATH_MAX];
        int written = snprintf(full_path, sizeof(full_path), "%s/%s", dest_dir, name);
        if (written < 0 || (size_t)written >= sizeof(full_path)) {
            LOGE("Path too long: %s/%s", dest_dir, name);
            success = false;
            break;
        }

        archive_entry_set_pathname(entry, full_path);

        r = archive_write_header(ext, entry);
        if (r == ARCHIVE_OK) {
            if (archive_entry_size(entry) > 0) {
                char buff[8192];
                ssize_t len;
                while ((len = archive_read_data(a, buff, sizeof(buff))) > 0) {
                    archive_write_data(ext, buff, len);
                }
            }
            archive_write_finish_entry(ext);
        } else {
            LOGE("archive_write_header failed for %s: %s",
                 full_path, archive_error_string(ext));
        }
    }

    if (!success) {
        LOGE("Extraction aborted due to security violation");
    } else {
        LOGD("Extraction completed successfully");
    }

    archive_read_close(a);
    archive_read_free(a);
    archive_write_close(ext);
    archive_write_free(ext);

    dlclose(handle);
    env->ReleaseStringUTFChars(dest_dir_str, dest_dir);
    return success ? JNI_TRUE : JNI_FALSE;
}
