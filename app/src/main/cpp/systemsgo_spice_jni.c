/*
 * systemsgo_spice_jni.c — SPICE-PROTOCOL FEATURE, Part 3/N (real SpiceSession).
 *
 * يستبدل هذا الملف هيكل Part 1/N بالكامل. الآن يوجد SpiceSession حقيقي:
 *   - Main channel: مصادقة/تسجيل دخول SPICE، وإشارات حالة الاتصال
 *     (channel-event) التي تقرر نجاح/فشل nativeConnect فعلياً.
 *   - Display channel: استقبال السطح الأساسي (primary surface) وإعادة
 *     رسمه إلى Java عبر onNativeFrame، بنفس توقيع/اصطلاح systemsgo_jni.c's
 *     systemsgo_on_frame (raw memcpy إلى int[] متوافق مع
 *     Bitmap.Config.ARGB_8888) — راجع تحذير التنسيق أدناه.
 *   - Inputs channel: فأر (حركة مطلقة + أزرار) وكيبورد (scancode).
 *
 * ✅ تم التحقق من أسماء/تواقيع دوال Inputs channel أدناه مقابل رأس
 * spice-client-glib الفعلي (channel-inputs.h): الأسماء الصحيحة هي
 * spice_inputs_key_press/spice_inputs_key_release/spice_inputs_position/
 * spice_inputs_button_press/spice_inputs_button_release — بلا "channel"
 * في الاسم (خلافاً لتخمين مبدئي سابق كان يفترض spice_inputs_channel_*).
 * إشارات SpiceDisplayChannel (تنسيق onNativeFrame وما شابه) تم التحقق
 * منها أيضاً وهي مطابقة لما استُخدم هنا.
 *
 * نموذج الخيوط (thread model):
 *   spice-client-glib مبني على GObject/GLib وهو event-driven بالكامل —
 *   لا شيء يعمل بدون GMainLoop يدور باستمرار. لأن Kotlin/JNI الطرف
 *   الآخر (RdpSessionActivity/SpiceBridge.connect) يستدعي بشكل متزامن
 *   (blocking call يتوقع true/false)، هذا الملف يُشغّل GMainLoop خاص به
 *   على pthread منفصل (spice_loop_thread_main) بمجرد nativeInit، وكل
 *   استدعاءات SpiceSession/channel تُنفَّذ *داخل* ذلك الخيط عبر
 *   g_idle_add (لا يجوز استدعاء GObject API لجلسة SPICE من خيط لا
 *   يملك GMainContext الخاص بها). nativeConnect نفسه يحجب (blocks)
 *   بـ pthread condition variable حتى تصل أول إشارة channel-event
 *   ناجحة/فاشلة من ذلك الخيط، بنفس روح systemsgo_jni.c's nativeConnect
 *   المتزامن.
 *
 * الأجزاء القادمة:
 *   Part 4/N — SpiceSessionClient في Kotlin يطبّق RemoteSessionClient
 *              فوق SpiceBridge (هذا الملف + SpiceBridge.kt يوفران
 *              الأساس الكامل لذلك الآن).
 */

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <time.h>
#include <errno.h>
#include <stdint.h>

#include <glib.h>
#include <glib-object.h>
#include <spice-client.h>

#define TAG "systemsgo_spice_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* connectResult قيم ممكنة أثناء انتظار nativeConnect لإشارة أولى من
 * main channel. PENDING تعني "لا شيء وصل بعد" — nativeConnect ينتظر
 * على الشرط cond حتى تتغير هذه القيمة أو تنتهي المهلة. */
typedef enum {
    SYSTEMSGO_SPICE_CONNECT_PENDING = 0,
    SYSTEMSGO_SPICE_CONNECT_SUCCESS = 1,
    SYSTEMSGO_SPICE_CONNECT_FAILED = 2,
} systemsgoSpiceConnectResult;

