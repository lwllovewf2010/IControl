LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	xposed.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
	libandroid_runtime \
	libdvm \
	libstlport \
	libdl

LOCAL_C_INCLUDES += dalvik \
                    dalvik/vm \
                    external/stlport/stlport \
                    bionic \
                    bionic/libstdc++/include

LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS += -DPLATFORM_SDK_VERSION=$(PLATFORM_SDK_VERSION)

ifeq ($(strip $(WITH_JIT)),true)
LOCAL_CFLAGS += -DWITH_JIT
endif

ifeq ($(strip $(XPOSED_SHOW_OFFSETS)),true)
LOCAL_CFLAGS += -DXPOSED_SHOW_OFFSETS
endif

LOCAL_CFLAGS += -DPLATFORM_SDK_VERSION=$(PLATFORM_SDK_VERSION)
LOCAL_MODULE := libxposed

include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	test.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
	libandroid_runtime \
	libdvm \
	libstlport \
	libdl

LOCAL_C_INCLUDES += dalvik \
                    dalvik/vm \
                    external/stlport/stlport \
                    bionic \
                    bionic/libstdc++/include

LOCAL_MODULE_TAGS := optional

LOCAL_MODULE := libtest

include $(BUILD_SHARED_LIBRARY)
