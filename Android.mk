LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

# Module name should match apk name to be installed.
LOCAL_MODULE := Termux
LOCAL_SRC_FILES := com.termux_60.apk
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_MODULE_TAGS := optional
LOCAL_CERTIFICATE := PRESIGNED


LOCAL_LIBS := $(shell zipinfo -1 $(LOCAL_PATH)/$(LOCAL_SRC_FILES) | grep ^lib/ | grep -v /$$)
LOCAL_MODULE_TARGET_ARCH := x86_64
LOCAL_PREBUILT_JNI_LIBS := $(addprefix @,$(filter lib/$(LOCAL_MODULE_TARGET_ARCH)/%,$(LOCAL_LIBS)))

include $(BUILD_PREBUILT)