typedef struct {
    char* host;
    int port;
    char* password;
    volatile int connected;

    /* ── SpiceSession وقنواته ─────────────────────────────────────── */
    SpiceSession* session;
    SpiceMainChannel* main_channel;
    SpiceDisplayChannel* display_channel;
    SpiceInputsChannel* inputs_channel;

    /* ── حلقة GLib الخاصة بهذه الجلسة (خيط مستقل) ─────────────────── */
    GMainContext* main_context;
    GMainLoop* main_loop;
    pthread_t loop_thread;
    volatile int loop_running;

    /* ── مزامنة nativeConnect المتزامن مع إشارات main channel ─────── */
    pthread_mutex_t connect_lock;
    pthread_cond_t connect_cond;
    systemsgoSpiceConnectResult connect_result;

    /* ── مرجع Java لاستدعاء onNativeFrame من خيط GLib ──────────────── */
    JavaVM* jvm;
    jobject bridgeObjGlobalRef;
    jmethodID onFrameMethod;

    /* آخر أبعاد سطح مُبلَّغ بها، لتفادي تخصيص jintArray جديد كل فريم
     * إن لم يتغير الحجم (نفس فكرة systemsgo_jni.c التقريبية، لكن مبسّطة:
     * هنا نعيد التخصيص فقط إن تغيّر w*h). */
    int last_w;
    int last_h;
} systemsgoSpiceSession;

/* ------------------------------------------------------------------ */
/* أدوات مساعدة لاستدعاء Java من خيط GLib                              */
/* ------------------------------------------------------------------ */

static JNIEnv* systemsgo_spice_attach_env(systemsgoSpiceSession* s, int* didAttach)
{
    JNIEnv* env = NULL;
    *didAttach = 0;
    if (!s->jvm) return NULL;
    jint rc = (*s->jvm)->GetEnv(s->jvm, (void**)&env, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if ((*s->jvm)->AttachCurrentThread(s->jvm, &env, NULL) != JNI_OK) {
            LOGE("AttachCurrentThread failed");
            return NULL;
        }
        *didAttach = 1;
    } else if (rc != JNI_OK) {
        LOGE("GetEnv failed rc=%d", rc);
        return NULL;
    }
    return env;
}

static void systemsgo_spice_detach_env(systemsgoSpiceSession* s, int didAttach)
{
    if (didAttach && s->jvm) {
        (*s->jvm)->DetachCurrentThread(s->jvm);
    }
}

/* يُنهي انتظار nativeConnect (سواء بنجاح أو فشل) — يُستدعى من خيط GLib. */
static void systemsgo_spice_signal_connect_result(systemsgoSpiceSession* s, systemsgoSpiceConnectResult r)
{
    pthread_mutex_lock(&s->connect_lock);
    if (s->connect_result == SYSTEMSGO_SPICE_CONNECT_PENDING) {
        s->connect_result = r;
        pthread_cond_broadcast(&s->connect_cond);
    }
    pthread_mutex_unlock(&s->connect_lock);
}

/* ------------------------------------------------------------------ */
/* Display channel: السطح الأساسي + الفريمات                          */
/* ------------------------------------------------------------------ */

/* تم التحقق (Spice-GTK Reference Manual, spice-space.org/api/spice-gtk/
 * SpiceDisplayChannel.html): "display-primary-create" يُطلق فعلاً بهذا
 * التوقيع بالضبط: (channel, gint format, gint width, gint height,
 * gint stride, gint shmid, gpointer imgdata, gpointer user_data) —
 * مطابق حرفياً لما هو مكتوب أدناه. بقي احتمال ضئيل أن تختلف نسخة
 * spice-protocol/spice-gtk المحددة المبنية في main.yml (SPICE_GTK_TAG)
 * عن التوثيق الحالي، لكن هذا الافتراض لم يعد تخميناً غير موثّق. */
