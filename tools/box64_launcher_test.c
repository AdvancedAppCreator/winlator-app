#include <errno.h>
#include <stdio.h>
#include <sys/types.h>

static pid_t test_pid;
static pid_t test_pgid;
static pid_t test_sid;
static int test_setsid_result;
static int test_setsid_errno;

static pid_t fake_getpid(void) {
    return test_pid;
}

static pid_t fake_getpgrp(void) {
    return test_pgid;
}

static pid_t fake_getsid(pid_t pid) {
    (void)pid;
    return test_sid;
}

static pid_t fake_setsid(void) {
    if (test_setsid_result < 0) {
        errno = test_setsid_errno;
        return -1;
    }
    test_pgid = test_pid;
    test_sid = test_pid;
    return test_pid;
}

#define WINLATOR_GETPID fake_getpid
#define WINLATOR_GETPGRP fake_getpgrp
#define WINLATOR_GETSID fake_getsid
#define WINLATOR_SETSID fake_setsid
#define BOX64_LAUNCHER_NO_MAIN
#include "box64_launcher.c"

static int run_case(
    const char* name,
    pid_t pid,
    pid_t pgid,
    pid_t sid,
    int setsid_result,
    int setsid_errno,
    int expected
) {
    test_pid = pid;
    test_pgid = pgid;
    test_sid = sid;
    test_setsid_result = setsid_result;
    test_setsid_errno = setsid_errno;
    int actual = prepare_process_group();
    if (actual != expected) {
        fprintf(stderr, "%s: expected %d, got %d\n", name, expected, actual);
        return 1;
    }
    return 0;
}

int main(void) {
    int failures = 0;
    failures += run_case("setsid-success", 4200, 4000, 4000, 0, 0, 0);
    failures += run_case(
        "already-process-group-leader",
        4200,
        4200,
        4000,
        -1,
        EPERM,
        0
    );
    failures += run_case(
        "eperm-without-group-ownership",
        4200,
        4100,
        4000,
        -1,
        EPERM,
        -1
    );
    if (failures != 0) return 1;
    puts("box64-launcher process-group tests passed");
    return 0;
}
