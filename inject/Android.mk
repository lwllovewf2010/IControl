LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	inject.c \


LOCAL_SHARED_LIBRARIES := \
	liblog \
	libdl \

LOCAL_MODULE:= inject2

include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	test.c \


LOCAL_SHARED_LIBRARIES := \
	liblog \
	libdl \

LOCAL_MODULE:= libtest

include $(BUILD_SHARED_LIBRARY)


# Build a variant of app_process binary linked with ASan runtime.
# ARM-only at the moment.
