# إعداد aFreeRDP (كيف يُبنى جسر RDP الأصلي فعليًا)

> **DOC FIX:** كانت نسخة سابقة من هذا الملف تصف طريقة بناء غير متوافقة إطلاقًا
> مع ما يقرأه `CMakeLists.txt` فعليًا (خطوة "استنساخ FreeRDP كـ git submodule
> داخل `app/src/main/cpp/FreeRDP`" ثم "تفعيل `externalNativeBuild` عبر تعليق
> `// ENABLE_NATIVE_BUILD`"). لا وجود لذلك التعليق في `app/build.gradle.kts`
> (البناء الأصلي مفعّل دائمًا، بلا أي toggle)، و`CMakeLists.txt` **لا يبحث
> إطلاقًا** عن مجلد `FreeRDP/` هذا — بل عن مكتبات ثنائية جاهزة (prebuilt) في
> مسار مختلف تمامًا. اتباع الدليل القديم حرفيًا لم يكن ليُنتج بناءً ناجحًا.
> هذا الملف الآن يطابق الاستراتيجية الفعلية المطبَّقة في الكود
> (`CMakeLists.txt`, `app/build.gradle.kts`, `.github/workflows/main.yml`).

## الاستراتيجية الفعلية: prebuilt libraries، وليس بناء من المصدر داخل Gradle

`RdpRemoteAdapter.kt` يعتمد حصريًا على `AFreeRdpBridge` (الجسر JNI في
`systemsgo_jni.c`)؛ **لا يوجد محرك RDP احتياطي مكتوب بلغة Kotlin** — أُزيل نهائيًا.
لذلك، بدون مكتبات FreeRDP/OpenSSL الأصلية، `AFreeRdpBridge.isAvailable`
يكون `false` وكل اتصالات RDP تفشل فورًا (VNC وSSH غير متأثرين — كلاهما
بلا حاجة لأي بناء أصلي).

`CMakeLists.txt` **لا يبني FreeRDP من المصدر أبدًا** داخل Gradle. بدلًا من
ذلك، يبحث فقط عن مكتبات جاهزة مسبقة البناء (prebuilt) هنا:

```
app/src/main/cpp/freerdp-prebuilt/<ABI>/lib/libfreerdp3.so   (و libwinpr3.so, libfreerdp-client3.so)
app/src/main/cpp/freerdp-prebuilt/<ABI>/include/freerdp3/...
app/src/main/cpp/freerdp-prebuilt/<ABI>/include/winpr3/...
```

إن لم توجد هذه الملفات لمعمارية (ABI) معيّنة، تتم طباعة رسالة في سجلّ
تكوين CMake ويُتخطّى بناء الجسر الأصلي لتلك المعمارية بصمت (لا يتعطل باقي
المشروع) — لكن RDP يبقى معطّلًا فعليًا لتلك المعمارية حتى تُوفَّر المكتبات.

## الخيار 1 (الموصى به): دع CI يفعل كل شيء تلقائيًا

`.github/workflows/main.yml` يقوم بكامل السلسلة تلقائيًا عند كل push/PR أو
تشغيل يدوي (workflow_dispatch): يسحب مصدر FreeRDP (بالتاغ المحدد في
`env.FREERDP_TAG`)، يبني OpenSSL لكل ABI في `env.BUILD_ABIS`، يبني FreeRDP
كـ prebuilt لكل ABI عبر `cmake --install` مباشرة (وليس عبر Gradle/NDK
add_subdirectory)، ثم يضع الناتج بالضبط في المسارين أعلاه قبل تشغيل
`./gradlew assembleDebug`. لا حاجة لأي إعداد يدوي — فقط شغّل الـ workflow
(أو أضف push على أحد الفروع المذكورة في `on:`) واسحب الـ APK كـ artifact.

## الخيار 2: بناء محلي يدوي (بدون CI)

إن أردت بناء المكتبات على جهازك بنفس الطريقة تمامًا التي يستخدمها CI (راجع
`.github/workflows/main.yml` للأوامر الدقيقة والأعلام/flags):

1. **بناء OpenSSL** لكل ABI تستهدفه (راجع `abiFilters` في
   `app/build.gradle.kts`، افتراضيًا `arm64-v8a`, `armeabi-v7a`, `x86_64`,
   `x86`) باستخدام Android NDK، وثبّت الناتج بحيث يكون لديك، لكل ABI:
   ```
   <OPENSSL_ROOT>/openssl-<ABI>/lib/libssl.a
   <OPENSSL_ROOT>/openssl-<ABI>/lib/libcrypto.a
   <OPENSSL_ROOT>/openssl-<ABI>/include/...
   ```
2. **استنساخ وبناء FreeRDP** (التاغ المحدد في `FREERDP_TAG` بملف الـ CI)
   عبر CMake مباشرة مع `-DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake`
   و`-DANDROID_ABI=<ABI>` و`-DOPENSSL_ROOT_DIR=<OPENSSL_ROOT>/openssl-<ABI>`
   (طالع القائمة الكاملة لأعلام CMake — `WITH_CLIENT=ON`, `WITH_SERVER=OFF`,
   إلخ — في خطوة "Build FreeRDP prebuilt" داخل `main.yml`)، ثم
   `cmake --install` الناتج إلى:
   ```
   app/src/main/cpp/freerdp-prebuilt/<ABI>/lib/
   app/src/main/cpp/freerdp-prebuilt/<ABI>/include/
   ```
3. **مرّر متغير البيئة** قبل تشغيل Gradle، بحيث يشير إلى المجلد الأب الذي
   يحتوي مجلدات `openssl-<ABI>/` من الخطوة 1:
   ```bash
   export ANDROID_OPENSSL_ROOT=/path/to/<OPENSSL_ROOT>
   ./gradlew assembleDebug
   ```
   (`app/build.gradle.kts` يمرر هذا المسار إلى CMake عبر
   `-DANDROID_OPENSSL_ROOT=...`؛ و`CMakeLists.txt` يحتسب منه تلقائيًا
   `${ANDROID_OPENSSL_ROOT}/openssl-${ANDROID_ABI}` لكل معمارية.)

أول بناء لـ FreeRDP نفسه قد يستغرق 20-60 دقيقة حسب جهازك؛ البناءات
اللاحقة أسرع بفضل الـ cache (إن استخدمت CI) أو لأنك لن تعيد بناء
المكتبات الأصلية إن لم تتغيّر.

## ماذا لو لم أفعل أيًا من هذين الخيارين؟

بناء التطبيق نفسه لن يتعطل — `CMakeLists.txt` يتحقق من وجود المكتبات
الجاهزة قبل محاولة الربط بها؛ إن لم يجدها يطبع رسالة ويتخطى الجسر الأصلي
لتلك المعمارية فقط، ويستمر باقي المشروع بالبناء بنجاح. لكن، وظيفيًا:

- **RDP**: `AFreeRdpBridge.isAvailable` سيكون `false`، وكل محاولات اتصال
  RDP ستفشل فورًا برسالة خطأ توجّه المستخدم لهذا الملف — RDP لن يعمل بدون
  أحد الخيارين أعلاه، فعليًا لكل ABI يستهدفه جهاز المستخدم.
- **VNC**: يعمل بشكل مستقل تمامًا، بلا أي علاقة ببناء FreeRDP —
  `com.undatech.opaque.RfbConnectable` عميل RFB حقيقي مكتوب بالكامل
  بلغة Kotlin (مصادقة DES، ترميزات Raw/CopyRect/ZRLE)، لا يحتاج أي مكتبة
  أصلية إطلاقًا.
- **SSH**: يعمل دائمًا بشكل طبيعي (مكتبة JSch خالصة بلغة Java/Kotlin، بلا
  حاجة لأي بناء أصلي).

## قيود مقصودة في أعلام بناء FreeRDP (وليست نسيانًا)

> **تحديث توثيق (SMARTCARD-REDIRECT FEATURE):** الفقرة الخاصة بـ `WITH_PCSC`
> أدناه أصبحت غير مطابقة للكود الحالي — راجع قسم "SMARTCARD-REDIRECT
> FEATURE" في آخر هذا الملف للوضع الفعلي المحدَّث (`WITH_PCSC=ON` مشروط
> بنجاح بناء PCSC-lite لكل ABI، وليس `OFF` ثابتة كما تصفه الفقرة القديمة
> أدناه). أُبقيت الفقرة القديمة دون حذف لتوثيق القرار الأصلي وسياقه.

خطوة "Build FreeRDP prebuilt" تبني FreeRDP 3.24.2 بـ `WITH_CUPS=OFF`
و`WITH_PCSC=OFF` صراحةً (**كانت** — راجع تنويه SMARTCARD-REDIRECT FEATURE
أعلاه لحالة `WITH_PCSC` الفعلية الآن)، إلى جانب `WITH_PULSE=OFF`, `WITH_X11=OFF`,
`WITH_WAYLAND=OFF`, `WITH_FFMPEG=OFF` وغيرها — أي أن القناتين ليستا
الحالة الوحيدة المستبعدة، بل جزء من تعطيل ممنهج لكل ما هو خاص بلينكس/
ديسكتوب لا معنى له على Android. هذا قرار مقصود لا إغفال:

- **`WITH_CUPS` (إعادة توجيه الطابعة)**: CUPS مكتبة طباعة لينكس/ديسكتوب
  ولا تُبنى عادة لـ Android من الأساس؛ تفعيلها هنا كان سيتطلب توفير مكتبة
  CUPS الأصلية نفسها مبنية لكل ABI (أربع معماريات إضافية للحزمة النهائية)
  لقناة لا استخدام واقعي لها على جهاز محمول بلا طابعات محلية.
- **`WITH_PCSC` (إعادة توجيه البطاقة الذكية / smart card)**: PC/SC على
  أندرويد ليس مجرد علم بناء — يحتاج فعليًا خدمة PCSC-lite تعمل بصلاحيات
  النظام أو جسر OMAPI/NFC خاص بالجهاز، وهذا غير متوفر في بيئة تطبيق عادي
  بلا صلاحيات جذر أو دعم مورّد الجهاز. **(كان الوضع؛ راجع قسم
  SMARTCARD-REDIRECT FEATURE في آخر الملف لما تغيّر ولما لم يتغيّر بعد.)**

**التأثير على المستخدم المؤسسي (طباعة فقط الآن)**: أي جلسة تحتاج طباعة
مستند من التطبيق المنشور مباشرة إلى طابعة محلية عبر إعادة التوجيه **لن
تعمل** بهذا البناء طالما `WITH_CUPS=OFF` — ليس لأن `systemsgo_jni.c` ينقصه
كود، بل لأن مكتبة FreeRDP الثنائية نفسها المبنية في CI لا تحتوي القناة.
(الفقرة القديمة هنا كانت تذكر تسجيل الدخول ببطاقة ذكية أيضًا كأثر معطّل —
ذلك الجزء تحديدًا معالَج الآن جزئيًا، راجع القسم الجديد.) تفعيل الطباعة
لاحقًا يتطلب طبقتين لا واحدة:
1. تغيير `-DWITH_CUPS=OFF` إلى `ON` في **كلا** موضعي خطوة `Build FreeRDP
   prebuilt` بـ `main.yml` (job `build` و job `release` — الأعلام مكرَّرة
   يدويًا في الملف، لا مصدر واحد مشترك، فتعديل أحدهما فقط يترك الآخر على
   `OFF` بصمت)، مع توفير أي مكتبات أصلية إضافية قد تتطلبها، ثم مسح مفتاحي
   الـ cache الحاليين (`openssl-...` و `freerdp-prebuilt-...` في
   `env.BUILD_ABIS`) لأن أي منهما لن يُعاد بناؤه تلقائيًا لمجرد تغيير علم
   CMake — مفتاح الـ cache لا يتضمن قائمة أعلام WITH_* أصلاً.
