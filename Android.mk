LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4

LOCAL_SRC_FILES := $(call all-java-files-under, src) \
        $(call all-java-files-under, lib/terminal-emulator/src) \
        $(call all-java-files-under, lib/terminal-view/src) \
        $(call all-java-files-under, lib/termux-styling/src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/lib/termux-styling/res \
        $(LOCAL_PATH)/lib/terminal-view/res $(LOCAL_PATH)/res

LOCAL_AAPT_FLAGS := --auto-add-overlay

LOCAL_OVERRIDES_PACKAGES := TermuxStyling OtoTerminal

LOCAL_PACKAGE_NAME := Termux
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true
LOCAL_JNI_SHARED_LIBRARIES := libtermux

include $(BUILD_PACKAGE)
include $(CLEAR_VARS)
LOCAL_CFLAGS := -std=c11 -Wall  -Wextra  -Werror  -Os  -fno-stack-protector  -Wl,--gc-sections
LOCAL_MODULE := libtermux
LOCAL_SRC_FILES := lib/terminal-emulator/jni/termux.c
LOCAL_MODULE_TAGS := optional
LOCAL_ARM_MODE := x86_64
include $(BUILD_SHARED_LIBRARY)