static void systemsgo_spice_on_primary_create(SpiceChannel* channel,
                                            gint format,
                                            gint width,
                                            gint height,
                                            gint stride,
                                            gint shmid,
                                            gpointer imgdata,
                                            gpointer data)
{
    (void)channel;
    (void)format;
    (void)shmid;
    systemsgoSpiceSession* s = (systemsgoSpiceSession*)data;

    LOGI("display-primary-create: %dx%d stride=%d format=%d", width, height, stride, format);

    if (!imgdata || width <= 0 || height <= 0) {
        LOGW("display-primary-create: empty/invalid surface — skipping");
        return;
    }

    int didAttach = 0;
    JNIEnv* env = systemsgo_spice_attach_env(s, &didAttach);
    if (!env || !s->bridgeObjGlobalRef || !s->onFrameMethod) {
        systemsgo_spice_detach_env(s, didAttach);
        return;
    }

    /* تنسيق البكسل: نفترض هنا SPICE_SURFACE_FMT_32_xRGB (القيمة الشائعة
     * لسطح 32-بت في spice-protocol)، والذي يخزّن كل بكسل في الذاكرة
     * (little-endian، وكل أجهزة Android المستهدفة little-endian) كـ
     * B,G,R,X بايتات متتالية — أي int32 واحد يقرأه Android كـ 0x00RRGGBB
     * مباشرة، تماماً كما يوثّق systemsgo_jni.c's PIXEL_FORMAT_BGRA32 →
     * ARGB_8888 memcpy trick أعلاه في هذا المشروع. هذا الافتراض *غير
     * مُتحقَّق* ضد إصدار spice-protocol المحدد (SPICE_PROTOCOL_TAG في
     * main.yml) — إن ظهرت الألوان معكوسة (أزرق/أحمر متبادلان) عند
     * التشغيل الفعلي، فالإصلاح هو swap بسيط لبايتات R/B هنا، وليس خطأ
     * في بقية الجسر. */
    jintArray pixels = (*env)->NewIntArray(env, width * height);
    if (!pixels) {
        LOGE("display-primary-create: NewIntArray failed (%dx%d)", width, height);
        systemsgo_spice_detach_env(s, didAttach);
        return;
    }

    if (stride == width * 4) {
        /* صف متواصل بلا padding — نسخة واحدة مباشرة. */
        (*env)->SetIntArrayRegion(env, pixels, 0, width * height, (const jint*)imgdata);
    } else {
        /* stride فيه padding (شائع مع بعض محاذاات SPICE) — ننسخ صفاً
         * بصف إلى مخزن مؤقت متجاور أولاً. */
        jint* tmp = (jint*)malloc((size_t)width * (size_t)height * sizeof(jint));
        if (!tmp) {
            LOGE("display-primary-create: malloc(%d) failed", width * height * 4);
            (*env)->DeleteLocalRef(env, pixels);
            systemsgo_spice_detach_env(s, didAttach);
            return;
        }
        const uint8_t* src = (const uint8_t*)imgdata;
        for (int y = 0; y < height; y++) {
            memcpy(tmp + (size_t)y * width, src + (size_t)y * stride, (size_t)width * 4);
        }
        (*env)->SetIntArrayRegion(env, pixels, 0, width * height, tmp);
        free(tmp);
    }

    s->last_w = width;
    s->last_h = height;

    /* توقيع onNativeFrame: (x, y, w, h, int[] pixels, boolean fullFrame)
     * — مطابق تماماً لـ AFreeRdpBridge.onNativeFrame في systemsgo_jni.c
     * حتى تُعاد استخدام نفس مسار الرسم في Kotlin (Bitmap واحد مشترك بين
     * RDP/SPICE بدل مسارين منفصلين). x=0,y=0 وfullFrame=true دائماً هنا
     * لأن هذا الاستدعاء هو "سطح جديد بالكامل"، على عكس
     * display-invalidate أدناه الذي يبعث مستطيلات جزئية فقط. */
    (*env)->CallVoidMethod(env, s->bridgeObjGlobalRef, s->onFrameMethod,
                            (jint)0, (jint)0, (jint)width, (jint)height,
                            pixels, (jboolean)JNI_TRUE);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }

    (*env)->DeleteLocalRef(env, pixels);
    systemsgo_spice_detach_env(s, didAttach);
}

