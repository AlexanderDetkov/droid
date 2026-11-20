/* Minimal stand-in for Samba's lib/replace: enough to build talloc.c on its own with the NDK (see build.sh). */
#ifndef DROID_REPLACE_H
#define DROID_REPLACE_H
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#include <sys/types.h>
#define HAVE_VA_COPY 1
#define HAVE_INTPTR_T 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 3
#ifndef _PUBLIC_
#define _PUBLIC_ __attribute__((visibility("default")))
#endif
#define discard_const(ptr) ((void *)((uintptr_t)(ptr)))
#define discard_const_p(type, ptr) ((type *)discard_const(ptr))
#ifndef MIN
#define MIN(a,b) ((a)<(b)?(a):(b))
#endif
#ifndef MAX
#define MAX(a,b) ((a)>(b)?(a):(b))
#endif
#ifndef likely
#define likely(x) __builtin_expect(!!(x), 1)
#define unlikely(x) __builtin_expect(!!(x), 0)
#endif
#endif
