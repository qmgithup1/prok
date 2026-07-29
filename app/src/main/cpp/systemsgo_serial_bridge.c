/*
 * SERIAL-OVER-NETWORK FEATURE — PART 2 (native/JNI layer).
 *
 * See com.systemsgo.hex.rdp.serial.SerialNetworkBridge's class doc for the
 * Kotlin side of this feature and com.systemsgo.hex.data.model.RdpProfile.
 * serialRedirectMode's doc for what it's for.
 *
 * ## The problem
 * FreeRDP's serial channel (MS-RDPESP, libfreerdp's com.c/com_ioctl.c) is
 * written against a real local tty: systemsgo_jni.c's existing "serial"
 * freerdp_client_add_device_channel(...) block (see that file) hands it a
 * device *path*, which FreeRDP itself open()s and then issues termios
 * (TCGETS/TCSETS — baud/parity/data-bits/stop-bits) and modem-control
 * (TIOCMGET/TIOCMSET/TIOCMBIS/TIOCMBIC — DTR/RTS) ioctls on directly. A
 * plain TCP socket has no such path and does not support those ioctls at
 * all, so com.systemsgo.hex.rdp.serial.SerialNetworkBridge's TCP/RFC-2217
 * connection cannot be handed to FreeRDP as-is.
 *
 * ## The fix: a real PTY as the "serial port"
 * This file `openpty()`s a PTY pair per session. The *slave* path (e.g.
 * "/dev/pts/12") is returned to Kotlin (nativeSerialBridgeOpen's
 * jSlavePathOut) and flows straight into the existing
 * freerdp_client_add_device_channel(..., "serial", <slave path>, ...) call
 * in systemsgo_jni.c, completely unmodified — from FreeRDP's point of view it
 * is opening a completely ordinary local serial device. The *master* side
 * is owned by this file's background relay thread, which:
 *
 *   1. Bridges raw data bytes bidirectionally between the PTY master and a
 *      local AF_UNIX socket (Android's LocalSocket abstract namespace —
 *      see resolveEffectiveSerialPath's Kotlin side for the
 *      LocalServerSocket this connect()s to) that
 *      SerialNetworkBridge.attachLocalPeer() reads/writes on the Kotlin
 *      side. SerialNetworkBridge already owns the actual TCP connection
 *      and (for RFC_2217) the telnet/COM-PORT-OPTION protocol logic, so
 *      this file never has to know about sockets-to-a-remote-host or
 *      telnet at all — it only ever moves bytes between two local fds.
 *   2. Polls the PTY master's modem-control lines (TIOCMGET) and baud rate
 *      (tcgetattr/cfgetispeed) roughly 5x/second, and — on a change —
 *      calls SerialNetworkBridge.setDtr(Boolean)/setRts(Boolean)/
 *      setBaudRate(Int) via JNI, so RFC 2217 mode can relay a *real*
 *      SET-CONTROL/SET-BAUDRATE subnegotiation to the network device
 *      server. (Polling, not an ioctl hook, because there is no portable
 *      way to intercept another process's — or even this uid's own —
 *      ioctl() calls on Android without a kernel module; a tty's line
 *      state does not otherwise generate a readable/pollable event.)
 *
 * ## Known caveats — VERIFY ON A REAL DEVICE BEFORE RELYING ON THIS
 * - DTR/RTS/CTS/DSR/CD cross-mapping: this relies on the mainline Linux
 *   Unix98 PTY driver's null-modem-style TIOCMGET/TIOCMSET loopback (the
 *   side that opened the *slave* setting TIOCM_DTR/TIOCM_RTS becomes
 *   visible, cross-wired per RS-232 null-modem convention, to TIOCMGET on
 *   the *master*, and vice versa). This behavior is real (added to the
 *   mainline kernel specifically so QEMU-style chardev-pty backends can
 *   emulate modem control) but is not POSIX-specified and its exact bit
 *   mapping has not been verified against the specific AOSP/vendor kernel
 *   this app ships on. Confirm with a real RFC 2217 server + serial device
 *   before depending on DTR/RTS signalling for anything safety-critical.
 * - Baud rate here only recognizes the standard POSIX B-constants
 *   (cfgetispeed()'s return value) — arbitrary/non-standard baud rates
 *   need Linux's termios2/TCGETS2 (BOTHER) ioctl, which is deliberately
 *   NOT used here because bionic's <termios.h> already matches the
 *   kernel's struct layout and mixing it with <asm/termbits.h> in the same
 *   translation unit is a well-known landmine (duplicate/conflicting
 *   `struct termios` definitions) — if arbitrary baud rates turn out to be
 *   needed, isolate the termios2 ioctl call in its own .c file that
 *   #includes ONLY <asm/termbits.h>/<linux/ioctl.h> and exposes a small
 *   plain-int extern "C" function, rather than mixing headers here.
 * - Line-state (overrun/parity/framing errors, break) from the *local*
 *   (FreeRDP/PTY) side is not surfaced to the network server at all —
 *   only modem-control and baud rate are. Real hardware line errors on
 *   the *remote* side already flow correctly the other direction (server
 *   -> SerialNetworkBridge.SerialLineListener.onLineState).
 * - Not yet covered by an automated test (needs a real ser2net instance
 *   and, ideally, a loopback/null-modem-wired real serial adapter) — see
 *   SETUP.md's "SERIAL-OVER-NETWORK FEATURE" section (add one there) for
 *   the manual test procedure this should get before shipping.
 */

