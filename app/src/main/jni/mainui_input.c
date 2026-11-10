/*
 * A virtual keyboard through /dev/uinput (root), for apps that ignore keys from Android's
 * virtual keyboard device (what `input keyevent` uses). RetroArch is one: it only takes
 * keyboard keys from a device it registered as a keyboard.
 *
 * Called through JNI from io.github.marsinsanity.mainui.tools.KeyInject, which MainUI runs as
 * root with app_process. The device only has F keys, so Android sees an internal,
 * non-alphabetic keyboard: the on-screen keyboard keeps working as before.
 *
 * Also [nativeNudge]: input written into a real controller's own /dev/input node, so apps see it
 * from that controller. (Since Android 12 every event injected through Android itself arrives
 * from device -1, "Virtual", which RetroArch takes for a new controller and gives a port.)
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
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

/* ---- nudge: a real controller re-reports where its sticks and triggers are ------------------ */

#define LONG_BITS (sizeof(unsigned long) * 8)
#define NLONGS(x) (((x) + LONG_BITS - 1) / LONG_BITS)

static int has_bit(int bit, const unsigned long *bits) {
    return (int) ((bits[bit / LONG_BITS] >> (bit % LONG_BITS)) & 1UL);
}

static int read_text(const char *path, char *buf, size_t n) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t r = read(fd, buf, n - 1);
    close(fd);
    if (r < 0) return -1;
    buf[r] = 0;
    char *nl = strchr(buf, '\n');
    if (nl) *nl = 0;
    return 0;
}

/* Sticks first (a blip far below any dead zone), triggers last. Never the d-pad hat. */
static const int NUDGE_AXES[] = { ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE };

/*
 * Moves one axis of the controller open on [fd] a hair and back. Android's reader then sends
 * the app in front two motion events from that controller, each carrying every axis's current
 * value: a trigger let go while another window had focus is seen released. The step is just
 * past the kernel's fuzz filter (2 x fuzz) and Android's (fuzz, or a quarter of the flat zone).
 * Returns the axis code + 1, 0 without a suitable axis, or -errno.
 */
static int nudge_fd(int fd) {
    unsigned long abs_bits[NLONGS(ABS_CNT)];
    memset(abs_bits, 0, sizeof(abs_bits));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) < 0) return -errno;
    for (size_t i = 0; i < sizeof(NUDGE_AXES) / sizeof(NUDGE_AXES[0]); i++) {
        int code = NUDGE_AXES[i];
        if (!has_bit(code, abs_bits)) continue;
        struct input_absinfo ai;
        if (ioctl(fd, EVIOCGABS(code), &ai) < 0) continue;
        int range = ai.maximum - ai.minimum;
        if (range < 32) continue;
        int step = 2 * ai.fuzz + 1;
        if (ai.flat / 4 + 2 > step) step = ai.flat / 4 + 2;
        if (step > range / 8) continue; /* that would be a real movement */
        int v = ai.value + step <= ai.maximum ? ai.value + step : ai.value - step;
        emit(fd, EV_ABS, code, v);
        emit(fd, EV_SYN, SYN_REPORT, 0);
        usleep(30000);
        emit(fd, EV_ABS, code, ai.value);
        emit(fd, EV_SYN, SYN_REPORT, 0);
        return code + 1;
    }
    return 0;
}

/*
 * Nudges the controller called [jname] with [vendor] / [product] (as Android's InputDevice
 * reports them) through its /dev/input/event node. Returns the axis code + 1 when done, 0 when
 * it has no axis that can be nudged unnoticed, -ENODEV when no node matches, or -errno.
 */
JNIEXPORT jint JNICALL
Java_io_github_marsinsanity_mainui_tools_KeyInject_nativeNudge(JNIEnv *env, jclass cls, jstring jname, jint vendor, jint product) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    int result = -ENODEV;
    DIR *dir = opendir("/sys/class/input");
    if (!dir) result = -errno;
    struct dirent *e;
    while (dir && (e = readdir(dir)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[300], buf[256];
        snprintf(path, sizeof(path), "/sys/class/input/%s/device/name", e->d_name);
        if (read_text(path, buf, sizeof(buf)) < 0 || strcmp(buf, name) != 0) continue;
        snprintf(path, sizeof(path), "/sys/class/input/%s/device/id/vendor", e->d_name);
        if (read_text(path, buf, sizeof(buf)) < 0 || (int) strtol(buf, NULL, 16) != vendor) continue;
        snprintf(path, sizeof(path), "/sys/class/input/%s/device/id/product", e->d_name);
        if (read_text(path, buf, sizeof(buf)) < 0 || (int) strtol(buf, NULL, 16) != product) continue;
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDWR | O_CLOEXEC);
        if (fd < 0) { if (result < 0) result = -errno; continue; }
        int r = nudge_fd(fd);
        close(fd);
        if (r > 0 || result < 0) result = r; /* one node with axes is enough to report success */
    }
    if (dir) closedir(dir);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return result;
}