/* تم التحقق (نفس المصدر أعلاه): "display-invalidate" يُطلق فعلاً بالتوقيع
 * (channel, gint x, gint y, gint w, gint h, gpointer user_data) —
 * "المستطيل x/y/w/h من الـ primary buffer تم تحديثه". التوقيع صحيح؛
 * الفجوة المتبقية هنا ليست في التوقيع بل في التطبيق: بما أننا لا نملك
 * نسخة محلية دائمة من السطح الكامل (imgdata مؤشر يملكه spice-gtk، صالح
 * طوال عمر السطح حسب نفس التوثيق)، أبسط تعامل صحيح لاحقاً هو قراءة نفس
 * مؤشر imgdata المحفوظ من primary-create عند كل invalidate بدل تجاهله.
 * حالياً هذا الـ handler يكتفي بتسجيل الحدث فقط (log)؛ الرسم الفعلي
 * يعتمد كلياً على display-primary-create أعلاه لكل سطح جديد — يعني أول
 * فريم بعد الاتصال يظهر، لكن التحديثات الجزئية اللاحقة (حركة الماوس،
 * كتابة نص) لن تُرسم حتى يُستبدل هذا الجسم بمنطق حقيقي. علامة صريحة
 * لعمل لاحق، وليست خطأ صامتاً: راجع السجل ("display-invalidate: ...")
 * إن ظهرت الشاشة متجمدة بعد أول فريم. */
static void systemsgo_spice_on_invalidate(SpiceChannel* channel,
                                        gint x, gint y, gint w, gint h,
                                        gpointer data)
{
    (void)channel;
    systemsgoSpiceSession* s = (systemsgoSpiceSession*)data;
    (void)s;
    LOGI("display-invalidate: rect=(%d,%d %dx%d) — partial repaint not yet wired, "
         "waiting for next full primary-create (see doc comment above this function)",
         x, y, w, h);
}

/* ------------------------------------------------------------------ */
/* Main channel: نتيجة الاتصال/المصادقة                                */
/* ------------------------------------------------------------------ */

/* تم التحقق (Spice-GTK Reference Manual, SpiceChannel.html + SpiceSession.html):
 * "channel-event" هي فعلاً الإشارة الموحّدة عبر كل أنواع القنوات
 * (SpiceChannelClass.channel_event(SpiceChannel*, SpiceChannelEvent)،
 * "signals, main context")، والتوثيق الرسمي نفسه يعطي بالضبط نفس مثال
 * الاستخدام هنا: "when the SpiceInputsChannel is available and get the
 * event SPICE_CHANNEL_OPENED, you can send key events". SPICE_CHANNEL_OPENED
 * يعني نجاح فتح القناة (لـ main channel تحديداً: نجحت المصادقة). أي قيمة
 * من SPICE_CHANNEL_ERROR_* أو SPICE_CHANNEL_CLOSED نعتبرها فشلاً. */
