#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern char** environ;

#ifndef WINLATOR_GETPID
#define WINLATOR_GETPID getpid
#endif
#ifndef WINLATOR_GETPGRP
#define WINLATOR_GETPGRP getpgrp
#endif
#ifndef WINLATOR_GETSID
#define WINLATOR_GETSID getsid
#endif
#ifndef WINLATOR_SETSID
#define WINLATOR_SETSID setsid
#endif

static int prepare_process_group(void) {
    pid_t pid = WINLATOR_GETPID();
    if (WINLATOR_SETSID() >= 0) {
        fprintf(
            stderr,
            "box64-launcher: created session pid=%ld pgid=%ld sid=%ld\n",
            (long)pid,
            (long)WINLATOR_GETPGRP(),
            (long)WINLATOR_GETSID(0)
        );
        return 0;
    }

    int error = errno;
    pid_t pgid = WINLATOR_GETPGRP();
    pid_t sid = WINLATOR_GETSID(0);
    if (error == EPERM && pgid == pid) {
        fprintf(
            stderr,
            "box64-launcher: setsid not permitted because launcher already owns "
            "its process group; continuing pid=%ld pgid=%ld sid=%ld\n",
            (long)pid,
            (long)pgid,
            (long)sid
        );
        return 0;
    }

    fprintf(
        stderr,
        "box64-launcher: setsid failed: %s pid=%ld pgid=%ld sid=%ld\n",
        strerror(error),
        (long)pid,
        (long)pgid,
        (long)sid
    );
    return -1;
}

#ifndef BOX64_LAUNCHER_NO_MAIN
static int join_path(char* output, size_t size, const char* directory, const char* name) {
    int length = snprintf(output, size, "%s/%s", directory, name);
    return length > 0 && (size_t)length < size;
}

int main(int argc, char** argv) {
    if (prepare_process_group() < 0) return 125;

    const char* rootfs = getenv("WINLATOR_ROOTFS");
    if (!rootfs || !rootfs[0]) {
        fprintf(stderr, "box64-launcher: WINLATOR_ROOTFS is not set\n");
        return 125;
    }

    char launcher[PATH_MAX] = {0};
    if (!realpath(argv[0], launcher)) {
        fprintf(stderr, "box64-launcher: realpath(%s) failed: %s\n", argv[0], strerror(errno));
        return 125;
    }

    char native_library_dir[PATH_MAX] = {0};
    strncpy(native_library_dir, launcher, sizeof(native_library_dir) - 1);
    char* separator = strrchr(native_library_dir, '/');
    if (!separator) {
        fprintf(stderr, "box64-launcher: invalid launcher path %s\n", launcher);
        return 125;
    }
    *separator = '\0';

    char loader[PATH_MAX] = {0};
    char box64[PATH_MAX] = {0};
    char rootfs_lib[PATH_MAX] = {0};
    if (!join_path(loader, sizeof(loader), native_library_dir, "libglibcloader.so")
        || !join_path(box64, sizeof(box64), native_library_dir, "libbox64.so")
        || !join_path(rootfs_lib, sizeof(rootfs_lib), rootfs, "lib")) {
        fprintf(stderr, "box64-launcher: runtime path is too long\n");
        return 125;
    }

    char** child_argv = calloc((size_t)argc + 7, sizeof(char*));
    if (!child_argv) {
        fprintf(stderr, "box64-launcher: unable to allocate arguments\n");
        return 125;
    }

    int index = 0;
    child_argv[index++] = loader;
    child_argv[index++] = "--library-path";
    child_argv[index++] = rootfs_lib;
    child_argv[index++] = "--argv0";
    child_argv[index++] = launcher;
    child_argv[index++] = box64;
    for (int i = 1; i < argc; i++) child_argv[index++] = argv[i];
    child_argv[index] = NULL;

    execve(loader, child_argv, environ);
    fprintf(stderr, "box64-launcher: execve(%s) failed: %s\n", loader, strerror(errno));
    free(child_argv);
    return 126;
}
#endif
