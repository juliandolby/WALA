#ifndef _CAST_LAUNCH_H
#define _CAST_LAUNCH_H

#include "dll_export.h"

typedef long long  __int64;

#include "jni.h"

extern DLLEXPORT JNIEnv *launch_jvm(char *);
extern void kill();

#endif
