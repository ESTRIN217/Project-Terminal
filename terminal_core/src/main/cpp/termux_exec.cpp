#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#define EI_CLASS 4
#define ELFCLASS32 1
#define ELFCLASS64 2

static int is_elf(const char* filename) {
    if (!filename) return 0;
    FILE* f = fopen(filename, "rb");
    if (!f) return 0;
    unsigned char magic[4];
    int n = fread(magic, 1, 4, f);
    fclose(f);
    return (n == 4 && magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F');
}

static int get_elf_class(const char* filename) {
    if (!filename) return 0;
    FILE* f = fopen(filename, "rb");
    if (!f) return 0;
    unsigned char ident[16];
    int n = fread(ident, 1, 16, f);
    fclose(f);
    if (n < 16) return 0;
    if (ident[0] != 0x7F || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') return 0;
    return ident[EI_CLASS];
}

static int is_under_proot_rootfs(const char* filename) {
    if (!filename) return 0;
    if (strncmp(filename, "/system/", 8) == 0) return 0;
    if (strncmp(filename, "/apex/", 6) == 0) return 0;
    if (strncmp(filename, "/dev/", 5) == 0) return 0;
    if (strncmp(filename, "/proc/", 6) == 0) return 0;
    if (strncmp(filename, "/sys/", 5) == 0) return 0;
    if (strncmp(filename, "/data_priv/", 11) == 0) return 0;
    return 1;
}

int execve(const char* filename, char* const argv[], char* const envp[]) {
    if (!filename) {
        errno = EFAULT;
        return -1;
    }

    if (!is_under_proot_rootfs(filename)) {
        return syscall(__NR_execve, filename, argv, envp);
    }

    if (!is_elf(filename)) {
        return syscall(__NR_execve, filename, argv, envp);
    }

    int elf_class = get_elf_class(filename);
    const char* linker;
    if (elf_class == ELFCLASS32) {
        linker = "/system/bin/linker";
    } else {
        linker = "/system/bin/linker64";
    }

    int arg_count = 0;
    if (argv) {
        while (argv[arg_count] != NULL) arg_count++;
    }

    char** new_argv = (char**)malloc((arg_count + 3) * sizeof(char*));
    if (!new_argv) {
        errno = ENOMEM;
        return -1;
    }

    new_argv[0] = (char*)linker;
    new_argv[1] = (char*)filename;
    for (int i = 1; i <= arg_count; i++) {
        new_argv[i + 1] = argv[i];
    }
    new_argv[arg_count + 2] = NULL;

    int result = syscall(__NR_execve, linker, new_argv, envp);
    free(new_argv);
    return result;
}

int execvp(const char* file, char* const argv[]) {
    extern char** environ;
    return execve(file, argv, environ);
}

int execv(const char* path, char* const argv[]) {
    extern char** environ;
    return execve(path, argv, environ);
}

int execl(const char* path, const char* arg, ...) {
    va_list args;
    va_start(args, arg);
    int count = 1;
    while (va_arg(args, const char*) != NULL) count++;
    va_end(args);

    char** argv = (char**)malloc((count + 1) * sizeof(char*));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }

    argv[0] = (char*)arg;
    va_start(args, arg);
    for (int i = 1; i < count; i++) {
        argv[i] = va_arg(args, char*);
    }
    va_end(args);
    argv[count] = NULL;

    extern char** environ;
    int result = execve(path, argv, environ);
    free(argv);
    return result;
}

int execlp(const char* file, const char* arg, ...) {
    va_list args;
    va_start(args, arg);
    int count = 1;
    while (va_arg(args, const char*) != NULL) count++;
    va_end(args);

    char** argv = (char**)malloc((count + 1) * sizeof(char*));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }

    argv[0] = (char*)arg;
    va_start(args, arg);
    for (int i = 1; i < count; i++) {
        argv[i] = va_arg(args, char*);
    }
    va_end(args);
    argv[count] = NULL;

    extern char** environ;
    int result = execve(file, argv, environ);
    free(argv);
    return result;
}

int execle(const char* path, const char* arg, ...) {
    va_list args;
    va_start(args, arg);
    int count = 1;
    while (va_arg(args, const char*) != NULL) {
        count++;
    }
    va_end(args);

    char** argv = (char**)malloc((count + 1) * sizeof(char*));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }

    argv[0] = (char*)arg;
    va_start(args, arg);
    for (int i = 1; i < count; i++) {
        argv[i] = va_arg(args, char*);
    }
    char** envp = va_arg(args, char**);
    va_end(args);
    argv[count] = NULL;

    int result = execve(path, argv, envp);
    free(argv);
    return result;
}

int fexecve(int fd, char* const argv[], char* const envp[]) {
    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    return execve(fd_path, argv, envp);
}
