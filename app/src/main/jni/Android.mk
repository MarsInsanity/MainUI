LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := mainuiinput
LOCAL_SRC_FILES := mainui_input.c
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