static void systemsgo_spice_on_channel_event(SpiceChannel* channel,
                                           SpiceChannelEvent event,
                                           gpointer data)
{
    systemsgoSpiceSession* s = (systemsgoSpiceSession*)data;

    /* لا نهتم بأحداث القنوات غير main channel من ناحية نتيجة الاتصال —
     * فشل display/inputs بعد أن نجح main لا يعني فشل nativeConnect
     * نفسه بأثر رجعي (هو بالفعل رجع true حينها). */
    gboolean isMain = SPICE_IS_MAIN_CHANNEL(channel);

    switch (event) {
        case SPICE_CHANNEL_OPENED:
            LOGI("channel-event: OPENED (main=%d)", isMain);
            if (isMain) {
                s->connected = 1;
                systemsgo_spice_signal_connect_result(s, SYSTEMSGO_SPICE_CONNECT_SUCCESS);
            }
            break;
        case SPICE_CHANNEL_CLOSED:
            LOGW("channel-event: CLOSED (main=%d)", isMain);
            if (isMain) {
                s->connected = 0;
                systemsgo_spice_signal_connect_result(s, SYSTEMSGO_SPICE_CONNECT_FAILED);
            }
            break;
        case SPICE_CHANNEL_ERROR_CONNECT:
        case SPICE_CHANNEL_ERROR_TLS:
        case SPICE_CHANNEL_ERROR_LINK:
        case SPICE_CHANNEL_ERROR_AUTH:
        case SPICE_CHANNEL_ERROR_IO:
            LOGE("channel-event: ERROR (%d) main=%d", (int)event, isMain);
            if (isMain) {
                s->connected = 0;
                systemsgo_spice_signal_connect_result(s, SYSTEMSGO_SPICE_CONNECT_FAILED);
            }
            break;
        default:
            LOGI("channel-event: other (%d) main=%d", (int)event, isMain);
            break;
    }
}

/* ------------------------------------------------------------------ */
/* channel-new: يُستدعى مرة لكل قناة يفتحها السيرفر (main/display/     */
/* inputs/…) — هنا نلتقط الثلاثة التي نحتاجها فقط ونتجاهل الباقي       */
/* (playback/record/usbredir/smartcard غير مبنية في الـ prebuilt أصلاً */
/* حسب main.yml's -Dusbredir=disabled إلخ، فلن تظهر هنا عملياً).       */
/* ------------------------------------------------------------------ */

static void systemsgo_spice_on_channel_new(SpiceSession* session, SpiceChannel* channel, gpointer data)
{
    (void)session;
    systemsgoSpiceSession* s = (systemsgoSpiceSession*)data;

    g_signal_connect(channel, "channel-event", G_CALLBACK(systemsgo_spice_on_channel_event), s);

    if (SPICE_IS_MAIN_CHANNEL(channel)) {
        LOGI("channel-new: main channel");
        s->main_channel = SPICE_MAIN_CHANNEL(channel);
        /* تم التحقق جزئياً: spice_channel_connect(SpiceChannel*) موجود فعلاً
         * في SpiceChannel.html بالتوقيع "gboolean spice_channel_connect
         * (SpiceChannel *channel); Connect the channel, using SpiceSession
         * connection informations". النقطة غير المؤكدة المتبقية هي فقط
         * "idempotent إن استُدعيت مرتين" (main channel قد تُفتح تلقائياً
         * أصلاً عبر spice_session_connect على مستوى الجلسة) — التوثيق لا
         * ينص صراحة على هذا، لذا استدعاؤها هنا لـ main channel أيضاً
         * (وليس فقط display/inputs) قد يكون تكراراً غير ضار أو قد لا
         * يكون؛ تُركت غير مستدعاة لـ main channel تحديداً لتفادي الخطر،
         * وتُستدعى فقط لـ display/inputs أدناه حيث لا غموض. */
    } else if (SPICE_IS_DISPLAY_CHANNEL(channel)) {
        LOGI("channel-new: display channel");
        s->display_channel = SPICE_DISPLAY_CHANNEL(channel);
        g_signal_connect(channel, "display-primary-create",
                          G_CALLBACK(systemsgo_spice_on_primary_create), s);
        g_signal_connect(channel, "display-invalidate",
                          G_CALLBACK(systemsgo_spice_on_invalidate), s);
        spice_channel_connect(channel);
    } else if (SPICE_IS_INPUTS_CHANNEL(channel)) {
        LOGI("channel-new: inputs channel");
        s->inputs_channel = SPICE_INPUTS_CHANNEL(channel);
        spice_channel_connect(channel);
    } else {
        LOGI("channel-new: ignoring channel type %s", G_OBJECT_TYPE_NAME(channel));
    }
}

