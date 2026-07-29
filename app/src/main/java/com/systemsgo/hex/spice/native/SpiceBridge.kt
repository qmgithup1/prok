package com.systemsgo.hex.spice.native

import android.util.Log

/**
 * SPICE-PROTOCOL FEATURE, Part 3/N (real SpiceSession).
 *
 * يوازي [com.systemsgo.hex.rdp.native.AFreeRdpBridge] بنفس الشكل تماماً،
 * بما في ذلك الآن onNativeFrame (توقيع مطابق حرفياً لـ
 * AFreeRdpBridge.onNativeFrame حتى يمكن لاحقاً — Part 4/N — رسم كلا
 * البروتوكولين على نفس مسار الـ Bitmap في Kotlin دون تفريع الكود).
 *
 * `isAvailable` يحاول تحميل المكتبة الأصلية (`libsystemsgo_spice_jni.so`) مرة
 * واحدة (lazy) ويُرجع false بأمان إن لم تكن موجودة (لم تُبنَ في CI لهذا
 * الإصدار، أو فشل بناء spice-client-glib لهذا الـ ABI)، بدل أن يُسقط
 * التطبيق.
 *
 * مُستهلك فعلياً عبر [com.systemsgo.hex.spice.protocol.SpiceSessionClient]
 * (Part 4/N)، الذي يطبّق RemoteSessionClient فوق هذا الصنف تماماً كما تفعل
 * RdpRemoteAdapter فوق AFreeRdpBridge، ومربوط بدوره بـ
 * com.systemsgo.hex.remote.RemoteSessionFactory لجلسات ProtocolType.SPICE.
 */
open class SpiceBridge {

    companion object {
        private const val TAG = "SpiceBridge"

        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("systemsgo_spice_jni")
                nativeIsAvailable()
            } catch (e: UnsatisfiedLinkError) {
                Log.i(TAG, "Native SPICE library not present for this build — " +
                    "either the SPICE prebuilt failed for this ABI or CI hasn't produced " +
                    "one yet. See app/src/main/cpp/CMakeLists.txt's SPICE-PROTOCOL FEATURE " +
                    "section and .github/workflows/main.yml's \"Build SPICE prebuilt\" step.")
                false
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native SPICE library", e)
                false
            }
        }

        private external fun nativeIsAvailable(): Boolean
    }

    private var handle: Long = 0

    /** ينشئ جلسة SPICE أصلية فارغة. يُرجع false إن فشل التخصيص. */
    fun init(): Boolean {
        handle = nativeInit()
        return handle != 0L
    }

    /**
     * يتصل فعلياً (SpiceSession + main channel) ويحجب (blocking، بمهلة
     * ~20 ثانية مضبوطة في systemsgo_spice_jni.c) حتى تصل أول إشارة نجاح/فشل
     * من السيرفر — بنفس اصطلاح AFreeRdpBridge.connect المتزامن. لذلك يجب
     * استدعاؤها من خيط عامل، وليس من الخيط الرئيسي (UI thread).
     */
    fun connect(host: String, port: Int, password: String?): Boolean {
        if (handle == 0L) return false
        return nativeConnect(handle, host, port, password)
    }

    /** حركة/موضع مطلق للفأر ضمن سطح العرض، مع قناع أزرار SPICE الحالي. */
    fun sendMousePosition(x: Int, y: Int, buttonMask: Int) {
        if (handle == 0L) return
        nativeSendMouse(handle, x, y, buttonMask)
    }

    /** button: رقم زر SPICE (1=يسار،2=وسط،3=يمين — راجع spice-constant.h). */
    fun sendMouseButton(button: Int, pressed: Boolean, buttonMask: Int) {
        if (handle == 0L) return
        nativeSendMouseButton(handle, button, pressed, buttonMask)
    }

    /** scancode: PC XT scancode خام (نفس ترميز SPICE_KEYBOARD_XT). */
    fun sendKey(scancode: Int, pressed: Boolean) {
        if (handle == 0L) return
        nativeSendKey(handle, scancode, pressed)
    }

    fun disconnect() {
        if (handle != 0L) nativeDisconnect(handle)
    }

    fun release() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0
        }
    }

    /**
     * يُستدعى من خيط GLib الداخلي عند كل سطح أساسي جديد من Display
     * channel — x/y/w/h مستطيل التحديث، pixels مصفوفة ARGB_8888-compatible
     * خام (raw memcpy، لا تحويل لكل بكسل)، fullFrame=true دائماً حالياً.
     * الفئات الفرعية (Part 4/N) تُعيد كتابة هذه الدالة لرسم pixels إلى
     * Bitmap/Surface فعلي؛ التطبيق الافتراضي هنا فارغ عمداً حتى لا ينهار
     * JNI إن لم تُطبَّق بعد.
     */
    open fun onNativeFrame(x: Int, y: Int, w: Int, h: Int, pixels: IntArray, fullFrame: Boolean) {
        // يُطبَّق فعلياً في Part 4/N (SpiceSessionClient).
    }

    private external fun nativeInit(): Long
    private external fun nativeConnect(handle: Long, host: String, port: Int, password: String?): Boolean
    private external fun nativeSendMouse(handle: Long, x: Int, y: Int, buttonMask: Int)
    private external fun nativeSendMouseButton(handle: Long, button: Int, pressed: Boolean, buttonMask: Int)
    private external fun nativeSendKey(handle: Long, scancode: Int, pressed: Boolean)
    private external fun nativeDisconnect(handle: Long)
    private external fun nativeFree(handle: Long)
}