2. تسجيل قناة `rdpdr` لجزء الطابعة داخل `systemsgo_jni.c` — وهو نفس النوع من
   الفجوة المذكور أعلاه بخصوص قناة `rail`.

- `WITH_CLIPBOARD_REDIR`، `WITH_AUDIO`، الخ — أعلام بناء FreeRDP إضافية
  يُفعَّلها عند بناء FreeRDP نفسه في CI أو محليًا (خطوة "Build FreeRDP
  prebuilt")، وليس في `app/src/main/cpp/CMakeLists.txt` (الذي يربط
  المكتبات الجاهزة فقط ولا يعيد بناء FreeRDP).
- إضافة قنوات (channels) كالحافظة (clipboard) ومشاركة الأجهزة (drive
  redirection) تتطلب توسيع `systemsgo_jni.c` لتسجيل الـ callbacks الخاصة بها.


## LIVE-RESIZE FIX: قناة `disp` (Display Control / MS-RDPEDISP)

على عكس `rail`/`rdpdr`/`smartcard` أعلاه، قناة `disp` **مُفعَّلة افتراضيًا**
في بناء FreeRDP القياسي (لا تحتاج علم `-DWITH_CHANNEL_DISP=ON` إضافي في
`main.yml`، لأنها ليست معزولة خلف علم اختياري كـ `WITH_CUPS`/`WITH_PCSC`
أعلاه) — لذلك تسجيلها في `systemsgo_jni.c` (`FreeRDP_SupportDisplayControl` +
`FreeRDP_DynamicResolutionUpdate` + `freerdp_client_load_addins` في
`systemsgo_pre_connect`، مع `systemsgo_on_channel_connected`/`_disconnected` لالتقاط
`DispClientContext` عند فتح القناة) يجب أن يعمل مع أي prebuilt موجود بالفعل،
بدون إعادة بناء FreeRDP. إن لم يدعم السيرفر البعيد RDPEDISP، تبقى القناة غير
متصلة ببساطة (`hctx->dispContext == NULL`) و`nativeResize()` تعيد `false` بلا
أي خطأ — الجلسة تستمر بدقتها الحالية كأن الميزة غير موجودة.

## REAL-PCM FIX: تشغيل الصوت الفعلي (rdpsnd/audin) عبر OpenSL ES — **مُطبَّق**

الحل الحقيقي لعدم سماع صوت فعلي **لم يكن** كتابة طبقة PCM مخصّصة داخل
`systemsgo_jni.c` تتحايل على FreeRDP — بل تفعيل الـ backend الصوتي الذي
توفّره FreeRDP نفسها لأندرويد أصلاً:

- `channels/rdpsnd/client/opensles/` (تشغيل/playback)
- `channels/audin/client/opensles/` (تسجيل المايك/capture)

كلاهما مبني على OpenSL ES، وهي واجهة NDK ثابتة موجودة على كل جهاز أندرويد
وكل إصدار API — لا تحتاج مكتبة خارجية أو صلاحية خاصة (المايك يحتاج
`RECORD_AUDIO` فقط، وهي مُعلنة بالفعل في `AndroidManifest.xml`).

**السبب أن الصوت لم يكن يعمل**: خطوة "Build FreeRDP prebuilt" في
`.github/workflows/main.yml` لم تكن تمرر `-DWITH_OPENSLES=ON` عند بناء
FreeRDP — فكانت تُبنى بلا أي backend صوتي لأندرويد إطلاقًا، فتختار
`rdpsnd_process_connect()` داخليًا خيار "fake" (لا تشغيل فعلي) بصمت، بغض
النظر عمّا يفعله `systemsgo_jni.c`.

**ما تم تطبيقه فعليًا (هذا الدمج):**

1. `.github/workflows/main.yml`: أُضيف `-DWITH_OPENSLES=ON` بجانب
   `-DWITH_PULSE=OFF` في **كلا** موضعي خطوة `Build FreeRDP prebuilt` (job
   `build` و job `release`).
2. نفس الملف: أُضيف اللاحق `-opensles-v1` لمفتاح cache
   `freerdp-prebuilt-...` في كلا الموضعين — تغيير علم CMake وحده لا يُبطل
   الـ cache تلقائيًا (مفتاحه لا يتضمن قائمة أعلام `WITH_*`)، فبدون هذا
   اللاحق كان سيُستعاد cache قديم بلا صوت رغم التعديل.
3. `CMakeLists.txt`: القيمة الافتراضية لخيار `SYSTEMSGO_AUDIO_BACKEND_AVAILABLE`
   غُيِّرت من `OFF` إلى `ON` لتطابق واقع الـ prebuilt الجديد، فيظهر مفتاح
   "تفعيل الصوت البعيد" في الواجهة كمتاح فورًا.

بعد أول تشغيل لهذا الـ workflow المُحدَّث (سيعيد بناء FreeRDP من الصفر
بسبب تغيّر مفتاح الـ cache — قد يستغرق وقتًا أطول من المعتاد لهذه المرة
فقط)، **لا حاجة لأي تعديل إضافي في `systemsgo_jni.c`**: بمجرد أن يفتح
السيرفر قناة `rdpsnd`، تختار FreeRDP نفسها backend "opensles" تلقائيًا
(هو الخيار الأول المجرَّب في قائمتها الداخلية، قبل pulse/alsa/oss/fake)
وتُشغَّل عيّنات الـ PCM القادمة من السيرفر مباشرة عبر OpenSL ES داخل
`libfreerdp-client3.so` نفسها — دون المرور بـ `onNativeAudioFrame`/
`RemoteAudioManager` إطلاقًا (تلك المسارات تبقى مفيدة فقط لعرض حالة
الاتصال في الواجهة). قناة `audin` (المايك) تعمل بنفس الطريقة تمامًا:
الـ backend يسجّل من المايك بنفسه، فـ `nativeSendAudioCapture`/
`sendAudioCapture` لا يحتاجان أي تغيير أيضًا.

## PRINTER-REDIRECT FEATURE: إعادة توجيه الطابعة (MS-RDPEPC)

كانت الفجوة سابقًا `WITH_CUPS=OFF` صريحة في `main.yml`. **PRINTER-CUPS FIX**
(انظر تعليق أعلى `main.yml`) أضاف خطوة "Build CUPS prebuilt" تبني `libcups`
(مكتبة العميل HTTP/IPP فقط من `cups/` داخل مصدر OpenPrinting/cups، وليس
`cupsd`/backends/filters المرتبطة بـ PAM/systemd) لكل ABI عبر autotools
cross-compile، ثم تمرّر `-DWITH_CUPS=ON` مع `CUPS_INCLUDE_DIR`/`CUPS_LIBRARIES`
لخطوة "Build FreeRDP prebuilt". هذا القسم يوثّق أين تقف الميزة الآن بعد هذا
التعديل:

1. **مفتاح "إعادة توجيه الطابعات"** (`Components.kt` → `ProtocolOptionsSection`،
   يقابل `RdpProfile.enablePrinterRedirect`): يعتمد على
   `AFreeRdpBridge.isPrinterBackendAvailable`، الذي يقرأ
   `SYSTEMSGO_PRINT_BACKEND_AVAILABLE` من `CMakeLists.txt` — انقلبت قيمته
   الافتراضية إلى `ON` مع هذا التعديل (كان `OFF`)، فيظهر المفتاح *مفعّلاً*
   الآن بدل "غير مدعوم" (نفس نمط "تفعيل الصوت عن بُعد" بعد REAL-PCM FIX).
2. **`systemsgo_jni.c`'s `nativeConnect`**: يسجّل جهاز طابعة عبر
   `freerdp_client_add_device_channel(settings, 3, {"printer", "Android
   (Redirected)", ""})` عند `jEnablePrinterRedirect` — هذا الكود لم يتغيّر،
   فقط علم `SYSTEMSGO_PRINT_BACKEND_AVAILABLE` المحيط به انقلب إلى مفعّل.
3. **`com.systemsgo.hex.print.RemotePrintManager`**: بلا تغيير، جاهزة كما
   كانت لاستهلاك بيانات الطباعة عبر `android.print.PrintManager`.

> ⚠️ **تحذير مهم — هذا تجريبي وغير مُختبَر على تشغيل CI حقيقي**: autotools
> الخاص بـ CUPS لم يُصمَّم أو يُختبَر رسميًا للـ cross-compile نحو Android
> (على عكس `WITH_OPENSLES` وهو واجهة NDK ثابتة معتمدة رسميًا من FreeRDP نفسها
> لأندرويد). خطوة "Build CUPS prebuilt" مصمَّمة لتتحمّل الفشل: إن تعذّر بناء
> `libcups` لـ ABI معيّن (مشاكل autoconf عند فحوصات AC_TRY_RUN التي لا يمكن
> تشغيلها أثناء cross-compiling هي الاحتمال الأكبر)، تلك الـ ABI فقط تسقط إلى
> `WITH_CUPS=OFF` صامتًا (رسالة تحذير في سجلّ الخطوة) بدل تعطيل الـ workflow
> كله — لكن معنى ذلك عمليًا أن `isPrinterBackendAvailable` قد يكون `true`
> على مستوى التطبيق بينما لا تعمل الطباعة فعليًا إلا على بعض الـ ABIs. **راجع
> سجلّ خطوة "Build CUPS prebuilt" لكل ABI في تشغيل الـ workflow الفعلي للتأكد
> من نجاحها فعليًا** قبل الاعتماد على هذه الميزة في أي إصدار.
>
> إن فشلت كل الـ ABIs (فشل CUPS كليًا)، الحل الأبسط هو الرجوع لما كان عليه
> الوضع سابقًا: أعد `-DWITH_CUPS=OFF` (بلا شرط `if`) في كلا موضعي خطوة "Build
> FreeRDP prebuilt" بـ `main.yml`، وأعد `SYSTEMSGO_PRINT_BACKEND_AVAILABLE` إلى
> `OFF` في `CMakeLists.txt`.

## WEBCAM-REDIRECT FEATURE: إعادة توجيه الكاميرا (MS-RDPECAM)

كانت الفجوة سابقًا أن `FREERDP_TAG` المثبَّت (3.24.2) أُصدر قبل أن يضيف
FreeRDP أي backend فعلي لقناة `rdpecam` على أندرويد إطلاقًا — راجع تعليق
`systemsgo_jni.c` القديم "No equivalent block exists for a camera/webcam
toggle" (لم يعد صحيحًا بعد هذا التعديل). على عكس الطابعة (CUPS، مكتبة
desktop/Linux تحتاج cross-compile كاملة لأندرويد)، الكاميرا لا تحتاج أي
مكتبة أصلية خارجية: PR أعلى المصدر
([#12894](https://github.com/FreeRDP/FreeRDP/pull/12894),
"[client,rdpecam,android] Add camera redirection support") أضاف backend
مكتوب مباشرة فوق Camera2 NDK، ونُشر أول مرة في FreeRDP 3.27.1 (سجل
الإصدار: "Android client RDPECAM support").

**ما تم تطبيقه فعليًا (هذا التعديل):**

1. `.github/workflows/main.yml`: رُفع `FREERDP_TAG` من `3.24.2` إلى
   `3.27.1` (أول تاغ فيه دعم rdpecam لأندرويد فعليًا)، وأُضيف
   `-DCHANNEL_RDPECAM_CLIENT=ON` بجانب `-DWITH_OPENSLES=ON` في خطوة "Build
   FreeRDP prebuilt" — في **كلا** موضعي الخطوة (job `build` و job
   `release`، نفس التكرار اليدوي الموثَّق أعلاه لـ `WITH_CUPS`/
   `WITH_PCSC`). أُضيفت أيضًا لاحقة `-rdpecam-v1` لمفتاح cache
   `freerdp-prebuilt-...` (بجانب `-opensles-v1` الموجودة) — رفع
   `FREERDP_TAG` وحده يُبطل الـ cache تلقائيًا، لكن اللاحقة تمنع أي التباس
   لو أُعيد التاغ لاحقًا لأي سبب.
