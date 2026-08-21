/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

#include <errno.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>

static const char kSource[] = "/dev/disp_feature";
static const char kTarget[] = "/dev/mi_display/disp_feature";

int main(void) {
    if (link(kSource, kTarget) == 0) {
        return 0;
    }

    if (errno == EEXIST) {
        struct stat source_stat;
        struct stat target_stat;

        if (stat(kSource, &source_stat) == 0 && stat(kTarget, &target_stat) == 0 &&
            source_stat.st_dev == target_stat.st_dev &&
            source_stat.st_ino == target_stat.st_ino) {
            return 0;
        }
    }

    fprintf(stderr, "link %s to %s failed: %d\n", kSource, kTarget, errno);
    return 1;
}