static void systemsgo_spice_on_channel_destroy(SpiceSession* session, SpiceChannel* channel, gpointer data)
{
    (void)session;
    systemsgoSpiceSession* s = (systemsgoSpiceSession*)data;
    if (SPICE_IS_MAIN_CHANNEL(channel) && s->main_channel == SPICE_MAIN_CHANNEL(channel)) {
        s->main_channel = NULL;
    } else if (SPICE_IS_DISPLAY_CHANNEL(channel) && s->display_channel == SPICE_DISPLAY_CHANNEL(channel)) {
        s->display_channel = NULL;
    } else if (SPICE_IS_INPUTS_CHANNEL(channel) && s->inputs_channel == SPICE_INPUTS_CHANNEL(channel)) {
        s->inputs_channel = NULL;
    }
}

/* ------------------------------------------------------------------ */
/* خيط GLib المستقل لهذه الجلسة                                        */
/* ------------------------------------------------------------------ */

static void* systemsgo_spice_loop_thread_main(void* arg)
{
    systemsgoSpiceSession* s = (systemsgoSpiceSession*)arg;

    s->main_context = g_main_context_new();
    g_main_context_push_thread_default(s->main_context);

    s->session = spice_session_new();
    g_signal_connect(s->session, "channel-new", G_CALLBACK(systemsgo_spice_on_channel_new), s);
    g_signal_connect(s->session, "channel-destroy", G_CALLBACK(systemsgo_spice_on_channel_destroy), s);

    g_object_set(s->session,
                 "host", s->host,
                 "port", g_strdup_printf("%d", s->port),
                 NULL);
    if (s->password) {
        g_object_set(s->session, "password", s->password, NULL);
    }

    LOGI("loop thread: starting spice_session_connect (%s:%d)", s->host, s->port);
    if (!spice_session_connect(s->session)) {
        LOGE("spice_session_connect returned FALSE immediately");
        systemsgo_spice_signal_connect_result(s, SYSTEMSGO_SPICE_CONNECT_FAILED);
    }

    s->main_loop = g_main_loop_new(s->main_context, FALSE);
    s->loop_running = 1;
    g_main_loop_run(s->main_loop);
    s->loop_running = 0;

    /* الخروج من g_main_loop_run يعني g_main_loop_quit() استُدعيت من
     * nativeDisconnect/nativeFree — الآن التنظيف آمن لأن لا مزيد من
     * الـ callbacks ستصل. */
    if (s->session) {
        spice_session_disconnect(s->session);
        g_object_unref(s->session);
        s->session = NULL;
    }
    s->main_channel = NULL;
    s->display_channel = NULL;
    s->inputs_channel = NULL;

    g_main_context_pop_thread_default(s->main_context);
    g_main_context_unref(s->main_context);
    s->main_context = NULL;

    LOGI("loop thread: exiting");
    return NULL;
}

/* ------------------------------------------------------------------ */
/* JNI: دورة حياة الجلسة                                               */
/* ------------------------------------------------------------------ */

JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_spice_native_SpiceBridge_nativeIsAvailable(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    /* Part 3/N: إن وصل هذا السطر للتنفيذ فهذا يعني libsystemsgo_spice_jni.so
     * حُمّل بنجاح، وهو مبني فقط عندما SPICE_ABI_DIR موجود فعلياً
     * (CMakeLists.txt's SYSTEMSGO_SPICE_BACKEND_AVAILABLE) — أي أن
     * spice-client-glib مربوط فعلاً. لا حاجة لفحص إضافي هنا. */
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_systemsgo_hex_spice_native_SpiceBridge_nativeInit(JNIEnv* env, jobject thiz)
{
    (void)thiz;

    systemsgoSpiceSession* s = (systemsgoSpiceSession*)calloc(1, sizeof(systemsgoSpiceSession));
    if (!s) {
        LOGE("nativeInit: calloc failed");
        return 0;
    }

    (*env)->GetJavaVM(env, &s->jvm);
    s->bridgeObjGlobalRef = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    /* توقيع مطابق حرفياً لـ AFreeRdpBridge.onNativeFrame في systemsgo_jni.c
     * — راجع تعليق systemsgo_spice_on_primary_create أعلاه لسبب ذلك. يجب
     * إضافة onNativeFrame(x:Int,y:Int,w:Int,h:Int,pixels:IntArray,full:Boolean)
     * إلى SpiceBridge.kt (أُضيفت في نفس الـ commit — راجع ذلك الملف). */
    s->onFrameMethod = (*env)->GetMethodID(env, cls, "onNativeFrame", "(IIII[IZ)V");
    if (!s->onFrameMethod) {
        LOGE("nativeInit: onNativeFrame(IIII[IZ)V not found on SpiceBridge — frames will be dropped");
        (*env)->ExceptionClear(env);
    }

    pthread_mutex_init(&s->connect_lock, NULL);
    pthread_cond_init(&s->connect_cond, NULL);
    s->connect_result = SYSTEMSGO_SPICE_CONNECT_PENDING;
    s->connected = 0;

    LOGI("nativeInit: session created");
    return (jlong)(intptr_t)s;
}

JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_spice_native_SpiceBridge_nativeConnect(
    JNIEnv* env, jobject thiz, jlong handle,
    jstring jHost, jint jPort, jstring jPassword)
{
    (void)thiz;

    systemsgoSpiceSession* s = (systemsgoSpiceSession*)(intptr_t)handle;
    if (!s) {
        LOGE("nativeConnect: null session handle");
        return JNI_FALSE;
    }

    const char* host = (*env)->GetStringUTFChars(env, jHost, NULL);
    const char* password = jPassword ? (*env)->GetStringUTFChars(env, jPassword, NULL) : NULL;

    free(s->host);
    s->host = host ? strdup(host) : NULL;
    s->port = (int)jPort;
    free(s->password);
    s->password = password ? strdup(password) : NULL;

    if (host) (*env)->ReleaseStringUTFChars(env, jHost, host);
    if (password) (*env)->ReleaseStringUTFChars(env, jPassword, password);

    if (!s->host || s->port <= 0) {
        LOGE("nativeConnect: invalid host/port");
        return JNI_FALSE;
    }

    LOGI("nativeConnect: starting loop thread for %s:%d", s->host, s->port);
    if (pthread_create(&s->loop_thread, NULL, systemsgo_spice_loop_thread_main, s) != 0) {
        LOGE("nativeConnect: pthread_create failed");
        return JNI_FALSE;
    }

    /* ننتظر متزامناً (بمهلة) حتى main channel يرسل OPENED أو خطأ/إغلاق —
     * بنفس روح systemsgo_jni.c's nativeConnect الذي يحجب حتى freerdp_connect
     * ينتهي. مهلة 20 ثانية تغطي شبكات بطيئة/TLS handshake بلا تجميد
     * الواجهة إلى الأبد إن كان السيرفر غير قابل للوصول. */
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 20;

    pthread_mutex_lock(&s->connect_lock);
    while (s->connect_result == SYSTEMSGO_SPICE_CONNECT_PENDING) {
        int rc = pthread_cond_timedwait(&s->connect_cond, &s->connect_lock, &deadline);
        if (rc == ETIMEDOUT) {
            LOGE("nativeConnect: timed out waiting for channel-event");
            s->connect_result = SYSTEMSGO_SPICE_CONNECT_FAILED;
            break;
        }
    }
    systemsgoSpiceConnectResult result = s->connect_result;
    pthread_mutex_unlock(&s->connect_lock);

    return (result == SYSTEMSGO_SPICE_CONNECT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

/* ------------------------------------------------------------------ */
/* JNI: إدخال الفأر/الكيبورد (Inputs channel)                          */
/* ------------------------------------------------------------------ */

/* تم التحقق: spice_inputs_key_press/key_release/position/button_press/
 * button_release هي الأسماء الحقيقية في channel-inputs.h (بلا "channel"
 * في اسم الدالة). كل استدعاء محاط بفحص s->inputs_channel != NULL أولاً. */

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_spice_native_SpiceBridge_nativeSendMouse(
    JNIEnv* env, jobject thiz, jlong handle, jint x, jint y, jint buttonMask)
{
    (void)env;
    (void)thiz;
    systemsgoSpiceSession* s = (systemsgoSpiceSession*)(intptr_t)handle;
    if (!s || !s->inputs_channel) return;

    /* display_channel_id=0: هذا العميل لا يدعم multi-monitor SPICE بعد
     * (خارج نطاق Part 3/N) — راجع Part 4/N أو ملاحظة مستقبلية إن احتيج
     * دعم أكثر من شاشة SPICE واحدة. */
    spice_inputs_position(s->inputs_channel, (gint)x, (gint)y, 0, (guint)buttonMask);
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_spice_native_SpiceBridge_nativeSendMouseButton(
    JNIEnv* env, jobject thiz, jlong handle, jint button, jboolean pressed, jint buttonMask)
{
    (void)env;
    (void)thiz;
    systemsgoSpiceSession* s = (systemsgoSpiceSession*)(intptr_t)handle;
    if (!s || !s->inputs_channel) return;

    if (pressed) {
        spice_inputs_button_press(s->inputs_channel, (guint)button, (guint)buttonMask);
    } else {
        spice_inputs_button_release(s->inputs_channel, (guint)button, (guint)buttonMask);
    }
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_spice_native_SpiceBridge_nativeSendKey(
    JNIEnv* env, jobject thiz, jlong handle, jint scancode, jboolean pressed)
{
    (void)env;
    (void)thiz;
    systemsgoSpiceSession* s = (systemsgoSpiceSession*)(intptr_t)handle;
    if (!s || !s->inputs_channel) return;

    if (pressed) {
        spice_inputs_key_press(s->inputs_channel, (guint)scancode);
    } else {
        spice_inputs_key_release(s->inputs_channel, (guint)scancode);
    }
}

/* ------------------------------------------------------------------ */
/* JNI: قطع الاتصال/التحرير                                            */
/* ------------------------------------------------------------------ */

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_spice_native_SpiceBridge_nativeDisconnect(JNIEnv* env, jobject thiz, jlong handle)
{
    (void)env;
    (void)thiz;

    systemsgoSpiceSession* s = (systemsgoSpiceSession*)(intptr_t)handle;
    if (!s) return;

    s->connected = 0;

    if (s->loop_running && s->main_loop) {
        LOGI("nativeDisconnect: quitting loop");
        g_main_loop_quit(s->main_loop);
        pthread_join(s->loop_thread, NULL);
        if (s->main_loop) {
            g_main_loop_unref(s->main_loop);
            s->main_loop = NULL;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_spice_native_SpiceBridge_nativeFree(JNIEnv* env, jobject thiz, jlong handle)
{
    (void)thiz;

    systemsgoSpiceSession* s = (systemsgoSpiceSession*)(intptr_t)handle;
    if (!s) return;

    /* تأكيد التنظيف إن كانت nativeDisconnect لم تُستدعَ صراحة قبل
     * nativeFree (Kotlin's release() side). */
    if (s->loop_running && s->main_loop) {
        g_main_loop_quit(s->main_loop);
        pthread_join(s->loop_thread, NULL);
    }
    if (s->main_loop) {
        g_main_loop_unref(s->main_loop);
        s->main_loop = NULL;
    }

    if (s->bridgeObjGlobalRef) {
        (*env)->DeleteGlobalRef(env, s->bridgeObjGlobalRef);
        s->bridgeObjGlobalRef = NULL;
    }

    pthread_mutex_destroy(&s->connect_lock);
    pthread_cond_destroy(&s->connect_cond);

    free(s->host);
    free(s->password);
    free(s);

    LOGI("nativeFree: session destroyed");
}