#include <jni.h>
#include <pty.h>
#include <fcntl.h>
#include <termios.h>
#include <unistd.h>
#include <pthread.h>
#include <poll.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/ioctl.h>
#include <android/log.h>

#include "systemsgo_serial_bridge.h"

#define TAG "HexRdpSerialBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Poll interval for both the PTY master's I/O readiness and its
 * modem-control/baud-rate state — see this file's top comment on why
 * polling (rather than an ioctl hook) is used for the latter. 200ms keeps
 * modem-control/baud-change latency imperceptible for a human operator
 * without spinning the relay thread. */
#define POLL_INTERVAL_MS 200

typedef struct {
    int master_fd;
    int slave_fd;   /* kept open for the bridge's lifetime so the pty isn't torn down before FreeRDP opens the slave path itself */
    int local_fd;   /* AF_UNIX socket connected to Kotlin's LocalServerSocket peer */
    pthread_t thread;
    volatile int running;

    JavaVM* jvm;
    jobject kotlin_bridge;   /* GlobalRef to the owning SerialNetworkBridge instance */
    jmethodID mid_set_dtr;   /* setDtr(Z)V   — null if lookup failed; that signalling is then skipped */
    jmethodID mid_set_rts;   /* setRts(Z)V   */
    jmethodID mid_set_baud;  /* setBaudRate(I)V */

    int last_dtr;   /* -1 = unknown yet (always reported once on first poll) */
    int last_rts;
    int last_baud;
} systemsgo_serial_bridge_t;

/* Standard POSIX speed_t (B-constant) -> bps lookup — see this file's top
 * comment on why arbitrary/non-standard rates (termios2/BOTHER) aren't
 * handled here. */
static int speed_to_baud(speed_t s) {
    switch (s) {
        case B0: return 0;
        case B50: return 50;
        case B75: return 75;
        case B110: return 110;
        case B134: return 134;
        case B150: return 150;
        case B200: return 200;
        case B300: return 300;
        case B600: return 600;
        case B1200: return 1200;
        case B1800: return 1800;
        case B2400: return 2400;
        case B4800: return 4800;
        case B9600: return 9600;
        case B19200: return 19200;
        case B38400: return 38400;
        case B57600: return 57600;
        case B115200: return 115200;
        case B230400: return 230400;
        default: return -1; /* non-standard / not representable as a plain B-constant */
    }
}