2. `CMakeLists.txt`: قيمة `SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE` الافتراضية
   `ON` (نفس نمط `SYSTEMSGO_AUDIO_BACKEND_AVAILABLE`/
   `SYSTEMSGO_PRINT_BACKEND_AVAILABLE`).
3. `systemsgo_jni.c`: `nativeConnect` يسجّل قناة ديناميكية (وليس جهاز `rdpdr`
   كالطابعة/القرص) عبر
   `freerdp_client_add_dynamic_channel(settings, 1, {"rdpecam"})` عند
   `jEnableWebcamRedirect` و`SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE` — نفس الآلية
   التي يستخدمها `xfreerdp` نفسه خلف خيار سطر الأوامر `/dvc:rdpecam`.
   `nativeIsWebcamBackendAvailable()` جديدة أيضًا، تعكس نفس العلم للواجهة.
4. الطبقة الكوتلينية: `AFreeRdpBridge.isWebcamBackendAvailable` +
   `enableWebcamRedirect` param في `connect()`، `RdpProfile.enableWebcamRedirect`
   (عمود Room جديد عبر `MIGRATION_17_18`)، `RdpCredentials.enableWebcamRedirect`
   (نفس نمط نسخ enablePrinterRedirect في `RemoteSessionFactory`)، مفتاح
   "إعادة توجيه الكاميرا" في `Components.kt` → `ProtocolOptionsSection`،
   وقراءة `camerastoredirect` (مفتاح .rdp القياسي لـ mstsc) في
   `RdpFileParser`. صلاحية `CAMERA` مطلوبة بالفعل في
   `AndroidManifest.xml`؛ أُضيف طلبها وقت التشغيل في
   `RdpSessionActivity.onCreate`/`SplitScreenActivity.onCreate` (نفس نمط
   `RECORD_AUDIO` الموجود لمايكروفون `audin`).

> ⚠️ **تحذير مهم — غير مُختبَر على تشغيل CI حقيقي**: لا توجد بيئة شبكة في
> البيئة التي أُعدَّ فيها هذا التعديل للتحقق من نجاح تجميع
> `channels/rdpecam/client` فعليًا ضد NDK r27d لكل ABI (الميزة أُضيفت
> لأعلى المصدر قبل أسابيع فقط من هذا التعديل، وليست بنفس نضج
> `WITH_OPENSLES`). خطوة "Build FreeRDP prebuilt" لا تفشل الـ workflow
> كله إن تعذّر تجميع القناة لـ ABI معيّن (نفس نمط تحمّل الفشل الموثَّق أعلاه
> لـ CUPS) — لكن هذا يعني عمليًا أن `isWebcamBackendAvailable` قد يكون
> `true` على مستوى التطبيق بينما لا تعمل الكاميرا فعليًا إلا على بعض
> الـ ABIs. **راجع سجلّ خطوة "Build FreeRDP prebuilt" لكل ABI في تشغيل
> الـ workflow الفعلي للتأكد من نجاحها** قبل الاعتماد على هذه الميزة في
> أي إصدار.
>
> إن فشل التجميع كليًا، الحل الأبسط هو حذف `-DCHANNEL_RDPECAM_CLIENT=ON`
> من كلا موضعي خطوة "Build FreeRDP prebuilt" في `main.yml`، وإعادة
> `SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE` إلى `OFF` في `CMakeLists.txt` — نفس
> مسار التراجع الموثَّق أعلاه لـ CUPS. رفع `FREERDP_TAG` نفسه إلى 3.27.1
> آمن الإبقاء عليه بشكل مستقل (لا علاقة له بنجاح/فشل rdpecam تحديدًا)
> ما لم يظهر خلاف ذلك في سجلّ CI.

## SMARTCARD-REDIRECT FEATURE: إعادة توجيه البطاقة الذكية (MS-RDPESC)

كانت الفجوة سابقًا `WITH_PCSC=OFF` صريحة في `main.yml`، مع ملاحظة أن PC/SC
على أندرويد "ليس مجرد علم بناء" لأنه يحتاج خدمة PCSC-lite تعمل كموارد نظام
(resource manager). هذا القسم يوثّق ما تغيّر فعليًا بهذا التعديل وما **لم**
يتغيّر — القيد الحقيقي (لا مجرد علم بناء) لا يزال قائمًا جزئيًا، على عكس
CUPS/rdpecam أعلاه حيث أُغلقت الفجوة بالكامل.

**ما تم تطبيقه فعليًا (هذا التعديل):**

1. `.github/workflows/main.yml`: أُضيفت خطوتا "Restore/Build PCSC-lite
   prebuilt" (job `build` و job `release`، نفس نمط "Build CUPS prebuilt"
   الموثَّق أعلاه) تستنسخان `LudovicRousseau/PCSC` بالتاغ `env.PCSC_LITE_VERSION`
   وتبنيان `libpcsclite.a` لكل ABI عبر autotools cross-compile، بـ
   `--disable-libudev`/`--disable-usb`/`--disable-serial` (لا udev على
   أندرويد؛ اكتشاف القارئ يبقى مسؤولية جسر USB-CCID لم يُنفَّذ بعد داخل
   التطبيق نفسه — راجع نقطة 3 أدناه). خطوة "Build FreeRDP prebuilt" تفحص
   الآن، **لكل ABI على حدة**، وجود `libpcsclite.a` الناتج قبل تقرير
   `-DWITH_PCSC=ON` أو `-DWITH_PCSC=OFF` لتلك المعمارية تحديدًا (نفس نمط
   تحمّل الفشل الموثَّق أعلاه لـ CUPS: فشل PCSC-lite في ABI واحد لا يُسقط
   الـ workflow كله، فقط تلك المعمارية تبقى بلا دعم بطاقة ذكية). أُضيفت
   لاحقة `-pcsc-v1` لمفتاح cache `freerdp-prebuilt-...` (بجانب
   `-opensles-v1`/`-rdpecam-v1` الموجودتين).
2. `CMakeLists.txt`: خيار `SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE` جديد،
   افتراضيًا `ON` (نفس نمط `SYSTEMSGO_PRINT_BACKEND_AVAILABLE`/
   `SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE`)، يُمرَّر إلى `systemsgo_jni.c` كـ macro.
3. `systemsgo_jni.c`: `nativeConnect` يسجّل جهاز `smartcard` على قناة `rdpdr`
   الثابتة (نفس آلية `printer`/`drive`، وليس قناة ديناميكية كـ `rdpecam`)
   عبر `freerdp_client_add_device_channel(settings, 2, {"smartcard", "Android
   Smart Card"})` عند `jEnableSmartcardRedirect` و
   `SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE` — نفس الآلية التي يستخدمها
   `xfreerdp` خلف خيار سطر الأوامر `/smartcard`. يضبط أيضًا
   `FreeRDP_RedirectSmartCards` بجانب `FreeRDP_DeviceRedirection`.
   `nativeIsSmartcardBackendAvailable()` جديدة أيضًا، تعكس نفس العلم للواجهة.
4. الطبقة الكوتلينية: `AFreeRdpBridge.isSmartcardBackendAvailable` +
   `enableSmartcardRedirect` param في `connect()`،
   `RdpProfile.enableSmartcardRedirect` (عمود Room جديد عبر
   `MIGRATION_19_20`)، `RdpCredentials.enableSmartcardRedirect` (نفس نمط
   نسخ enableWebcamRedirect في `RemoteSessionFactory`/`RdpRemoteAdapter`)،
   مفتاح "إعادة توجيه البطاقة الذكية" في `Components.kt` →
   `ProtocolOptionsSection`، وقراءة `redirectsmartcards` (مفتاح .rdp
   القياسي لـ mstsc) في `RdpFileParser`.

> ⚠️ **القيد الأهم — لا يزال قائمًا حتى مع `SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE=1`:**
> ربط `libpcsclite` وتسجيل جهاز `smartcard` على `rdpdr` يعنيان أن FreeRDP
> *تحاول* فتح جلسة PC/SC، لكن `libpcsclite` نفسها مجرد مكتبة *عميل* — تحتاج
> فعليًا خدمة *resource manager* تجيب على استعلاماتها (طبيعيًا `pcscd`، وهو
> daemon نظامي لا يعمل داخل sandbox تطبيق أندرويد عادي بلا صلاحيات جذر).
> بدون ذلك، القناة تُفتح لكن تُبلّغ "لا قارئ متاح" حتى لو كان قارئ USB-CCID
> فعليًا موصولًا بالجهاز — نفس ما ورد في الملاحظة الأصلية عن PC/SC. إغلاق
> هذه الفجوة فعليًا يحتاج أحد مسارين لم يُنفَّذا بعد في هذا التعديل:
> (أ) تضمين نسخة مبسّطة من `pcscd` نفسه (أو معادل خفيف) يعمل داخل عملية
> التطبيق ويُدير قارئ USB-CCID عبر `android.hardware.usb` مباشرة، أو
> (ب) جسر OMAPI/NFC خاص بمورّد الجهاز لقارئات NFC/eSE المدمجة. حتى ذلك
> الحين، اعتبر تفعيل هذا المفتاح "جاهزية القناة" فقط — ضروري لكن غير كافٍ
> لتسجيل دخول فعلي ببطاقة PIV/CAC. هذا فرق جوهري عن CUPS/rdpecam أعلاه، حيث
> إغلاق فجوة البناء كان كافيًا وحده (لا حاجة لخدمة نظام موازية تعمل باستمرار).
>
> ⚠️ **تجريبي أيضًا من ناحية البناء نفسه** (نفس تحذير CUPS): autotools
> الخاص بـ PCSC-lite لم يُصمَّم أو يُختبَر رسميًا للـ cross-compile نحو
> Android. **راجع سجلّ خطوة "Build PCSC-lite prebuilt" لكل ABI في تشغيل
> الـ workflow الفعلي** قبل الاعتماد على `isSmartcardBackendAvailable`.
> إن فشلت كل الـ ABIs، الحل الأبسط هو حذف خطوتي PCSC-lite من `main.yml`،
> إعادة `-DWITH_PCSC=OFF` (بلا شرط) في كلا موضعي "Build FreeRDP prebuilt"،
> وإعادة `SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE` إلى `OFF` في `CMakeLists.txt`.

### تحديث: جسر USB-CCID داخل التطبيق (يُغلق فجوة "لا pcscd على أندرويد")

