/*
 * A virtual keyboard through /dev/uinput (root), for apps that ignore keys from Android's
 * virtual keyboard device (what `input keyevent` uses). RetroArch is one: it only takes
 * keyboard keys from a device it registered as a keyboard.
 *
 * Called through JNI from io.github.marsinsanity.mainui.tools.KeyInject, which MainUI runs as
 * root with app_process. The device only has F keys, so Android sees an internal,
 * non-alphabetic keyboard: the on-screen keyboard keeps working as before.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/uinput.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static void emit(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (unsigned short) type;
    ev.code = (unsigned short) code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) { /* reported by the caller's next call */ }
}

JNIEXPORT jint JNICALL
Java_io_github_marsinsanity_mainui_tools_KeyInject_nativeOpen(JNIEnv *env, jclass cls, jstring jname, jintArray jkeys) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -errno;
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    jsize n = (*env)->GetArrayLength(env, jkeys);
    jint *keys = (*env)->GetIntArrayElements(env, jkeys, NULL);
    for (jsize i = 0; i < n; i++) ioctl(fd, UI_SET_KEYBIT, keys[i]);
    (*env)->ReleaseIntArrayElements(env, jkeys, keys, JNI_ABORT);

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    strncpy(dev.name, name, UINPUT_MAX_NAME_SIZE - 1);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    dev.id.bustype = BUS_VIRTUAL;
    dev.id.vendor = 0x4d55;   /* "MU" */
    dev.id.product = 0x0001;
    dev.id.version = 1;
    if (write(fd, &dev, sizeof(dev)) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        int err = errno;
        close(fd);
        return -err;
    }
    return fd;
}

JNIEXPORT jboolean JNICALL
Java_io_github_marsinsanity_mainui_tools_KeyInject_nativePress(JNIEnv *env, jclass cls, jint fd, jintArray jkeys, jint holdMs) {
    jsize n = (*env)->GetArrayLength(env, jkeys);
    jint *keys = (*env)->GetIntArrayElements(env, jkeys, NULL);
    for (jsize i = 0; i < n; i++) emit(fd, EV_KEY, keys[i], 1);
    emit(fd, EV_SYN, SYN_REPORT, 0);
    usleep((useconds_t) (holdMs > 0 ? holdMs : 1) * 1000);
    for (jsize i = n - 1; i >= 0; i--) emit(fd, EV_KEY, keys[i], 0);
    emit(fd, EV_SYN, SYN_REPORT, 0);
    (*env)->ReleaseIntArrayElements(env, jkeys, keys, JNI_ABORT);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_io_github_marsinsanity_mainui_tools_KeyInject_nativeClose(JNIEnv *env, jclass cls, jint fd) {
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}
