LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under,src)
LOCAL_SRC_FILES += $(call all-java-files-under,lib)

LOCAL_MODULE := xposed_bridge
LOCAL_MODULE_PATH := $(TARGET_OUT)/framework/

include $(BUILD_JAVA_LIBRARY)

# Use the following include to make our test apk.
include $(call all-makefiles-under,$(LOCAL_PATH))