الفجوة الموصوفة أعلاه ("حتى مع `SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE=1` لا يوجد
`pcscd` على أندرويد") عولجت الآن بمكوّن جديد بدل الاعتماد على PCSC-lite
الحقيقية وقت التشغيل:

- **`app/src/main/java/com/systemsgo/hex/smartcard/UsbCcidReader.kt`**:
  برنامج تشغيل USB-CCID من الصفر فوق `android.hardware.usb` (قارئ واحد،
  فتحة واحدة) — طلب صلاحية USB، فتح الجهاز، وتبادل رسائل CCID
  (`PC_to_RDR_IccPowerOn`/`XfrBlock`...) عبر bulk transfer مباشرة.
- **`PcscUsbBridge.kt`**: الواجهة التي يستدعيها الكود الأصلي عبر JNI
  (`nativeConnect`/`nativeTransmit`/...)، وأيضًا من تقوم بتحميل الشيم
  الأصلي نفسه (`System.loadLibrary("pcsclite")`) من خيط JVM عادي — سبب ذلك
  موثّق بالتفصيل في تعليق أعلى الملف (مشكلة classloader الشهيرة عند
  استدعاء `FindClass` من خيط بلا Java stack frame).
- **`app/src/main/cpp/pcsc_shim/pcsc_shim.c`**: تُبنى كـ `libpcsclite.so`
  (نفس اسم SONAME الحقيقي عمدًا) وتنفّذ دوال WinSCard القياسية
  (`SCardConnect`/`SCardTransmit`/...) مباشرة، بدون أي `pcscd` أو PCSC-lite
  حقيقية وقت التشغيل — كل استدعاء يُحوَّل عبر JNI إلى `PcscUsbBridge`.
- **`CMakeLists.txt`**: هدف `pcsclite_shim` جديد (اسم الملف الناتج
  `libpcsclite.so` عبر `OUTPUT_NAME`)، مستقل تمامًا عن هدف `systemsgo_jni`
  ولا يعتمد على وجود FreeRDP prebuilt.
- **`AndroidManifest.xml`** + **`res/xml/usb_device_filter.xml`**: صلاحية
  `android.hardware.usb.host`، و`intent-filter` على `RdpSessionActivity`
  لالتقاط `USB_DEVICE_ATTACHED` لقارئات CCID فقط (class 0x0B).

### تحديث: تحقّق من الافتراض الأساسي + إصلاح نقطتي ضعف حقيقيتين

راجعنا هذا القسم مقابل مصدر FreeRDP الفعلي على GitHub (مش بس مواصفة PC/SC
العامة) وصلّحنا نقطتين كانتا موثّقتين كـ"غير مؤكَّد":

1. **الافتراض الأساسي (dlopen وقت التشغيل) — تأكَّد من المصدر الحقيقي ✅**
   `winpr/libwinpr/smartcard/smartcard_pcsc.c` يحتوي فعليًا على
   `g_PCSCModule = LoadLibraryA("libpcsclite.so")` — تحميل ديناميكي بنفس
   الاسم غير المرقّم (unversioned) اللي يبنيه هذا الـ shim، مش ربط وقت
   البناء. هذا مؤكَّد من الكود المصدري نفسه، مش تخمين حسب المواصفة.

2. **بروتوكول البطاقة (T=0 مقابل T=1) — تم إصلاحه**
   `SCardConnect`/`SCardReconnect`/`SCardStatus`/`SCardTransmit` في
   `pcsc_shim.c` كانت تُرجع `SCARD_PROTOCOL_T1` ثابتة. الآن
   `UsbCcidReader.parseAtrProtocol()` يحلّل سلسلة TDi الفعلية من الـ ATR
   حسب ISO/IEC 7816-3 §8.2.3 (نفس الخوارزمية القياسية: TD1 فأعلى، النيبل
   العالي يحدد وجود TAi/TBi/TCi/TDi التالي، والنيبل الواطئ لآخر TDi هو T
   الفعلي؛ غياب أي TDi يعني T=0 افتراضيًا حسب المواصفة)، ونتيجته تنعكس عبر
   `PcscUsbBridge.nativeGetProtocol()` إلى كل نقاط `pcsc_shim.c` بدل الرقم
   الثابت. بطاقة T=0-only رح تُبلّغ T0 بشكل صحيح الآن.

3. **`SCardGetStatusChange` — صار Blocking فعليًا بدل Snapshot فوري**
   `UsbCcidReader` صار يفتح (اختياريًا، لو الجهاز يوفّره — القناة اختيارية
   حسب مواصفة CCID) الـ interrupt-IN endpoint ويقرأ رسائل
   `RDR_to_PC_NotifySlotChange` (bMessageType=0x50) على thread منفصل، ويحدّث
   حالة وجود البطاقة لحظيًا بدل الاعتماد فقط على نتيجة آخر `powerOn`.
   `pcsc_shim.c` الآن يستدعي `PcscUsbBridge.nativeWaitForStatusChange` وينتظر
   فعليًا حتى `dwTimeout` (بما فيها `INFINITE`، مقسّمة لدفعات 5 ثواني لتفادي
   مشكلة تحويل `0xFFFFFFFF` لـ `jint` سالب) بدل الرجوع فورًا بلقطة حالية.
   القرّاءات اللي ما عندها interrupt endpoint (الميزة اختيارية بالمواصفة)
   لسا تُرجع بلا تغيير حقيقي، لكنها تنام فعليًا لمدة `dwTimeout` بدل ما ترجع
   فورًا — يعني ما في busy-spin على الجانب الأصلي.

**لسا غير مُختبَر على بناء/جهاز حقيقي** (نفس تحذير كل الأقسام أعلاه) —
تحديدًا: (1) دقة تأطير رسائل CCID في `UsbCcidReader` (بما فيها التحديث
الجديد لقراءة استجابات أطول من بافر واحد، ومسار الـ interrupt endpoint)
لم تُختبَر ضد قارئ فعلي، (2) خوارزمية تحليل الـ ATR جديدة ومنطقية لكنها لم
تُختبَر ضد ATR حقيقي من بطاقة T=0-only، (3) سلوك WinPR الدقيق عند استدعاء
`SCardGetStatusChange` بقيم `dwTimeout` مختلفة (بما فيها `INFINITE`) لم
يُتحقّق منه ضد المصدر الفعلي لمعرفة هل فعلاً يستدعيه بهالنمط. راجع تعليقات
"UNVERIFIED"/"FIXED" داخل `pcsc_shim.c` و`UsbCcidReader.kt` لتفاصيل كل نقطة
قبل الاعتماد على هذا في تسجيل دخول إنتاجي فعلي.

## CODEC-NEGOTIATION FEATURE: تفاوض AV1/H.264 تلقائي عبر RDPGFX

**تحديث (H264-OPENH264 FIX): `SYSTEMSGO_H264_BACKEND_AVAILABLE` أصبح `ON` الآن.**
main.yml (كلا job `build`/`release`) يحتوي خطوة "Build openh264 prebuilt"
جديدة (نفس نمط تحمّل الفشل لكل ABI الموثّق لـ CUPS/PCSC-lite أعلاه) تبني
`libopenh264` لكل معمارية، وخطوة "Build FreeRDP prebuilt" تمرّر
`-DWITH_OPENH264=ON` مع `OPENH264_INCLUDE_DIR`/`OPENH264_LIBRARIES` فقط
للـ ABIs التي أنتجت `libopenh264.a` فعليًا (نفس شرط `WITH_PCSC` أعلاه)،
مع لاحقة `-h264-v1` جديدة في مفتاح cache الخاص بـ FreeRDP prebuilt.

> ⚠️ **غير مُختبَر فعليًا بعد** — هذا التعديل نفسه لم يُشغَّل على CI حقيقي:
> (1) متغيرات الـ Makefile الخاصة ببناء openh264 لأندرويد (`OS=android`,
> `NDKROOT`, `ARCH`, `TARGET`, `NDKLEVEL`) كُتبت من التوثيق العام لمشروع
> openh264 دون إمكانية التحقق منها مقابل مصدر حقيقي (لا وصول شبكة في بيئة
> الكتابة)، و(2) لم يُختبَر تفاوض H.264 فعليًا مع خادم Windows RDP حقيقي.
> راجع سجلّ خطوة "Build openh264 prebuilt" لكل ABI أولاً — إن فشلت كل
> الـ ABIs، الحل الأبسط هو نفس مسار الرجوع الموثّق لـ CUPS: أعد
> `SYSTEMSGO_H264_BACKEND_AVAILABLE` إلى `OFF` في `CMakeLists.txt` وأزل
> `-DWITH_OPENH264=ON` (والخطوة الجديدة) من `main.yml`.

- `SYSTEMSGO_H264_BACKEND_AVAILABLE` (الآن `ON`): يحتاج تأكيد فعلي عبر تشغيل
  CI (نجاح "Build openh264 prebuilt" و"Build FreeRDP prebuilt" لكل ABI)
  ثم اختبار تفاوض حقيقي — سطح تشخيص الجلسة (`negotiatedCodec`، انظر أدناه)
  يفترض أن يعرض "H264"/"AVC444" بدل الافتراضي عند نجاح التفاوض.
- `SYSTEMSGO_AV1_BACKEND_AVAILABLE` (الآن `ON` — **AV1-CODEC-BUILD FEATURE**):
  AV1 دعم *تجريبي* أُضيف في FreeRDP 3.25 (هذا المشروع يبني 3.27.1، الذي
  يتضمنه) — لكن ملاحظات الإصدار الرسمية تقول صراحة إنه "يعمل حاليًا فقط مع
  خوادم مبنية على FreeRDP نفسها"، أي لن يتفاوض مع خادم Windows RDP قياسي.
  **تصحيح توثيق (بعد بحث فعلي، مو تخمين):** الفقرة السابقة هنا كانت تقول
  `-DWITH_AV1=ON` مع "dav1d أو aom" — كلاهما غير دقيق. تحققت من
  `CMakeLists.txt` الحقيقي لمشروع FreeRDP نفسه: يسجّل AV1 عبر
  `find_feature(aom ...)` و`find_feature(yuv ...)` (نفس الآلية العامة
  المستخدمة لكل ميزة اختيارية بالمشروع — `find_feature(Cups ...)` تولّد
  `-DWITH_CUPS`، `find_feature(PCSC ...)` تولّد `-DWITH_PCSC`، إلخ)، فالأعلام
  الصحيحة هي **`-DWITH_AOM=ON`** و**`-DWITH_YUV=ON`** معًا — لا يوجد أصلًا
  علم اسمه `WITH_AV1`. والمكتبة نفسها هي **AOM (AOMedia's libaom)** تحديدًا،
  وليس dav1d (PR #12527 المصدري نفسه، اللي أضاف الميزة، يربط ضد AOM).
  **الآن مُنفَّذ فعليًا بـ `main.yml`:** خطوتان جديدتان ("Build aom
  prebuilt"/"Build libyuv prebuilt") تبنيان `libaom`/`libyuv` من المصدر
  لكل ABI، بنفس نمط تحمّل الفشل الموثّق لـ CUPS/PCSC-lite/openh264. فرق
  جوهري عن تلك الثلاثة: تحقّقتُ من أن `Findaom.cmake`/`Findyuv.cmake` في
  FreeRDP يستخدمان **pkg-config** (لا `XXX_INCLUDE_DIR`/`XXX_LIBRARIES` كما
  تفعل PCSC/openh264/CUPS)، فخطوة "Build FreeRDP prebuilt" توجّه pkg-config
  نفسه إلى ملفات `.pc` المبنية عبر `PKG_CONFIG_LIBDIR` (يستبدل مسار بحث
  pkg-config الافتراضي بالكامل، بدل `PKG_CONFIG_PATH` الذي يضيف إليه — هذا
  يمنع تسرّب أي ملف `aom.pc`/`yuv.pc` مثبَّت على مضيف الـ runner نفسه إلى
  بناء معمارية مختلفة). `aom` نفسها تُنتج `aom.pc` تلقائيًا عبر
  `cmake --install` (تحققتُ من قوائم ملفات حزم Arch/MSYS2 لـ aom). أما
  `libyuv` فليس له `.pc` رسمي إطلاقًا (لا توزيعة تشحن واحداً — تحققتُ من
  قائمة ملفات حزمة Debian's `libyuv-dev`)، فخطوة "Build libyuv prebuilt"
  تكتبه يدويًا (باسمي `yuv.pc` و`libyuv.pc` معًا، لأن FreeRDP نفسه غيّر اسم
  pkgconfig الذي يطلبه في التزام لاحق — "Findyuv: Use correct pkgconfig
  name" — ولم يكن ممكناً التحقق من المحتوى الفعلي لـ `Findyuv.cmake` بهذه
  البيئة، فكتابة الاسمين تحوّط رخيص). ⚠️ **غير مُختبَر فعليًا على CI حقيقي**
  — راجع تعليق `SYSTEMSGO_AV1_BACKEND_AVAILABLE` في `CMakeLists.txt` للتفاصيل
  الكاملة والمخاطر المتبقية (خصوصًا: هل `libyuv` على `LIBYUV_BRANCH` الحالي
  فعلاً يُنتج target باسم `yuv` عبر `CMakeLists.txt` المجتمعي غير الرسمي).
- **تحديث AV1 الآخر — مفتاح الإعدادات صار مؤكَّدًا:** الفقرة السابقة هنا كانت
  تقول إن اسم مفتاح `FreeRDP_Settings_Keys_Bool` غير مؤكَّد وتخمنه كـ
  `"GfxAV1"`. تحققت الآن مباشرة من الالتزام (commit) الحقيقي بمصدر FreeRDP
  الذي أضاف الميزة (PR #12527، الالتزام `6232229`، "[settings,av1] add AV1
  related settings") — رسالة الالتزام نفسها تقول صراحة: **"GfxCodecAV1 to
  enable/disable support"** (وبجانبه `GfxCodecAV1Profile` لضبط الجودة، غير
  مُستخدَم هنا). صُحِّح `systemsgo_apply_codec_preference()` في `systemsgo_jni.c`
  ليستخدم `freerdp_settings_set_value_for_name(settings, "GfxCodecAV1",
  "true")` بدل الاسم القديم الخاطئ. يبقى الضبط بالاسم (مو رمز enum مباشر)
  لنفس السبب المذكور سابقًا — هذا المشروع يستنسخ مصدر FreeRDP بالـ CI فقط
  بدون نسخة محلية للرأس (header) الفعلي — لكن *القيمة* نفسها لم تعد تخمينًا.

راجع `systemsgo_jni.c`'s `SYSTEMSGO_H264_BACKEND_AVAILABLE`/
`SYSTEMSGO_AV1_BACKEND_AVAILABLE` وتعليقات `systemsgo_apply_codec_preference()`،
و`CMakeLists.txt`'s `SYSTEMSGO_H264_BACKEND_AVAILABLE`/
`SYSTEMSGO_AV1_BACKEND_AVAILABLE` options، للتفاصيل الكاملة.

**منفَّذة فعليًا (بخلاف ما كان موثَّقًا سابقًا هنا كـ"مؤجّلة") — تحقّقتُ من
وجودها في الكود، لا تحتاج عملاً إضافيًا في Part 2:**
- **واجهة Advanced Settings في Kotlin**: `CodecPreferenceSection` في
  `Components.kt` (خيارات `CodecPreference.AUTO`/`PREFER_AV1`/
  `PREFER_H264`/`DISABLE_MODERN_CODECS`)، مربوطة بـ
  `AFreeRdpBridge.CodecPreference` عبر `RdpRemoteAdapter`، ومحفوظة في
  `RdpProfile.codecPreference` (قاعدة البيانات عبر `Converters.kt`).
  الخيارات المتاحة تُقيَّد فعليًا بـ `h264BackendAvailable`/
  `av1BackendAvailable` (أي بهذين العلمين في `CMakeLists.txt` أعلاه).
- **كشف قدرات فك التشفير بالعتاد (`MediaCodecList`)**:
  `HardwareDecoderCapabilities.kt` يمسح `MediaCodecList(REGULAR_CODECS)`
  مرة واحدة (نتائج مخزَّنة لعمر الـ process) لمعرفة وجود فك تشفير H.264/AV1
  بالعتاد، ونتيجته تُستخدم في `CodecPreferenceSection` (متغيرات
  `h264HardwareDecoder`/`av1HardwareDecoder`) لتمييز/تفضيل الخيار في
  الواجهة.
- **الإبلاغ عن الكودك الفعلي المتفاوَض عليه**: تدفّق كامل من
  `systemsgo_jni.c` (تعليق بجانب `SurfaceCommand hook`، ليس
  `RdpgfxClientContext::CapsConfirm` — انظر تعليق الملف هناك) عبر
  `AFreeRdpBridge.negotiatedCodec` (`SharedFlow` بـ `replay=1`) →
  `RdpRemoteAdapter._negotiatedCodec`/`RemoteSessionClient.negotiatedCodec`
  → `RdpSessionActivity`'s `viewModel.negotiatedCodec` → شاشة التشخيص
  (تُعرض عبر `negotiatedCodec?.let { ... }`).

**لم يُختبَر فعليًا رغم أن الكود موجود:** لا شيء مما سبق (واجهة الاختيار،
الكشف عن العتاد، الإبلاغ عن التفاوض) شُغِّل فعليًا مقابل قناة RDPGFX حية
مع خادم حقيقي — راجع فقرة "غير مُختبَر فعليًا بعد" أعلى هذا القسم.

**لا يزال مؤجّلاً فعليًا:**
- تكييف البتريت/الجودة تلقائيًا حسب حالة الشبكة استنادًا إلى الكودك
  المتفاوَض عليه (لا علاقة له بالكشف عن العتاد أو الإبلاغ عن الاسم أعلاه،
  وهما مُنفَّذان — هذا تحديدًا هو تعديل البتريت الديناميكي الذي لم يُبنَ
  بعد).


## GENERIC-VCHANNEL FEATURE: واجهة عامة للقنوات الافتراضية + نظام Plugins (Kotlin)

قبل هذه الميزة، كل قناة (disp/cliprdr/rail/rdpsnd/audin/rdpdr/rdpecam/rdpgfx/
rdpei) كانت تحتاج: حقل مخصص في `systemsgoContext`، `if`/`else if` مخصص في
`systemsgo_on_channel_connected`/`disconnected`، جزء مخصص في `nativeConnect`
لتسجيلها، و`jmethodID` + دالة `onNativeXxxChannelState` مخصصة في
`AFreeRdpBridge.kt`. هذا النمط لا يزال قائمًا لكل قناة من هذه (لم يُحذف —
انظر التحذير أدناه)، لكن أُضيفت طبقة عامة موازية فوقها:

1. **إشعار عام بأي قناة**: `systemsgo_notify_channel_lifecycle()` الجديدة في
   `systemsgo_jni.c` تُستدعى من أول سطر في كل من
   `systemsgo_on_channel_connected`/`disconnected`، لأي اسم قناة كان (وليس فقط
   القنوات غير المعروفة) — عبر `jmethodID` جديدين
   (`onChannelConnectedMethod`/`onChannelDisconnectedMethod`) يُستدعيان في
   Kotlin كـ `AFreeRdpBridge.onNativeChannelConnected(name)`/
   `onNativeChannelDisconnected(name)`، ثم يُعاد بثّهما كـ
   `AFreeRdpBridge.channelLifecycle: SharedFlow<ChannelLifecycleEvent>`.
2. **تسجيل قناة إضافية بالاسم دون كود C جديد**: `AFreeRdpBridge.
   registerDynamicChannel(name)` (يستدعي `nativeRegisterDynamicChannel`)
   يضيف الاسم إلى جدول `systemsgoContext::pendingDynamicChannelNames`
   (السعة: `SYSTEMSGO_MAX_PENDING_DYNAMIC_CHANNELS` = 16 اسمًا، كل اسم حتى
   `SYSTEMSGO_DYNAMIC_CHANNEL_NAME_MAX - 1` = 31 حرفًا)، الذي يُستهلك مرة
   واحدة داخل `nativeConnect()` (بعد جزء `rdpecam` مباشرة) عبر نفس آلية
   `freerdp_client_add_dynamic_channel()` المُثبَتة أصلًا لقناة `rdpecam` —
   **وليس آلية جديدة**، فقط جعل الآلية القائمة قابلة للاستدعاء بالاسم دون
   إضافة `if` جديد لكل قناة. يجب استدعاؤه بعد `nativeInit()` وقبل
   `nativeConnect()` (نفس عقد كل أعلام `enableXxx` — يُستهلك مرة واحدة عند
   الاتصال، لا يُبدَّل أثناء الجلسة).
3. **نظام الـ Plugins في Kotlin**: حزمة جديدة
   `com.systemsgo.hex.rdp.channels` تحتوي `RdpChannelPlugin` (واجهة:
   `channelName` + `onChannelConnected()`/`onChannelDisconnected()`) و
   `RdpChannelPluginRegistry` (يسجّل الـ plugins بالاسم، ويوصلها بتدفق
   `channelLifecycle` عبر `wire(bridge, scope)`). هذا هو "Plugin System"
   المطلوب: أي كود جديد يريد التفاعل مع اتصال/انقطاع قناة معيّنة يكتب صنفًا
   يطبّق `RdpChannelPlugin` ويسجّله في `RdpChannelPluginRegistry`، دون لمس
   `systemsgo_jni.c` أو `AFreeRdpBridge.kt` إطلاقًا.

**تحذير مهم — حدود حقيقية لهذه الميزة (وليست نقصًا في التوثيق فقط):**

- **هذه ليست "قناة مخصّصة بروتوكول حر"**: محمّل القنوات الديناميكية في بناء
  FreeRDP الثابت لهذا المشروع (بلا `dlopen` على أندرويد — كل الإضافات
  مُجمَّعة داخل `libfreerdp-client3.so` نفسها) يستطيع فقط فتح أسماء
  إضافات (`addins`) موجودة فعليًا داخل جدول FreeRDP الثابت المُجمَّع في هذا
  البناء بالذات. طلب اسم لا يقابل أي إضافة مبنية فعليًا هو no-op آمن (لا
  يُفتح أبدًا، بلا خطأ) تمامًا كخادم لا يدعم القناة — **وليس** طريقة
  لتعريف بروتوكول بايتات خام جديد يخترعه هذا التطبيق.
- **`freerdp_client_add_dynamic_channel` بالاسم — الآلية الفعلية غير
  مُتحقَّق منها بالتفصيل مقابل مصدر 3.27.1** (نفس تحذير AV1/H264 أعلاه: لا
  وصول شبكة في هذه البيئة المعزولة للتحقق المباشر) — لكن مسار النجاح (اسم
  فعلي مثل `rdpecam`) مبني على نفس الاستدعاء المُختبَر والموثَّق فعليًا في
  هذا الملف لقناة الكاميرا (WEBCAM-REDIRECT FEATURE)، لا استدعاء جديد
  غير مجرَّب.
- **لم يُبنَ ولم يُختبَر في هذه البيئة المعزولة** (بلا NDK/شبكة، انظر أعلى
  هذا الملف) — الكود الجديد يتّبع نفس الأنماط المستخدمة فعليًا في بقية هذا
  الملف حرفيًا، لكنه يحتاج بناء CI فعلي ثم اختبارًا حقيقيًا مقابل جلسة RDP
  حية قبل الاعتماد عليه في إصدار فعلي.

## RDP-SERVER-API FEATURE: استقبال اتصالات RDP واردة (Server API) — المرحلة الأولى

**الهدف:** حتى الآن هذا المشروع عميل RDP فقط (`systemsgo_jni.c` يتصل خارجًا عبر
`libfreerdp-client3.so`). هذه الميزة تبني الاتجاه المعاكس: الجهاز نفسه
يستمع لاتصالات RDP واردة ويتصرف كخادم — الأساس الذي ستُبنى فوقه لاحقًا
Shadow Server (مشاركة شاشة الجهاز الفعلية عبر MediaProjection) ثم RDP
Proxy (الاثنان معًا في نفس العملية كوسيط).

**ما تغيّر:**

1. **`.github/workflows/main.yml`**: خطوة "Build FreeRDP prebuilt" (كلا
   الـ job build وrelease) تمرّر الآن `-DWITH_SERVER=ON` بدل `OFF`، فتُنتج
   نفس الخطوة أيضًا `libfreerdp-server3.so` (واجهة `freerdp_peer`/
   `freerdp_listener` من `include/freerdp/{peer,listener}.h`) جنبًا إلى جنب
   مع `libfreerdp-client3.so` الموجودة أصلًا — يُنسخ تلقائيًا عبر نفس أمر
   `find "$BUILD_DIR" -name "*.so"` القائم، بلا خطوة نسخ إضافية.
   `WITH_SHADOW`/`WITH_PLATFORM_SERVER` بقيا `OFF` عمدًا: يبنيان ملفات
   تنفيذية كاملة (خوادم مرجعية لـ FreeRDP نفسها) لا حاجة لهذا المشروع بها؛
   الملف الجديد يربط مباشرة مع `libfreerdp-server3.so` وينفّذ callbacks
   الخاصة به بنفسه. مفتاح الـ cache (`freerdp-prebuilt-...`) أُضيف له
   لاحقة `-server-v1` كي يُعاد بناء FreeRDP فعليًا بهذا العلم الجديد بدل
   استخدام نسخة مخزَّنة (cached) بُنيت بـ `WITH_SERVER=OFF`.
2. **`app/src/main/cpp/systemsgo_server_jni.c` (جديد)**: مكتبة `.so` منفصلة
   تمامًا عن `systemsgo_jni.c` (لا حالة مشتركة بينهما) — انظر التعليق أعلى
   الملف نفسه للنطاق الكامل. **باختصار المرحلة الأولى هذه:**
   - Listener يستمع على منفذ TCP مُعطى، ويقبل اتصالات عبر
     `freerdp_listener_new`/`PeerAccepted`، بخيط منفصل لكل عميل متصل.
   - تفاوض كامل (X.224/MCS/GCC/Capabilities) تتولاه `libfreerdp-server3`
     داخليًا؛ هذا الملف يزوّدها فقط بـ callbacks
     `Capabilities`/`PostConnect`/`Activate`/`Logon`.
   - أمن: طبقة "Standard RDP Security" القديمة فقط (`RdpSecurity=TRUE`،
     `TlsSecurity`/`NlaSecurity=FALSE`) — الطبقة الوحيدة التي لا تحتاج شهادة
     TLS إطلاقًا (FreeRDP يولّد مفتاح RSA مؤقتًا داخليًا لها). عملاء RDP
     حديثة (`mstsc` الافتراضي) قد ترفض التفاوض إليها حسب سياستها المحلية؛
     `xfreerdp`/عميل هذا المشروع نفسه (`AFreeRdpBridge`) يمكن توجيهه إليها
     صراحة. دعم TLS حقيقي (شهادة فعلية) بند مستقبلي صريح — غير مُنفَّذ بعد.
   - **الإطار المرئي (framebuffer): لون ثابت واحد فقط عند التفعيل
     (Activate)** — وليس شاشة الجهاز الفعلية. هذا مقصود تمامًا: الهدف من
     هذه المرحلة إثبات أن مسار
     الاستماع/القبول/التفاوض/الإدخال (input) يعمل من طرف لنهاية، قبل ربط
     مصدر تصوير حقيقي (Shadow Server، البند التالي في الخارطة).
   - إدخال لوحة المفاتيح والفأرة من العميل المتصل يصل إلى Kotlin عبر
     `AFreeRdpServerBridge.peerKeyEvents`/`peerMouseEvents` (SharedFlow) —
     لا رد فعل فعلي عليه بعد (لا حقن أحداث في نظام أندرويد) — بند مستقبلي
     أيضًا، جزء طبيعي من Shadow Server (تحويل إدخال العميل البعيد إلى
     لمسات/ضغطات فعلية على الجهاز).
   - مصادقة: **تقبل أي عميل متصل بلا فحص فعلي** (`systemsgo_server_peer_logon`
     يعيد `TRUE` دائمًا) — لا شهادة اعتماد للتحقق منها بعد (لا NLA). عاملها
     كاختبار على شبكة محلية موثوقة فقط حتى يُضاف فحص حقيقي (مرتبط بشاشة PIN/
     البصمة الموجودة أصلًا في Security Settings).
3. **`app/src/main/cpp/CMakeLists.txt`**: هدف جديد `systemsgo_server_jni`،
   مبني فقط إن وُجد `libfreerdp-server3.so` فعليًا في الـ prebuilt (نفس
   نمط "skip بأمان إن لم يوجد" المستخدم أصلًا لـ `systemsgo_jni` نفسها) — بناء
   بـ cache قديم (قبل بند 1 أعلاه) لن يكسر شيئًا، فقط يتخطى هذا الهدف بصمت
   حتى يُعاد بناء الـ prebuilt.
4. **`AFreeRdpServerBridge.kt` (جديد،
   `com.systemsgo.hex.rdp.native`)**: صنف مستقل تمامًا عن `AFreeRdpBridge`
   (لا حالة مشتركة) — `start(port, width, height)`/`stop()`/`isRunning`،
   بالإضافة إلى `peerConnected`/`peerDisconnected`/`peerKeyEvents`/
   `peerMouseEvents` كـ `SharedFlow`. **لا واجهة مستخدم بعد** تستدعيه —
   الهدف من هذه المرحلة هو مسار JNI/native نفسه، وربطه بشاشة/زر فعلي في
   التطبيق بند تالٍ (على الأرجح مع Shadow Server مباشرة، حيث تصبح "تفعيل
   استقبال الاتصالات" خطوة ذات معنى فعلي للمستخدم).

**غير مُتحقَّق منه فعليًا (نفس تحذير كل ميزة أخرى في هذا الملف بلا وصول
شبكة/NDK هنا) — لكن بدرجة أعلى من المعتاد:** هذا أول ملف في المشروع
بأكمله يتضمن `freerdp/peer.h`/`freerdp/listener.h` ويربط مع
`libfreerdp-server3.so` — الشكل العام (accept loop، `ContextNew`/
`ContextSize`، `Capabilities`/`PostConnect`/`Activate`/`Logon`،
`input->KeyboardEvent`/`MouseEvent`، `update->BitmapUpdate`) يقلّد عينات
FreeRDP الرسمية (`server/shadow/`, `server/Mac/mf_peer.c`,
`server/proxy/pf_server.c` — غير موجودة في هذا المستودع، لم تُفحص مباشرة)،
لكن أسماء الحقول/تواقيع الـ callbacks بالضبط لهذا الإصدار (3.27.1) لم
تُقارَن بالهيدرز الفعلية. **أول خطوة عند أي فشل بناء في
`systemsgo_server_jni.c`**: قارن مباشرة مع
`app/src/main/cpp/freerdp-prebuilt/<ABI>/include/freerdp3/freerdp/{peer.h,listener.h,update.h}`
بعد أول تشغيل CI ناجح لخطوة "Build FreeRDP prebuilt" بالعلم الجديد — هذا
هو نفس أسلوب العمل المتّبع لكل ميزة أخرى في هذا الملف، وليس استثناءً.

**تم تنفيذه الآن (Shadow Server، المرحلة الثانية):**

- **`systemsgo_server_jni.c`**: أُضيف سجلّ (`g_activePeers`) لكل الأطراف (peers)
  المتصلة حاليًا، وذاكرة مؤقتة (`g_latestFrame`) لآخر إطار حقيقي وُصل. نقطة
  دخول JNI جديدة `nativePushFrame(frame, width, height)` تستقبل مخزنًا
  بصيغة BGRX32 من Kotlin وتبثّه لكل الأطراف المتصلة عبر
  `systemsgo_server_broadcast_frame`؛ `systemsgo_server_peer_activate` صار يرسل
  آخر إطار حقيقي متوفر بدل اللون الرمادي الثابت دائمًا (يبقى الرمادي احتياطيًا
  فقط قبل أول نداء لـ `nativePushFrame`، أو إن اختلف مقاس الإطار المدفوع عن
  مقاس الخادم المهيَّأ).
- **`AFreeRdpServerBridge.kt`**: دالة جديدة `pushFrame(frame, width, height)`
  تنادي `nativePushFrame` أعلاه، بلا أي تغيير على شكل الصنف من المرحلة
  الأولى.
- **`com.systemsgo.hex.shadow` (حزمة جديدة)**:
  - `ShadowScreenCaptureService`: خدمة أمامية (foreground service) تستخدم
    `MediaProjection` + `VirtualDisplay` + `ImageReader` لالتقاط شاشة الجهاز
    الفعلية، تحوّلها من RGBA إلى BGRX32 (`toBgrx`)، وتدفعها عبر
    `AFreeRdpServerBridge.pushFrame` بمعدل مقيّد (`TARGET_FPS` = 12، بإسقاط
    أي دفعة (tick) تتأخر عن سابقتها بدل تكديسها).
  - `ShadowServerActivity`: الشاشة الوحيدة التي *يجب* أن تكون Activity لا
    Service — هي من تطلق حوار موافقة `MediaProjectionManager
    .createScreenCaptureIntent()` النظامي وتستقبل نتيجته، ثم تُشغّل/تُوقف
    `ShadowScreenCaptureService`. **لم تُربط بعد بأي نقطة دخول ثابتة في
    واجهة التطبيق** (نفس نمط "أثبت المسار أولًا" في المرحلة الأولى) — شغّلها
    مباشرة عبر `adb shell am start -n
    com.systemsgo.hex/.shadow.ShadowServerActivity` أو أضف زرًا واحدًا لها
    (مثلًا في شاشة الإعدادات) كخطوة تالية صغيرة بعد اختبارها فعليًا مقابل
    عميل RDP حقيقي.
  - `RemoteInputAccessibilityService` + `ScancodeKeyMap`: نصف حقن الإدخال —
    خدمة Accessibility اختيارية (يُفعِّلها المستخدم يدويًا من إعدادات
    أندرويد) تشترك في `peerMouseEvents`/`peerKeyEvents` من نسخة الجسر التي
    شغّلتها `ShadowScreenCaptureService` (`ShadowScreenCaptureService
    .activeBridge`)، وتحوّل حركة/ضغط الفأرة إلى لفتة لمس حقيقية عبر
    `dispatchGesture` (بآلية "continued stroke" كي يبقى السحب/الضغط
    المستمر لفتة واحدة لا لمسات منفصلة)، وتحوّل رموز مفاتيح PC الشائعة
    (أحرف/أرقام QWERTY أمريكي، Backspace، Enter، Space) إلى تعديلات نص على
    أي عنصر (`AccessibilityNodeInfo`) قابل للتحرير حاليًا في التركيز، عبر
    `ACTION_SET_TEXT`. **ليست لوحة مفاتيح كاملة عمدًا** — لا IME، لا لغات
    غير لاتينية، لا مفاتيح دالة (F1..)، تمامًا كما يوثّق تعليق الصنف نفسه.
  - **بلا حقن نظامي حقيقي لأحداث لوحة المفاتيح خارج حقول النص**: هذا قيد
    منصّة أندرويد نفسها (لا واجهة API عامة لحقن `KeyEvent` عبر كل التطبيقات
    بلا صلاحيات جذر أو تطبيق نظام)، وليس نقصًا في هذا الكود — بديل
    `AccessibilityNodeInfo`/`ACTION_SET_TEXT` أعلاه هو نفس الأسلوب الذي
    تستخدمه تطبيقات إدارة كلمات المرور المعروفة لنفس القيد بالضبط.
- **`AndroidManifest.xml`**: صلاحية جديدة عادية (بلا حوار وقت التشغيل)
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` (مطلوبة API 34+ لخدمة أمامية من نوع
  `mediaProjection`)، وتسجيل `ShadowServerActivity`/
  `ShadowScreenCaptureService`/`RemoteInputAccessibilityService` (الأخيرة
  محمية بـ `android:permission="...BIND_ACCESSIBILITY_SERVICE"` على عنصر
  `<service>` نفسه — هذا ما يمنع أي تطبيق آخر غير النظام من الارتباط بها،
  وليس عبر `<uses-permission>` في هذا الملف، الذي لا معنى له لهذه الصلاحية
  تحديدًا).
- **`res/xml/accessibility_service_config.xml`** (جديد): تفعيل
  `canPerformGestures`/`canRetrieveWindowContent` فقط — ما يحتاجه
  `RemoteInputAccessibilityService` بالضبط، بلا صلاحيات إضافية غير
  مستخدَمة.

**غير مُتحقَّق منه فعليًا** (نفس تحذير كل ميزة أخرى بلا NDK/جهاز اختبار هنا،
بدرجة أعلى من المعتاد لأن هذا أول كود في المشروع يستخدم `MediaProjection`/
`AccessibilityService.dispatchGesture` معًا): تدفّق البيانات
(التقاط→تحويل→BitmapUpdate) لم يُختبر مقابل عميل RDP حقيقي بعد؛ نمط
"continued stroke" في `dispatchGesture` يتبع توثيق Android الرسمي حرفيًا
لكن التوقيت (`GESTURE_STROKE_TICK_MS`) قد يحتاج ضبطًا بعد اختبار فعلي على
جهاز حقيقي.

**الخطوات التالية في الخارطة (لم تُنفَّذ بعد):**

- **ربط نقطة دخول ثابتة** لـ `ShadowServerActivity` في واجهة التطبيق (زر في
  شاشة الإعدادات على الأرجح) بدل تشغيلها عبر `adb` فقط.
- **RDP Proxy**: يحتاج العميل (`systemsgo_jni.c`) والخادم (هذا الملف) معًا في
  نفس العملية كوسيط بين طرف RDP وارد وآخر صادر — الأصعب في الخارطة الثلاثية
  المتفَق عليها، ويُبنى فوق الاثنين أعلاه.
- **TLS/NLA حقيقي** لطبقة الأمان (لا يزال Standard RDP Security فقط، أهم
  الآن أكثر من المرحلة الأولى لأن Shadow Server يعرض شاشة حقيقية ويقبل
  إدخالًا حقيقيًا — عاملها كاختبار شبكة محلية موثوقة فقط حتى يُنفَّذ هذا).

## USB-REDIRECT FEATURE: إعادة توجيه USB (MS-RDPEUSB) — الجزء 2/3 (الجسر الأصلي/JNI)

الجزء 1/3 (الطبقة الكوتلينية — `UsbRedirectionManager`، `UsbNativeBridge`،
`UsbRedirectionSettingsScreen`) كان جاهزًا مسبقًا، بانتظار جسر أصلي
(`systemsgo_urbdrc_jni.c`) يترجم بين طلبات MS-RDPEUSB القادمة من الخادم
وواجهة `UsbDeviceConnection` في أندرويد. هذا القسم يوثّق ما أضافه هذا
التعديل: تنفيذ ذلك الجسر بالكامل.

**لماذا لا يعتمد هذا الملف على `channels/urbdrc/client/*` الخاصة بـ FreeRDP
نفسها:** قناة `urbdrc` الأصلية في FreeRDP مبنية فوق backend من نوع
`IUDEVMAN`/`IUDEVICE` (`libusb_udevman.c`) يفترض شجرة `usbfs`/`libusb`
حقيقية — غير متاحة داخل sandbox تطبيق أندرويد بلا صلاحيات جذر. الاعتماد
عليها كان يعني أيضًا حاجة لإعادة بناء FreeRDP بعلم
`WITH_CHANNEL_URBDRC_CLIENT=ON` وتوريد رؤوسها الخاصة إلى هذا المستودع —
تغيير في إعدادات بناء FreeRDP نفسه، مؤجَّل عمدًا إلى الجزء 3 (انظر قائمة
"مؤجَّل إلى الجزء 3" داخل `systemsgo_urbdrc_jni.c` نفسه). بدلًا من ذلك، هذا
الملف يتحدث بروتوكول MS-RDPEUSB مباشرة فوق قناة ديناميكية (`Dynamic
Virtual Channel`) خاصة به بالكامل، مبنية فقط فوق الواجهة العامة المُثبَّتة
من SDK — `freerdp/channels/wtsvc.h` (`IWTSPlugin`/
`IWTSVirtualChannelCallback`)، `freerdp/dvc.h`
(`DVC_PLUGIN_ENTRY`/`IDRDYNVC_ENTRY_POINTS`)، و
`freerdp/channels/client.h` (`freerdp_channels_client_load_ex`).

**ما تم تطبيقه فعليًا (هذا التعديل):**

1. `systemsgo_urbdrc_jni.c` (جديد بالكامل، ~2150 سطر): محرك بروتوكول
   RDPEUSB + مُدير أجهزة USB داخلي (بلا أي اعتماد على `IUDEVICE`/
   `IUDEVMAN`) + مُنفِّذ `IWTSPlugin` لقناة `urbdrc` كاملة. يشمل:
   - تفاوض القدرات (`RIM_EXCHANGE_CAPABILITY_REQUEST/RESPONSE`).
   - إعلان/إزالة جهاز (`ADD_DEVICE`/`DEVICE_REMOVED`).
   - اختيار الإعداد والواجهة (`SELECT_CONFIGURATION`/`SELECT_INTERFACE`)
     عبر `performSetInterface` من الطبقة الكوتلينية.
   - نقل التحكم (`control`) والـ bulk/interrupt عبر
     `performControlTransfer`/`performBulkOrInterruptTransfer`، مع تجمّع
     خيوط عامل (`worker pool`، 4 خيوط افتراضيًا) بدل خيط لكل نقل — النقل
     لا يحجب خيط بروتوكول RDPEUSB أبدًا.
   - تتبّع الطلبات المعلَّقة (`pending URBs`) لدعم `URB_CANCEL` بشكل
     صحيح، وإعادة تعيين/إزالة الجهاز (`RESET`).
   - معالجة أخطاء شاملة: أي حزمة مشوَّهة، جهاز اختفى، أو استثناء JNI لا
     يُسقط العملية أبدًا — يُسجَّل ويُرسَل ردّ فشل (`USBD_STATUS_*`) بدل
     ذلك.
   - كل خيط عامل يستدعي Java يتبع نمط `AttachCurrentThread`/`GetEnv`/
     مسح استثناء معلَّق/`DetachCurrentThread` نفسه المستخدَم في
     `systemsgo_jni.c` و`pcsc_shim.c`.
2. نقاط دخول JNI تطابق التوقيعات الموجودة مسبقًا في `UsbNativeBridge.kt`
   دون أي تعديل عليها: `nativeDeviceAttached`، `nativeDeviceDetached`،
   `nativeSetChannelActive` — تستدعي بدورها
   `performControlTransfer`/`performBulkOrInterruptTransfer`/
   `performReset`/`performSetInterface` المُعرَّفة مسبقًا في الجزء 1.
3. `CMakeLists.txt`: **لم يحتَج أي تعديل** — سقالة "USB-REDIRECT FEATURE
   (Part 1/3)" الموجودة مسبقًا (خيار `SYSTEMSGO_USB_BACKEND_AVAILABLE`،
   افتراضيًا `ON`) كانت مكتوبة مسبقًا لتبني `systemsgo_urbdrc_jni.c` فور
   وجوده — وهو موجود الآن.

> ⚠️ **أكثر جزء غير مُتحقَّق منه في هذا الملف: آلية التسجيل مع
> `drdynvc`.** تسجيل plugin لقناة ديناميكية داخل العملية نفسها (بلا
> إعادة بناء FreeRDP بعلم `CHANNEL_URBDRC_CLIENT` وبلا ملف `.so` منفصل
> قابل لـ `dlopen`) ليس له سابقة عملية موثَّقة في هذا المشروع بعد —
> `urbdrc_register_with_channels()` تستخدم `freerdp_channels_client_load_ex`
> على افتراض أنها تقبل نقطة دخول من نوع DVC، وهذا الافتراض هو أول شيء
> يجب التحقق منه إن فشل البناء أو إن لم يُستدعَ `Initialize()` للـ
> plugin عند الاتصال. راجع التعليق الطويل فوق تلك الدالة مباشرة داخل
> `systemsgo_urbdrc_jni.c` للبديل المقترح (بناء `.so` منفصل بتسمية تطابق
> اتفاقية FreeRDP لإضافات `urbdrc`، بنفس أسلوب `libpcsclite.so` أعلاه).
>
> ⚠️ **كل ثابت رقمي لبروتوكول MS-RDPEUSB في هذا الملف (`RDPEUSB_MSG_*`
> وما شابه) منقول من مواصفة Microsoft العامة، ولم يُقارَن بايتًا-ببايت
> مقابل التقاط شبكي حقيقي أو خادم RDP حقيقي بعد.** كل ثابت مُعرَّف باسمه
> تحديدًا لهذا السبب — أي خطأ يكون تصحيحًا في سطر واحد، لا بحثًا عن رقم
> سحري متناثر. راجع تعليق الترويسة في `systemsgo_urbdrc_jni.c` للتفاصيل
> الكاملة، وقائمة "DEFERRED TO PART 3" في نهايته لكل ما تبقّى عمدًا خارج
> نطاق هذا الجزء (اختبار الحمل، حالات تزامن hot-plug الحرجة، استعادة
> الحالة بعد إعادة الاتصال، تعديلات CI، تفعيل `CHANNEL_URBDRC_CLIENT`).

### تحديث (Part 3B/2): الاتصال بدورة حياة الجلسة، hot-plug حي، ثبات الـ workers، وCI

الأجزاء 3A و3B/1 غطّت العناصر 1-4 من `PART_3_PROMPT.md` (ربط دورة حياة
الجلسة، hot-plug أثناء جلسة نشطة، فصل الجهاز أثناء نقل بيانات، واستعادة
الاتصال). هذا التحديث يغطي العناصر 5-9:

- **العنصر 5 (مهلات، تعافي، إعادة محاولة محدودة):** أضيفت إعادة محاولة
  محدودة (محاولتان بحد أقصى، تأخير قصير ثابت) في
  `UsbRedirectionManager.executeControlTransfer`/`executeDataTransfer`
  لأخطاء I/O عابرة — بالإضافة إلى "فحص صحة" غير دوري (event-driven بدل
  مؤقّت متكرر) يزيل الجهاز تلقائيًا (بنفس مسار الفصل الفعلي) بعد سلسلة
  فشل متتالية طويلة بما يكفي لترجيح أن الـ `fd` نفسه تعطّل، لا مجرد خطأ
  عابر. **قرار تصميم متعمَّد:** لم يُربط `resetDevice()` تلقائيًا داخل
  حلقة إعادة المحاولة هذه — إعادة تعيين الجهاز تُبطل كل مطالبات الواجهات
  (`claimInterface`) على الجهاز كله، وربطها تلقائيًا بأي فشل عابر لنقل
  واحد قد يُفسد نقلًا آخر متزامنًا على نفس الجهاز (جهاز مركّب HID+تخزين
  مثلًا) — إعادة التعيين تبقى فقط استجابة لطلب `RESET` صريح من الخادم عبر
  MS-RDPEUSB (المسار الحالي، `executeReset`)، وهو المصدر الوحيد
  الصحيح لقرار "متى نُعيد التعيين". أيضًا: مهلة نقل interrupt IN التي كانت
  `0` (بلا حد أقصى فعليًا في واجهة أندرويد) صارت محدودة
  (`SYSTEMSGO_USB_INTERRUPT_TIMEOUT_MS`، 60 ثانية) كي لا يبقى أي خيط من
  تجمّع الـ workers (4 خيوط فقط) محجوزًا إلى الأبد بجهاز HID خامل، وهو ما
  كان يمكن أن يجوّع باقي الأجهزة المُعاد توجيهها. تم أيضًا تفعيل حد
  `SYSTEMSGO_USB_MAX_PENDING_URBS` (كان مُعرَّفًا وغير مُطبَّق) كحماية من
  نمو غير محدود لقائمة الطلبات المعلَّقة إذا أرسل الخادم طلبات أسرع مما
  يستطيع تجمّع الـ workers استيعابه.
- **العنصر 6 (أجهزة متعددة متزامنة):** رُوجعت بنية `nativeIdToKey`/
  `openConnections` (Kotlin) و`g_urb.devices[]`/`pendingUrbs` لكل جهاز
  (native) ووُجدت كافية لـ 2-4 أجهزة متزامنة بلا تعديل: كل جهاز له قفل
  خاص به لقائمة طلباته المعلَّقة (`RedirectedDevice.lock`)، فلا تتنافس
  الأجهزة على قفل مشترك عند النقل الفعلي؛ القفل العام
  (`g_urb.devicesLock`) يُستخدم فقط لبحث قصير عن الجهاز (مصفوفة من 16
  عنصرًا كحد أقصى) وليس أثناء استدعاء JNI الفعلي للنقل. لم يُجرَ أي تغيير
  بنيوي — راجع خطة اختبار الحمل في رد المحادثة لإجراء عملي على جهاز حقيقي.
- **العنصر 7 (CI):** **لم يُعدَّل `.github/workflows/main.yml`.**
  `-DCHANNEL_URBDRC_CLIENT=ON` غير مطلوب لأن `systemsgo_urbdrc_jni.c` لا
  يعتمد على `channels/urbdrc/client/*` الخاصة بـ FreeRDP إطلاقًا (انظر
  تعليق ترويسة الملف) — يبني إضافة DVC مخصّصة بالكامل فوق الواجهة العامة
  المُثبَّتة فقط (`freerdp/channels/wtsvc.h`، `freerdp/dvc.h`،
  `freerdp/channels/client.h`) ويُسجَّل داخل العملية نفسها عبر
  `freerdp_channels_client_load_ex()`، لا عبر تحميل `.so` منفصل باسم
  `urbdrc-client`. تفعيل هذا العلم كان سيبني نسخة منفصلة تمامًا من
  إضافة `urbdrc` الأصلية في FreeRDP دون أن يستخدمها أو يحمّلها أي جزء من
  هذا المستودع — علم بناء ميت، لا تغيير حقيقي، لذلك لم يُضَف.

#### قيود معروفة (USB-REDIRECT FEATURE)

بنفس روح قسم "القيود المعروفة" الخاص بالبطاقة الذكية أعلاه — هذه فجوات
حقيقية لا تزال قائمة بعد الأجزاء 3A/3B/3B، لا نسيانًا:

1. **آلية تسجيل `drdynvc` لم تُختبَر ضد بناء/خادم حقيقيَّين بعد.** أكثر
   جزء "غير مؤكَّد" في الملف كله — راجع التحذير ⚠️ أعلاه.
2. **ثوابت بروتوكول MS-RDPEUSB الرقمية (`RDPEUSB_MSG_*` وغيرها) منقولة من
   المواصفة العامة فقط**، ولم تُقارَن بايتًا-ببايت مقابل التقاط شبكي
   حقيقي أو خادم RDP حقيقي — أول شيء يُراجَع لو ظهر سلوك بروتوكولي غريب.
3. **لا دعم لنقل isochronous** (صوت/فيديو USB حي عبر URB) ولا لآلية
   `RDPEUSB_ADD_VIRTUAL_CHANNEL` الثانوية — خارج نطاق هذا التطبيق حاليًا.
4. **الأجهزة متعددة الإعدادات (multi-configuration)** غير مدعومة فعليًا:
   `SELECT_CONFIGURATION` يُقَرّ فقط بالإعداد الذي اختاره أندرويد تلقائيًا
   عند فتح الجهاز (لا مكافئ لـ `libusb_set_configuration` في واجهة
   أندرويد العامة). الغالبية العظمى من أجهزة USB (بطاقات ذكية، مفاتيح
   FIDO2، تخزين، HID) لها إعداد واحد فقط، فهذا نادرًا ما يظهر عمليًا.
5. **إعادة المحاولة/التعافي (هذا التحديث) محدودة الحدّة عمدًا** — محاولتان
   لكل نقل، بلا `resetDevice()` تلقائي (انظر أعلاه). جهاز يفشل باستمرار
   (أكثر من عتبة `MAX_CONSECUTIVE_TRANSFER_FAILURES`) يُزال تلقائيًا
   كأنه فُصل فعليًا؛ لو كان لا يزال متصلًا فعليًا فسيُعاد ربطه عبر نفس
   مسار العنصر 4 (استعادة تلقائية)، لكن أي نقل بيانات كان قيد التنفيذ في
   تلك اللحظة يُفقَد (لا استئناف جزئي).
6. **لم يُجرَ اختبار حمل فعلي** على `SYSTEMSGO_USB_MAX_DEVICES`/
   `SYSTEMSGO_USB_WORKER_THREADS`/`SYSTEMSGO_USB_MAX_PENDING_URBS` — القيم
   الحالية (16 جهازًا، 4 خيوط عامل، 64 طلبًا معلَّقًا لكل جهاز) نقطة بداية
   معقولة غير مُشتقّة من قياس فعلي؛ راجع خطة اختبار الحمل في رد المحادثة.


## SERIAL-OVER-NETWORK FEATURE: Raw TCP / RFC 2217 لإعادة توجيه المنفذ التسلسلي

يضيف هذا `serialRedirectMode` (`LOCAL_DEVICE` الافتراضي / `RAW_TCP` / `RFC_2217`)
إلى `RdpProfile` — بديل عن `serialPortPath` (جهاز `/dev/ttyX` محلي) يسمح بربط
منفذ MS-RDPESP التسلسلي بخادم شبكي (مثل `ser2net`) بدلاً من محول USB-OTG فعلي.

**طبقة Kotlin** — `com.systemsgo.hex.rdp.serial.SerialNetworkBridge`: يفتح اتصال
TCP بالخادم، وفي وضع `RFC_2217` يطبّق طرف العميل الكامل لبروتوكول RFC 2217 (تفاوض
telnet عبر `COM-PORT-OPTION`، ترميز/فك IAC، أوامر `SET-BAUDRATE`/`SET-CONTROL`/
`PURGE-DATA`...، واستقبال `NOTIFY-MODEMSTATE`/`NOTIFY-LINESTATE`). هذا الجزء
منطق Kotlin خالص وله اختبارات وحدة (`SerialNetworkBridgeTest`).

**طبقة native** — `systemsgo_serial_bridge.c` (مبني ضمن نفس مكتبة `systemsgo_jni.so`،
راجع `CMakeLists.txt`): بما أن قناة FreeRDP التسلسلية تتوقع جهاز tty حقيقي (تفتح
مسارًا وتستخدم `ioctl`/`termios` عليه مباشرة)، ينشئ هذا الملف زوج PTY حقيقي
عبر `openpty()`، يُمرَّر مسار الـ slave إلى بلوك `"serial"` الموجود مسبقًا في
`systemsgo_jni.c` كما لو كان جهازًا حقيقيًا، ويربط طرف الـ master بمقبس محلي
(`android.net.LocalServerSocket`, namespace مجرّد) يتصل به `SerialNetworkBridge`
من جهة Kotlin. خيط native واحد يرحّل البايتات في الاتجاهين، ويستطلع (poll)
خطوط التحكم (`TIOCMGET`) وسرعة الإرسال (`tcgetattr`) على الـ master كل ~200ms،
ويستدعي `SerialNetworkBridge.setDtr/setRts/setBaudRate` عبر JNI عند أي تغيير.

**تحذيرات معروفة تحتاج تحقّقًا على جهاز حقيقي** (موثّقة بالتفصيل أعلى
`systemsgo_serial_bridge.c`):
- تخطيط إشارات `DTR/RTS` مقابل `CTS/DSR/CD` يعتمد على سلوك "null-modem" الخاص
  بتعريف PTY في نواة Linux الأساسية — سلوك حقيقي وموثّق لكنه غير مضمون حرفيًا
  عبر كل نواة AOSP/بائع على حدة.
- سرعة الإرسال هنا تدعم فقط ثوابت `B*` القياسية (`cfgetispeed`)، وليس سرعات
  غير قياسية عبر `termios2`/`TCGETS2` (تم تجنّبها عمدًا بسبب تعارض معروف بين
  رؤوس bionic و`<asm/termbits.h>` — إن لزمت لاحقًا، تُعزل في ملف `.c` منفصل).
- لم يُختبَر بعد end-to-end مقابل خادم RFC 2217 حقيقي (الأسهل: `ser2net`)؛
  الخطوات: 1) شغّل `ser2net` بمنفذ RFC 2217 يشير إلى جهاز تسلسلي حقيقي أو
  PTY آخر على نفس الشبكة، 2) أنشئ ملف تعريف RDP مع `serialRedirectMode=RFC_2217`
  و`serialNetworkHost`/`serialNetworkPort` يشيران إليه، 3) فعّل `enableSerialRedirect`
  واتصل، 4) تحقق من ظهور جهاز COM جديد على الجلسة البعيدة وأن البيانات/
  DTR-RTS/سرعة الإرسال تتدفق في الاتجاهين.
