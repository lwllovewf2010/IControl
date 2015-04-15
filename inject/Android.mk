LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	inject.c \


LOCAL_SHARED_LIBRARIES := \
	liblog \
	libdl \

LOCAL_MODULE:= inject2

include $(BUILD_EXECUTABLE)
