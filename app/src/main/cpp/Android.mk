# libturn.so: Smart Turn on sherpa-onnx's onnxruntime (turn.c), which it finds at run time with dlopen.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := turn
LOCAL_SRC_FILES := turn.c
LOCAL_CFLAGS := -Wall -Werror -O2
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
