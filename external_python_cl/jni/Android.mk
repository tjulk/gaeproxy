LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

# This is the target being built.
LOCAL_MODULE := python

# All of the source files that we will compile.
LOCAL_SRC_FILES := python.c

LOCAL_SHARED_LIBRARIES := libdl

include $(BUILD_EXECUTABLE)