static void call_bool_method(JNIEnv* env, systemsgo_serial_bridge_t* b, jmethodID mid, int value) {
    if (!mid) return;
    (*env)->CallVoidMethod(env, b->kotlin_bridge, mid, value ? JNI_TRUE : JNI_FALSE);
    if ((*env)->ExceptionCheck(env)) {
        LOGW("Exception calling into SerialNetworkBridge (Z)V method — clearing and continuing");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void call_int_method(JNIEnv* env, systemsgo_serial_bridge_t* b, jmethodID mid, int value) {
    if (!mid) return;
    (*env)->CallVoidMethod(env, b->kotlin_bridge, mid, (jint)value);
    if ((*env)->ExceptionCheck(env)) {
        LOGW("Exception calling into SerialNetworkBridge (I)V method — clearing and continuing");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

/* Detects DTR/RTS/baud changes FreeRDP made on the PTY slave and forwards
 * them to the Kotlin-side SerialNetworkBridge — see this file's top
 * comment for the caveats on exact bit-mapping accuracy. */
static void poll_line_state(JNIEnv* env, systemsgo_serial_bridge_t* b) {
    int mctl = 0;
    if (ioctl(b->master_fd, TIOCMGET, &mctl) == 0) {
        int dtr = (mctl & TIOCM_DTR) != 0;
        int rts = (mctl & TIOCM_RTS) != 0;
        if (dtr != b->last_dtr) { b->last_dtr = dtr; call_bool_method(env, b, b->mid_set_dtr, dtr); }
        if (rts != b->last_rts) { b->last_rts = rts; call_bool_method(env, b, b->mid_set_rts, rts); }
    }
    struct termios tio;
    if (tcgetattr(b->master_fd, &tio) == 0) {
        int baud = speed_to_baud(cfgetispeed(&tio));
        if (baud > 0 && baud != b->last_baud) { b->last_baud = baud; call_int_method(env, b, b->mid_set_baud, baud); }
    }
}

static void* relay_thread_main(void* arg) {
    systemsgo_serial_bridge_t* b = (systemsgo_serial_bridge_t*)arg;
    JNIEnv* env = NULL;
    int attached = 0;
    if ((*b->jvm)->GetEnv(b->jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        JavaVMAttachArgs args = { JNI_VERSION_1_6, "HexRdpSerialBridge", NULL };
        if ((*b->jvm)->AttachCurrentThread(b->jvm, &env, &args) != 0) {
            LOGE("Failed to attach serial bridge relay thread to the JVM — aborting relay");
            return NULL;
        }
        attached = 1;
    }

    unsigned char buf[4096];
    while (b->running) {
        struct pollfd fds[2];
        fds[0].fd = b->master_fd; fds[0].events = POLLIN; fds[0].revents = 0;
        fds[1].fd = b->local_fd;  fds[1].events = POLLIN; fds[1].revents = 0;

        int rc = poll(fds, 2, POLL_INTERVAL_MS);
        if (rc < 0) {
            if (errno == EINTR) continue;
            LOGE("poll() failed on serial bridge: %s", strerror(errno));
            break;
        }

        if (rc > 0) {
            int hard_error = 0;
            if (fds[0].revents & POLLIN) {
                ssize_t n = read(b->master_fd, buf, sizeof(buf));
                if (n > 0) {
                    if (write(b->local_fd, buf, (size_t)n) < 0 && errno != EAGAIN) {
                        LOGW("write() to local peer failed: %s", strerror(errno));
                    }
                } else if (n < 0 && errno != EAGAIN && errno != EINTR) {
                    LOGW("read() from pty master failed: %s", strerror(errno));
                    hard_error = 1;
                } else if (n == 0) {
                    hard_error = 1; /* FreeRDP closed its end of the serial device */
                }
            }
            if (fds[1].revents & POLLIN) {
                ssize_t n = read(b->local_fd, buf, sizeof(buf));
                if (n > 0) {
                    if (write(b->master_fd, buf, (size_t)n) < 0 && errno != EAGAIN) {
                        LOGW("write() to pty master failed: %s", strerror(errno));
                    }
                } else if (n < 0 && errno != EAGAIN && errno != EINTR) {
                    LOGW("read() from local peer failed: %s", strerror(errno));
                    hard_error = 1;
                } else if (n == 0) {
                    hard_error = 1; /* SerialNetworkBridge's remote connection closed */
                }
            }
            if ((fds[0].revents & (POLLHUP | POLLERR | POLLNVAL)) ||
                (fds[1].revents & (POLLHUP | POLLERR | POLLNVAL))) {
                hard_error = 1;
            }
            if (hard_error) break;
        }

        poll_line_state(env, b);
    }

    LOGI("Serial-over-network relay thread exiting");
    if (attached) (*b->jvm)->DetachCurrentThread(b->jvm);
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSerialBridgeOpen(
    JNIEnv* env, jobject thiz,
    jstring jLocalSocketName, jobject jKotlinBridge, jobjectArray jSlavePathOut)
{
    (void)thiz;

    const char* localSocketName = (*env)->GetStringUTFChars(env, jLocalSocketName, NULL);
    if (!localSocketName) {
        LOGE("nativeSerialBridgeOpen: GetStringUTFChars failed");
        return 0;
    }

    int master_fd = -1, slave_fd = -1;
    char slave_name[128];
    if (openpty(&master_fd, &slave_fd, slave_name, NULL, NULL) != 0) {
        LOGE("openpty() failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jLocalSocketName, localSocketName);
        return 0;
    }

    /* Non-blocking master: the relay thread only ever reads it after
     * poll() reports POLLIN, but non-blocking avoids any residual risk of
     * wedging the relay (and thus the modem/baud poll below it) on a
     * partial-read race. */
    int flags = fcntl(master_fd, F_GETFL, 0);
    if (flags >= 0) fcntl(master_fd, F_SETFL, flags | O_NONBLOCK);

    /* Connect to the abstract-namespace AF_UNIX socket Kotlin's
     * LocalServerSocket(localSocketName) is already listening on —
     * android.net.LocalSocketAddress.Namespace.ABSTRACT is Android's name
     * for the same convention as Linux's own abstract-socket namespace: a
     * sockaddr_un whose sun_path starts with a NUL byte, followed by the
     * name (NOT NUL-terminated on the wire). The Kotlin caller
     * (AFreeRdpBridge.resolveEffectiveSerialPath) creates that
     * LocalServerSocket and starts accept()ing *before* calling this
     * function, so this connect() should never race it. */
    int local_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (local_fd < 0) {
        LOGE("socket(AF_UNIX) failed: %s", strerror(errno));
        close(master_fd); close(slave_fd);
        (*env)->ReleaseStringUTFChars(env, jLocalSocketName, localSocketName);
        return 0;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    size_t name_len = strlen(localSocketName);
    size_t max_len = sizeof(addr.sun_path) - 2; /* leave room for the leading NUL and avoid overflow */
    if (name_len > max_len) name_len = max_len;
    memcpy(addr.sun_path + 1, localSocketName, name_len);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);
    (*env)->ReleaseStringUTFChars(env, jLocalSocketName, localSocketName);

    if (connect(local_fd, (struct sockaddr*)&addr, addr_len) != 0) {
        LOGE("connect() to abstract-namespace socket failed: %s", strerror(errno));
        close(master_fd); close(slave_fd); close(local_fd);
        return 0;
    }

    systemsgo_serial_bridge_t* b = (systemsgo_serial_bridge_t*)calloc(1, sizeof(systemsgo_serial_bridge_t));
    if (!b) {
        LOGE("Out of memory allocating systemsgo_serial_bridge_t");
        close(master_fd); close(slave_fd); close(local_fd);
        return 0;
    }
    b->master_fd = master_fd;
    b->slave_fd = slave_fd;
    b->local_fd = local_fd;
    b->running = 1;
    b->last_dtr = -1;
    b->last_rts = -1;
    b->last_baud = -1;

    (*env)->GetJavaVM(env, &b->jvm);
    b->kotlin_bridge = (*env)->NewGlobalRef(env, jKotlinBridge);

    jclass cls = (*env)->GetObjectClass(env, jKotlinBridge);
    b->mid_set_dtr  = (*env)->GetMethodID(env, cls, "setDtr", "(Z)V");
    if (!b->mid_set_dtr) (*env)->ExceptionClear(env);
    b->mid_set_rts  = (*env)->GetMethodID(env, cls, "setRts", "(Z)V");
    if (!b->mid_set_rts) (*env)->ExceptionClear(env);
    b->mid_set_baud = (*env)->GetMethodID(env, cls, "setBaudRate", "(I)V");
    if (!b->mid_set_baud) (*env)->ExceptionClear(env);
    if (!b->mid_set_dtr || !b->mid_set_rts || !b->mid_set_baud) {
        LOGW("One or more SerialNetworkBridge modem-control/baud methods not found via JNI "
             "— that signalling will be skipped for this session (data relay is unaffected).");
    }

    jstring jSlaveName = (*env)->NewStringUTF(env, slave_name);
    (*env)->SetObjectArrayElement(env, jSlavePathOut, 0, jSlaveName);

    if (pthread_create(&b->thread, NULL, relay_thread_main, b) != 0) {
        LOGE("pthread_create for serial bridge relay failed: %s", strerror(errno));
        (*env)->DeleteGlobalRef(env, b->kotlin_bridge);
        close(master_fd); close(slave_fd); close(local_fd);
        free(b);
        return 0;
    }

    LOGI("Serial-over-network bridge opened: pty slave=%s", slave_name);
    return (jlong)(intptr_t)b;
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSerialBridgeClose(
    JNIEnv* env, jobject thiz, jlong jBridgeHandle)
{
    (void)thiz;
    if (jBridgeHandle == 0) return;
    systemsgo_serial_bridge_t* b = (systemsgo_serial_bridge_t*)(intptr_t)jBridgeHandle;

    b->running = 0;
    /* Deliberately does NOT close the fds before pthread_join(): the relay
     * thread is very likely still inside poll()/read()/write() on them
     * right now, and closing an fd out from under another thread risks the
     * kernel reusing that fd number for something unrelated opened
     * concurrently elsewhere in the process, which the still-running relay
     * thread could then read/write into. `running=0` combined with the
     * relay loop's own POLL_INTERVAL_MS bound is what stops it (worst case
     * ~200ms shutdown latency) — only once pthread_join() below confirms
     * the thread has actually exited is it safe to close these fds. */
    pthread_join(b->thread, NULL);
    close(b->master_fd);
    close(b->slave_fd);
    close(b->local_fd);

    (*env)->DeleteGlobalRef(env, b->kotlin_bridge);
    free(b);
    LOGI("Serial-over-network bridge closed");
}
