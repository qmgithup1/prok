cmake_minimum_required(VERSION 3.22.1)
project(systemsgo_native)

set(CMAKE_C_STANDARD 11)

# ════════════════════════════════════════════════════════════════════════
# SystemsGo JNI Bridge
# ════════════════════════════════════════════════════════════════════════
#
# الاستراتيجية: نستخدم FreeRDP و OpenSSL كـ prebuilt libraries جاهزة.
# FreeRDP يُبنى مسبقاً في CI عبر cmake مباشرة (انظر main.yml خطوة
# "Build FreeRDP prebuilt"). هذا يتجنب تماماً مشاكل add_subdirectory
# مع NDK toolchain (SWScale REQUIRED، OpenSSL scope، إلخ).

# DOC FIX: a `FREERDP_DIR` variable pointing at ${CMAKE_CURRENT_SOURCE_DIR}/FreeRDP
# (a source checkout path) used to live here but was never referenced by
# anything below — this build only ever consumes prebuilt binaries (see
# FREERDP_PREBUILT_ROOT), never FreeRDP source directly. Removed to avoid
# implying a submodule/source build this file doesn't perform (see SETUP.md).
set(FREERDP_PREBUILT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/freerdp-prebuilt)
set(FREERDP_ABI_DIR ${FREERDP_PREBUILT_ROOT}/${ANDROID_ABI})

# ── SPICE-PROTOCOL FEATURE: prebuilt roots (Part 2/N CI step + Part 3/N ──
# real JNI logic are both now in place; see main.yml's "Build SPICE
# prebuilt" step and systemsgo_spice_jni.c). نفس نمط FreeRDP أعلاه: مكتبات
# SPICE (spice-client-glib + spice-protocol + GLib/GObject) تُبنى مسبقاً
# في CI كـ prebuilt وتوضع هنا، وليس عبر add_subdirectory. إن فشل بناء
# الـ prebuilt لأي ABI (fail-soft)، SPICE_ABI_DIR لن يحوي
# libspice-client-glib-2.0.so لذلك الـ ABI، والقسم أدناه سيُطبع رسالة
# "not found" ويتخطى البناء بأمان لذلك الـ ABI فقط (نفس سلوك FreeRDP).
set(SPICE_PREBUILT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/spice-prebuilt)
set(SPICE_ABI_DIR ${SPICE_PREBUILT_ROOT}/${ANDROID_ABI})

# ── MOSH-PROTOCOL FEATURE (Part 1/N): scaffold only ──────────────────────
# نفس نمط SPICE أعلاه: مصدر mosh (src/crypto + src/network + src/statesync
# + src/terminal من شجرة mosh الأصلية) + protobuf-lite يُبنيان مسبقاً في CI
# كـ prebuilt ويوضعان هنا، وليس عبر add_subdirectory. هذا أول جزء فقط
# (scaffold): المتغيرات + fail-soft check + systemsgo_mosh_jni.c كهيكل أولي
# بدون منطق SSP فعلي بعد. خطوة CI ("Build Mosh prebuilt") لم تُضَف بعد لـ
# main.yml — لذلك MOSH_ABI_DIR لن يكون موجوداً في أي بناء حالي، والقسم
# أدناه سيُطبع رسالة "not found" ويتخطى البناء بأمان (نفس سلوك SPICE/FreeRDP
# في حالة عدم وجود الـ prebuilt). راجع mosh/NOTES.md لتفاصيل لماذا هذا أبعد
# من "ربط مكتبة جاهزة" (لا يوجد mosh-client كمكتبة مشتركة جاهزة على أي
# توزيعة — مصدره فقط).
set(MOSH_PREBUILT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/mosh-prebuilt)
set(MOSH_ABI_DIR ${MOSH_PREBUILT_ROOT}/${ANDROID_ABI})

# AUDIO-BACKEND FIX: whether the FreeRDP prebuilt under FREERDP_ABI_DIR was
# itself built with a working Android audio subsystem (OpenSL ES/AAudio) for
# the "rdpsnd"/"audin" channels. Defaults OFF because the "Build FreeRDP
# prebuilt" CI step (.github/workflows/main.yml) currently builds with
# WITH_PULSE=OFF and no such subsystem enabled — see SETUP.md. There is no
# way to detect this by inspecting the prebuilt .so itself (FreeRDP exposes
# no "which audio backends were compiled in" symbol), so this has to be
# flipped by hand the day the CI build step is changed to actually enable
# one. Read by systemsgo_jni.c as SYSTEMSGO_AUDIO_BACKEND_AVAILABLE — see that
# file's doc comment on the macro for how it's consumed.
#
# REAL-PCM FIX: the actual fix for "no real audio plays" is NOT more code in
# systemsgo_jni.c — FreeRDP already ships a complete, working Android audio
# backend for both channels (channels/rdpsnd/client/opensles/ for playback,
# channels/audin/client/opensles/ for microphone capture), built on OpenSL
# ES, a stable Android NDK API present on every device/API level. It just
# isn't compiled into this project's FreeRDP prebuilt today, because the
# "Build FreeRDP prebuilt" step in .github/workflows/main.yml never passes
# -DWITH_OPENSLES=ON (that file is not part of this patch set, so it must be
# edited directly — see SETUP.md's "REAL-PCM FIX" section for the exact
# one-line change, in BOTH the `build` and `release` jobs, the same way
# WITH_PULSE=OFF etc. appear twice today). Once that flag is on and the
# prebuilt is rebuilt (bump/clear the FreeRDP prebuilt cache key — a WITH_*
# flag change alone does not invalidate it), rdpsnd_process_connect() picks
# "opensles" automatically (tried before pulse/alsa/oss/fake in FreeRDP's own
# backend list) and PCM from the remote session's rdpsnd channel plays on
# the Android output automatically, entirely inside libfreerdp-client3.so —
# no OpenSL ES/AAudio calls are needed in systemsgo_jni.c at all. At that point
# flip this option to ON below (or pass
# -DSYSTEMSGO_AUDIO_BACKEND_AVAILABLE=ON) to turn the "Enable Remote Sound"
# toggle on in the UI. Microphone capture (audin) works the same way and
# needs no change to nativeSendAudioCapture()/sendAudioCapture() either,
# since FreeRDP's opensles audin backend records from the mic itself.
# REAL-PCM FIX: flipped to ON — .github/workflows/main.yml's "Build FreeRDP
# prebuilt" step (both the `build` and `release` jobs) now passes
# -DWITH_OPENSLES=ON when it builds the FreeRDP prebuilt this option
# describes, so the prebuilt this CMakeLists.txt links against does have a
# working Android audio backend from this point on. If you ever revert that
# main.yml flag (or point FREERDP_ABI_DIR at an older prebuilt built before
# this change), flip this back to OFF — there is still no way to detect it
# automatically by inspecting the .so itself.
option(SYSTEMSGO_AUDIO_BACKEND_AVAILABLE
    "FreeRDP prebuilt was built with a working Android audio backend (OpenSL ES/AAudio)"
    ON)

# PRINTER-REDIRECT FEATURE: whether the FreeRDP prebuilt under FREERDP_ABI_DIR
# was itself built with a working printer-redirection backend for the
# "rdpdr" channel's printer device (MS-RDPEPC). Same shape as
# SYSTEMSGO_AUDIO_BACKEND_AVAILABLE just above: a manually-maintained flag,
# since there's no way to detect this by inspecting the prebuilt .so.
#
# PRINTER-CUPS FIX: was OFF because upstream FreeRDP's printer channel plugin
# (channels/printer/client) needs CUPS — a desktop/Linux printing library,
# not a stock part of Android NDK — and CI built FreeRDP with -DWITH_CUPS=OFF.
# The "Build CUPS prebuilt" CI step (main.yml) now cross-builds libcups
# itself for every ABI and passes -DWITH_CUPS=ON + CUPS_INCLUDE_DIR/
# CUPS_LIBRARIES to the FreeRDP prebuilt step, so this default now matches
# that (same pattern as SYSTEMSGO_AUDIO_BACKEND_AVAILABLE flipping to ON after
# REAL-PCM FIX). Read by systemsgo_jni.c as SYSTEMSGO_PRINT_BACKEND_AVAILABLE, and
# by AFreeRdpBridge.isPrinterBackendAvailable for the UI toggle.
#
# CAVEAT: unlike WITH_OPENSLES (a stock, FreeRDP-supported NDK interface),
# CUPS is an autotools project never officially validated to cross-compile
# for Android — treat this as experimental until a real CI run confirms it.
# This flag is also one build-wide switch, not per-ABI: main.yml's CUPS step
# skips (rather than aborts on) a failing ABI, so if CUPS builds for some
# ABIs but not others, the FreeRDP .so for the failing ABI(s) simply won't
# have rdpdr-printer support even though this flag reads "available"
# app-wide. Not a crash — freerdp_client_add_device_channel()'s return value
# is already checked in systemsgo_jni.c and only logs an error for that ABI —
# but check each ABI's "Build CUPS prebuilt" log, not just a green workflow,
# before relying on this in a release.
#
# CUPS-PRINT FIX (verified against main.yml): this comment previously
# described a "Build CUPS prebuilt" step and -DWITH_CUPS=ON wiring that did
# NOT actually exist in main.yml — the real FreeRDP build step hard-coded
# -DWITH_CUPS=OFF unconditionally in both jobs, so every real release
# silently shipped this flag reading ON/"available" while the printer
# channel had no CUPS backend behind it at all (the toggle did nothing).
# main.yml now has a real "Build CUPS prebuilt" step (mirroring PCSC-lite's
# fail-soft, per-ABI autotools shape) and a CUPS_ABI_FLAGS block in the
# FreeRDP build step that only passes -DWITH_CUPS=ON for ABIs where it
# actually produced libcups.a — this option's ON default is now backed by
# a real CI step, not just a comment describing one.
option(SYSTEMSGO_PRINT_BACKEND_AVAILABLE
    "FreeRDP prebuilt was built with a working printer-redirection backend (rdpdr printer device)"
    ON)

# WEBCAM-REDIRECT FEATURE: whether the FreeRDP prebuilt under FREERDP_ABI_DIR
# was itself built with a working camera-redirection backend for the
# "rdpecam" dynamic virtual channel (MS-RDPECAM). Same shape as
# SYSTEMSGO_AUDIO_BACKEND_AVAILABLE / SYSTEMSGO_PRINT_BACKEND_AVAILABLE above: a
# manually-maintained flag, since there's no way to detect this by
# inspecting the prebuilt .so itself.
#
# Unlike printer/CUPS, this one does NOT need any extra desktop/Linux
# library cross-compiled for Android: FreeRDP upstream PR #12894
# ("[client,rdpecam,android] Add camera redirection support", first
# released in FreeRDP 3.27.1 — see 3.27.1 changelog: "Android client
# RDPECAM support") ships a native Camera2-NDK-based capture backend for
# "rdpecam" specifically for Android, the same way channels/rdpsnd/client/
# opensles/ ships a ready Android backend for "rdpsnd" (REAL-PCM FIX
# above). It just needs the channel compiled in.
#
# main.yml's "Build FreeRDP prebuilt" step (both `build` and `release`
# jobs) now pins FREERDP_TAG=3.27.1 (was 3.24.2, which predates #12894
# entirely — no CMake flag on that tag would have enabled this) and passes
# -DCHANNEL_RDPECAM_CLIENT=ON, so this default now matches that build
# (same pattern as SYSTEMSGO_AUDIO_BACKEND_AVAILABLE flipping to ON after
# REAL-PCM FIX). Read by systemsgo_jni.c as SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE,
# and by AFreeRdpBridge.isWebcamBackendAvailable for the UI toggle.
#
# CAVEAT: this is brand new upstream (merged weeks before this fix) and
# has not been confirmed against a real CI run in this environment (no
# network access here to actually build FreeRDP and check). If the
# "Build FreeRDP prebuilt" step fails to compile channels/rdpecam/client
# for some ABI against NDK r27d, that ABI simply won't have rdpecam
# support even though this flag reads "available" app-wide — check each
# ABI's build log, not just a green workflow, before relying on this in a
# release. If it turns out CHANNEL_RDPECAM_CLIENT does not build cleanly
# at all, revert this to OFF and drop -DCHANNEL_RDPECAM_CLIENT=ON from
# main.yml (both jobs), the same fallback path documented for CUPS above.
option(SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE
    "FreeRDP prebuilt was built with a working camera-redirection backend (rdpecam dynamic channel)"
    ON)

# CODEC-NEGOTIATION FEATURE: whether the FreeRDP prebuilt under
# FREERDP_ABI_DIR was itself built with a working H.264 (AVC420/AVC444)
# decode backend for the RDPGFX graphics pipeline. Same shape as the flags
# above — a manually-maintained compile-time flag, since there is no way to
# probe "was this .so built with a real H.264 decoder" by inspecting it.
#
# Setting FreeRDP_GfxH264/FreeRDP_GfxAVC444 to TRUE only controls what the
# client *advertises* during the RDPGFX capability exchange — it does not by
# itself provide a decoder. FreeRDP's H.264 codec module
# (libfreerdp/codec/h264.c) needs an actual backend compiled in to turn
# decoded NAL units into pixels: -DWITH_OPENH264=ON (needs libopenh264
# cross-compiled for Android, the same "extra prebuilt dependency" shape as
# CUPS/PCSC above) or -DWITH_FFMPEG=ON (explicitly OFF in this project's
# main.yml — see SETUP.md).
#
# H264-OPENH264 FIX: main.yml's "Build FreeRDP prebuilt" step (both `build`
# and `release` jobs) now has a preceding "Build openh264 prebuilt" step
# (same fail-soft, per-ABI shape as "Build PCSC-lite prebuilt") that
# cross-builds libopenh264 for every ABI in BUILD_ABIS, and -DWITH_OPENH264
# is passed ON for whichever ABIs that step actually produced a static lib
# for (OFF otherwise — mirrors the WITH_PCSC conditional in the FreeRDP
# build step). So this now defaults ON, matching that: advertising a codec
# with no decoder behind it would make the server pick H.264 and then every
# frame would fail to decode, exactly the kind of silent breakage
# SYSTEMSGO_PRINT_BACKEND_AVAILABLE/SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE already
# avoid for their own channels — this flag flipping ON is only correct
# because the CI step feeding it now exists, not on its own. Read by
# systemsgo_jni.c as SYSTEMSGO_H264_BACKEND_AVAILABLE, and by
# AFreeRdpBridge.isH264BackendAvailable for the codec-preference UI (see
# RdpRemoteAdapter's Advanced Settings section / CodecPreferenceSection in
# Components.kt).
#
# ⚠️ Same caveat as CUPS/PCSC/rdpecam above: this is unverified against a
# real CI run and a real Windows RDP server. openh264's own Android/NDK
# cross-compile invocation (Makefile variables such as OS=android,
# NDKROOT=, ARCH=, TARGET=) was written from memory/documentation in an
# environment with no network access to check openh264's actual BUILD.md /
# Makefile against the exact revision main.yml clones — treat the "Build
# openh264 prebuilt" step itself as the least-verified part of this whole
# change. If it fails for an ABI or all ABIs, that ABI (or the whole build)
# falls back to -DWITH_OPENH264=OFF automatically (see the FreeRDP build
# step) — it will NOT silently claim H.264 works; worst case this flag
# would need reverting to OFF here if openh264 categorically can't be made
# to build this way. Check every ABI's "Build openh264 prebuilt" log AND
# do a real H.264 negotiation test against a real Windows RDP host before
# relying on this in a release.
option(SYSTEMSGO_H264_BACKEND_AVAILABLE
    "FreeRDP prebuilt was built with a working H.264 (AVC420/AVC444) decode backend for the RDPGFX pipeline"
    ON)

# CODEC-NEGOTIATION FEATURE: whether the FreeRDP prebuilt under
# FREERDP_ABI_DIR was itself built with a working AV1 decode backend for the
# RDPGFX graphics pipeline. Same shape as SYSTEMSGO_H264_BACKEND_AVAILABLE just
# above.
#
# AV1 support in FreeRDP itself is brand new — added as *experimental* in
# release 3.25 (this project's FREERDP_TAG, 3.27.1, postdates that) — and
# upstream's own release notes for it say it "currently works only with
# FreeRDP based servers", i.e. it will not negotiate against a stock
# Windows RDP host (mstsc's server side has no AV1 codec to offer). Building
# it needs two of FreeRDP's own external-library features, both cross-
# compiled for Android: AOM (the AV1 encode/decode library itself — NOT
# dav1d; FreeRDP's own PR #12527 wires it up specifically against AOMedia's
# libaom) and YUV (a color-conversion helper library the same PR added
# alongside it). FreeRDP registers both via its own `find_feature()` CMake
# helper, which — confirmed directly against FreeRDP's own CMakeLists.txt —
# derives the flag name from the feature name passed to it, so the actual
# flags for "Build FreeRDP prebuilt" are -DWITH_AOM=ON and -DWITH_YUV=ON,
# not -DWITH_AV1=ON (a plausible-looking but wrong guess this project made
# before checking).
#
# AV1-CODEC-BUILD FEATURE: main.yml now has "Build aom prebuilt"/"Build
# libyuv prebuilt" steps (same fail-soft, per-ABI shape as CUPS/PCSC-lite/
# openh264) feeding -DWITH_AOM=ON/-DWITH_YUV=ON into "Build FreeRDP
# prebuilt", so this now defaults ON — same reasoning as
# SYSTEMSGO_H264_BACKEND_AVAILABLE flipping ON once its own CI step existed:
# advertising a codec with no decoder behind it is worse than not
# advertising it, but that's only true once the step feeding it is actually
# there. Read by systemsgo_jni.c as SYSTEMSGO_AV1_BACKEND_AVAILABLE, and by
# AFreeRdpBridge.isAv1BackendAvailable.
#
# ⚠️ Same caveat as SYSTEMSGO_H264_BACKEND_AVAILABLE: unverified against a real
# CI run. Two extra unknowns specific to this pair, beyond the usual
# "no network access to check exact build flags against source" caveat
# openh264/CUPS/PCSC-lite already carry: (1) FreeRDP's Findaom.cmake/
# Findyuv.cmake resolve their libraries via pkg-config (confirmed against
# FreeRDP's own cmake/ directory listing), not the plain
# XXX_INCLUDE_DIR/XXX_LIBRARIES CMake cache variables PCSC/openh264/CUPS
# use — so the CI step points pkg-config at the built .pc files via
# PKG_CONFIG_LIBDIR instead of passing include/lib paths directly; if
# Findaom.cmake/Findyuv.cmake actually expect something else, "Build
# FreeRDP prebuilt" will silently fall back to -DWITH_AOM=OFF/
# -DWITH_YUV=OFF rather than fail loudly. (2) libyuv has no official
# CMakeLists.txt or pkg-config file (upstream builds it via GN) — the
# "Build libyuv prebuilt" step relies on a community-maintained
# CMakeLists.txt on the LIBYUV_BRANCH clone producing a target literally
# named "yuv", and writes yuv.pc/libyuv.pc itself since none exists
# upstream to install. If either assumption is wrong for the exact
# LIBYUV_BRANCH head cloned, that step's own per-ABI fail-soft handling
# (see its comment in main.yml) degrades to -DWITH_YUV=OFF the same way —
# it will NOT silently claim AV1 works either way. Check every ABI's
# "Build aom prebuilt"/"Build libyuv prebuilt"/"Build FreeRDP prebuilt"
# logs first, then do a real AV1 negotiation test against a FreeRDP-based
# server before relying on this in a release. If aom/libyuv categorically
# can't be made to build this way, revert this flag to OFF and remove
# AOM_TAG/LIBYUV_BRANCH's build steps from main.yml, same rollback path
# documented for SYSTEMSGO_H264_BACKEND_AVAILABLE/openh264.
#
# SETTINGS KEY (confirmed, no longer a guess): the FreeRDP_Settings_Keys_Bool
# AV1 negotiation uses is "GfxCodecAV1" — confirmed against FreeRDP's own
# merged PR #12527 ("[settings,av1] add AV1 related settings", commit
# 6232229, merged as 7d7de34), whose commit message states explicitly
# "GfxCodecAV1 to enable/disable support" (there's also a paired
# "GfxCodecAV1Profile" for quality, not currently used here). See
# systemsgo_jni.c's systemsgo_apply_codec_preference() for where this is set — by
# name, via freerdp_settings_set_value_for_name(), which still fails safe
# (logs and continues) instead of a hard compile error if a future tag ever
# renames the key again. Treat AV1 as experimental even after flipping this
# ON, and verify against FreeRDP's actual codec-id capability confirm before
# relying on it in a release.
option(SYSTEMSGO_AV1_BACKEND_AVAILABLE
    "FreeRDP prebuilt was built with a working AV1 decode backend for the RDPGFX pipeline (experimental, FreeRDP-server-only)"
    ON)

# SMARTCARD-REDIRECT FEATURE: whether the FreeRDP prebuilt under
# FREERDP_ABI_DIR was itself built with a working smart-card redirection
# backend for the "rdpdr" device-redirection channel's smartcard device
# (MS-RDPESC). Same shape as SYSTEMSGO_PRINT_BACKEND_AVAILABLE above: a
# manually-maintained flag, since there's no way to detect this by
# inspecting the prebuilt .so itself.
#
# Was OFF because CI built FreeRDP with -DWITH_PCSC=OFF (see SETUP.md's
# smart-card section) — upstream FreeRDP's channels/smartcard/client addin
# links against libpcsclite (PCSC-lite), which has no official Android/AOSP
# prebuilt. main.yml's "Build PCSC-lite prebuilt" step now cross-builds
# libpcsclite itself for every ABI (autotools, hotplug/libudev disabled —
# there is no udev on Android) and passes -DWITH_PCSC=ON + PCSC_INCLUDE_DIRS/
# PCSC_LIBRARIES to the FreeRDP prebuilt step for whichever ABIs that
# succeeded on, so this default now matches that (same pattern as
# SYSTEMSGO_PRINT_BACKEND_AVAILABLE flipping to ON after PRINTER-CUPS FIX).
# Read by systemsgo_jni.c as SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE, and by
# AFreeRdpBridge.isSmartcardBackendAvailable for the UI toggle.
#
# CAVEAT (bigger than the CUPS one): even with libpcsclite linked in and the
# "rdpdr" smartcard device registered, FreeRDP's PC/SC client still needs an
# actual PC/SC *resource manager* to talk to — normally pcscd, a system
# daemon, which does not run inside a regular Android app's sandbox and has
# no root-free equivalent shipped by AOSP. Physical readers on Android are
# reached over USB (CCID class) via android.hardware.usb, or over NFC/SE via
# OMAPI — neither is something libpcsclite alone bridges automatically; a
# reader still needs an app-side IFD handler wired to Android's USB/NFC APIs
# for a plugged-in card to actually respond (see
# com.systemsgo.hex.security's smartcard package, which is where such a
# bridge would live — not yet implemented). Registering the device channel
# below without a live resource manager is still meaningful groundwork (it's
# what a future in-app pcscd-equivalent or OMAPI bridge plugs into, and it's
# also what a smart-card-logon flow that only needs an inserted PIV/CAC
# certificate for NLA — not full APDU passthrough — could build on first),
# but treat "smartcard redirection works end-to-end out of the box" as NOT
# true yet, unlike printer/webcam above.
option(SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE
    "FreeRDP prebuilt was built with a working smartcard-redirection backend (rdpdr smartcard device, MS-RDPESC)"
    ON)

# OpenSSL prebuilt — مطلوب فقط عندما يكون FreeRDP prebuilt موجوداً
# في CI: ANDROID_OPENSSL_ROOT مُصدَّر قبل gradlew
# في البناء المحلي بدون FreeRDP prebuilt: لا يُبنى الجسر الأصلي ولا حاجة لـ OpenSSL هنا،
# لكن تنبيه: لا يوجد "Kotlin fallback" يُشغِّل RDP بديلاً — RdpRemoteAdapter.kt
# يعتمد حصرياً على هذا الجسر الأصلي؛ بدونه تفشل اتصالات RDP (انظر SETUP.md).
if(DEFINED ANDROID_OPENSSL_ROOT AND NOT ANDROID_OPENSSL_ROOT STREQUAL "")
    set(OPENSSL_ABI_DIR "${ANDROID_OPENSSL_ROOT}/openssl-${ANDROID_ABI}")
else()
    set(OPENSSL_ABI_DIR "")
    message(STATUS "SystemsGo: ANDROID_OPENSSL_ROOT not set — native RDP bridge will be skipped (see SETUP.md)")
endif()

# ── الحالة 1: FreeRDP prebuilt موجود → بناء JNI bridge كامل ──────────
if(EXISTS "${FREERDP_ABI_DIR}/lib/libfreerdp3.so" OR
   EXISTS "${FREERDP_ABI_DIR}/lib/libfreerdp3.a")

    message(STATUS "SystemsGo: FreeRDP prebuilt found at ${FREERDP_ABI_DIR}")

    # OpenSSL
    add_library(OpenSSL::Crypto STATIC IMPORTED)
    set_target_properties(OpenSSL::Crypto PROPERTIES
        IMPORTED_LOCATION "${OPENSSL_ABI_DIR}/lib/libcrypto.a")

    add_library(OpenSSL::SSL STATIC IMPORTED)
    set_target_properties(OpenSSL::SSL PROPERTIES
        IMPORTED_LOCATION "${OPENSSL_ABI_DIR}/lib/libssl.a")

    # WinPR — السكربت الرسمي يبني .so
    add_library(winpr SHARED IMPORTED)
    set_target_properties(winpr PROPERTIES
        IMPORTED_LOCATION "${FREERDP_ABI_DIR}/lib/libwinpr3.so")

    # FreeRDP
    add_library(freerdp SHARED IMPORTED)
    set_target_properties(freerdp PROPERTIES
        IMPORTED_LOCATION "${FREERDP_ABI_DIR}/lib/libfreerdp3.so")

    add_library(freerdp-client SHARED IMPORTED)
    set_target_properties(freerdp-client PROPERTIES
        IMPORTED_LOCATION "${FREERDP_ABI_DIR}/lib/libfreerdp-client3.so")

    # JNI bridge
    # SERIAL-OVER-NETWORK FEATURE: systemsgo_serial_bridge.c implements the
    # native PTY <-> local-abstract-socket relay that lets serialRedirectMode
    # RAW_TCP/RFC_2217 hand FreeRDP's "serial" rdpdr device a real pty slave
    # path instead of only a physical /dev node — see that file's top-of-file
    # doc comment and com.systemsgo.hex.rdp.serial.SerialNetworkBridge's
    # class doc for the Kotlin side of this bridge. Built into the same
    # systemsgo_jni.so (not a separate library) so its JNI_OnLoad-registered
    # methods are found under the same System.loadLibrary("systemsgo_jni") call
    # AFreeRdpBridge already makes — no separate load needed.
    add_library(systemsgo_jni SHARED systemsgo_jni.c systemsgo_serial_bridge.c)

    # AUDIO-BACKEND FIX: surface the option above to systemsgo_jni.c as a plain
    # 0/1 macro (matching that file's #ifndef SYSTEMSGO_AUDIO_BACKEND_AVAILABLE
    # fallback, so a build that doesn't go through this option — e.g. an IDE
    # invoking the compiler directly — still defaults safely to OFF/0).
    if(SYSTEMSGO_AUDIO_BACKEND_AVAILABLE)
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_AUDIO_BACKEND_AVAILABLE=1)
    else()
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_AUDIO_BACKEND_AVAILABLE=0)
    endif()

    # PRINTER-REDIRECT FEATURE: same pattern as SYSTEMSGO_AUDIO_BACKEND_AVAILABLE
    # just above — surfaces the option to systemsgo_jni.c as a plain 0/1 macro.
    if(SYSTEMSGO_PRINT_BACKEND_AVAILABLE)
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_PRINT_BACKEND_AVAILABLE=1)
    else()
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_PRINT_BACKEND_AVAILABLE=0)
    endif()

    # WEBCAM-REDIRECT FEATURE: same pattern as SYSTEMSGO_PRINT_BACKEND_AVAILABLE
    # just above — surfaces the option to systemsgo_jni.c as a plain 0/1 macro.
    if(SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE)
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE=1)
    else()
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE=0)
    endif()

    # SMARTCARD-REDIRECT FEATURE: same pattern as SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE
    # just above — surfaces the option to systemsgo_jni.c as a plain 0/1 macro.
    if(SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE)
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE=1)
    else()
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE=0)
    endif()

    # CODEC-NEGOTIATION FEATURE: same pattern as the flags above — surfaces
    # SYSTEMSGO_H264_BACKEND_AVAILABLE / SYSTEMSGO_AV1_BACKEND_AVAILABLE to
    # systemsgo_jni.c as plain 0/1 macros. See those options' doc comments
    # above for what each gates.
    if(SYSTEMSGO_H264_BACKEND_AVAILABLE)
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_H264_BACKEND_AVAILABLE=1)
    else()
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_H264_BACKEND_AVAILABLE=0)
    endif()

    if(SYSTEMSGO_AV1_BACKEND_AVAILABLE)
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_AV1_BACKEND_AVAILABLE=1)
    else()
        target_compile_definitions(systemsgo_jni PRIVATE SYSTEMSGO_AV1_BACKEND_AVAILABLE=0)
    endif()

    target_include_directories(systemsgo_jni PRIVATE
        # FreeRDP 3.x installs headers under versioned subdirectories
        # (include/freerdp3/freerdp/*.h, include/winpr3/winpr/*.h) — this is
        # the official upstream layout (lets FreeRDP2/FreeRDP3 coexist), see
        # https://www.linuxfromscratch.org/blfs/view/git/xsoft/freerdp.html
        # ("Installed Directories: /usr/include/{freerdp3,...,winpr3}").
        # Pointing at ${FREERDP_ABI_DIR}/include alone (without the freerdp3/
        # winpr3 subfolders) causes: fatal error: 'freerdp/freerdp.h' file not found
        ${FREERDP_ABI_DIR}/include/freerdp3
        ${FREERDP_ABI_DIR}/include/winpr3
        ${OPENSSL_ABI_DIR}/include
    )

    target_link_libraries(systemsgo_jni
        freerdp-client
        freerdp
        winpr
        OpenSSL::SSL
        OpenSSL::Crypto
        android
        log
        z
    )

    # ARMV7-FIX: على armeabi-v7a فقط، WinPR/FreeRDP يستخدمان عمليات atomic
    # ذرية 8-بايت (__atomic_fetch_add_8 ...) التي لا يوفرها compiler-rt
    # ضمنياً على 32-بت ARM — يجب ربط libatomic.a الخاصة بـ NDK صريحاً، وإلا
    # يفشل الربط بخطأ "undefined reference to __atomic_fetch_add_4/8".
    # arm64-v8a/x86_64/x86 لا يحتاجون هذا (لديهم تعليمات atomic أصلية).
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        target_link_libraries(systemsgo_jni atomic)
    endif()

# ── الحالة 2: لا prebuilt → skip silently, لا جسر RDP أصلي متاح ─────────
else()
    message(STATUS "SystemsGo: FreeRDP prebuilt NOT found at ${FREERDP_ABI_DIR}")
    message(STATUS "SystemsGo: Skipping native bridge — RDP connections will fail until it is built (see SETUP.md). VNC/SSH are unaffected by this.")
    message(STATUS "SystemsGo: Run CI to build FreeRDP prebuilt automatically")
endif()

# ── RDP-SERVER-API FEATURE: systemsgo_server_jni (incoming RDP connections) ──
# Independent target from systemsgo_jni above — a SEPARATE .so, built only when
# libfreerdp-server3.so exists in the prebuilt (i.e. only once main.yml's
# "Build FreeRDP prebuilt" step has actually run with -DWITH_SERVER=ON — see
# that step's RDP-SERVER-API FEATURE comment). Same fail-soft shape as the
# "no FreeRDP prebuilt found yet" branch above: a stale/cached prebuilt from
# before that flag flip simply won't have this library, and this whole
# block is skipped rather than failing the build — the app just won't offer
# server functionality until CI rebuilds the prebuilt (cache key was bumped
# to force exactly that on the next run — see main.yml).
if(EXISTS "${FREERDP_ABI_DIR}/lib/libfreerdp-server3.so")
    message(STATUS "SystemsGo: libfreerdp-server3.so found — building systemsgo_server_jni (RDP-SERVER-API FEATURE)")

    add_library(freerdp-server SHARED IMPORTED)
    set_target_properties(freerdp-server PROPERTIES
        IMPORTED_LOCATION "${FREERDP_ABI_DIR}/lib/libfreerdp-server3.so")

    add_library(systemsgo_server_jni SHARED systemsgo_server_jni.c)

    target_include_directories(systemsgo_server_jni PRIVATE
        ${FREERDP_ABI_DIR}/include/freerdp3
        ${FREERDP_ABI_DIR}/include/winpr3
        ${OPENSSL_ABI_DIR}/include
    )

    target_link_libraries(systemsgo_server_jni
        freerdp-server
        freerdp
        winpr
        OpenSSL::SSL
        OpenSSL::Crypto
        android
        log
    )

    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        target_link_libraries(systemsgo_server_jni atomic)
    endif()
else()
    message(STATUS "SystemsGo: libfreerdp-server3.so NOT found at ${FREERDP_ABI_DIR} — skipping systemsgo_server_jni (RDP-SERVER-API FEATURE unavailable until CI rebuilds the FreeRDP prebuilt with -DWITH_SERVER=ON, see main.yml)")
endif()

# ── SMARTCARD-REDIRECT FEATURE: libpcsclite.so shim ─────────────────────
# Independent target from systemsgo_jni above — it has no dependency on the
# FreeRDP prebuilt at all (pure JNI + this repo's own pcsc_shim_types.h, see
# that file's header comment for why it doesn't need real PCSC-lite headers
# either), so it builds even in the "no FreeRDP prebuilt found yet" branch
# above. See pcsc_shim.c's top-of-file comment for the full picture of how
# this fits together with com.systemsgo.hex.smartcard.PcscUsbBridge/
# UsbCcidReader on the Kotlin side, and app/src/main/cpp/SETUP.md's
# SMARTCARD-REDIRECT FEATURE section for the architecture summary.
if(SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE)
    add_library(pcsclite_shim SHARED pcsc_shim/pcsc_shim.c)

    # OUTPUT_NAME (not the CMake target name above) controls the actual
    # file name Gradle packages into the APK: "libpcsclite.so" — the exact
    # SONAME real PCSC-lite ships as, and the exact name WinPR's smartcard
    # module dlopen()s at runtime (see pcsc_shim.c's top comment). The
    # CMake target name "pcsclite_shim" only exists so this doesn't
    # collide/confuse with the earlier (now largely superseded — see
    # SETUP.md) attempt at cross-compiling a *real* PCSC-lite from source
    # in main.yml; that cross-compiled static library, if present, is only
    # used for FreeRDP's own build-time headers now (WITH_PCSC=ON needs
    # winscard.h/pcsclite.h to compile against), not linked into anything
    # at runtime — this shim replaces the runtime half entirely.
    set_target_properties(pcsclite_shim PROPERTIES OUTPUT_NAME "pcsclite")

    target_include_directories(pcsclite_shim PRIVATE pcsc_shim)
    target_link_libraries(pcsclite_shim log)

    message(STATUS "SystemsGo: building libpcsclite.so shim (SMARTCARD-REDIRECT FEATURE, backed by PcscUsbBridge/UsbCcidReader)")
else()
    message(STATUS "SystemsGo: SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE=OFF — skipping libpcsclite.so shim, smart-card redirection unavailable this build")
endif()

# ── USB-REDIRECT FEATURE (Part 1/3): systemsgo_urbdrc_jni scaffold ──────────
# Kotlin-side groundwork (UsbRedirectionManager, UsbNativeBridge,
# UsbRedirectionSettingsScreen) is complete as of Part 1 — see
# PART_2_PROMPT.md for the native `android_udevman` IUDEVMAN/IUDEVICE
# backend (systemsgo_urbdrc_jni.c) this target is reserved for. Same
# fail-soft shape as the "no FreeRDP prebuilt found yet" branch far above:
# this whole block is a no-op (message only, no target declared) until
# that source file exists, so Part 1 alone never breaks the build.
#
# systemsgo_urbdrc_jni.c will need its own registration with FreeRDP's channel
# core via freerdp_client_add_dynamic_channel()/urbdrc's own
# freerdp_urbdrc_client_subsystem_entry-equivalent entry point — see
# systemsgo_jni.c's nativeConnect for the existing pattern of registering
# rdpdr/rdpsnd/audin channels this will follow for urbdrc.
option(SYSTEMSGO_USB_BACKEND_AVAILABLE
    "Build the native URBDRC (USB redirection) JNI bridge once systemsgo_urbdrc_jni.c exists (Part 2/3)"
    ON)

if(SYSTEMSGO_USB_BACKEND_AVAILABLE AND
   (EXISTS "${FREERDP_ABI_DIR}/lib/libfreerdp3.so" OR EXISTS "${FREERDP_ABI_DIR}/lib/libfreerdp3.a") AND
   EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/systemsgo_urbdrc_jni.c")

    message(STATUS "SystemsGo: systemsgo_urbdrc_jni.c found — building native URBDRC bridge (USB-REDIRECT FEATURE)")

    add_library(systemsgo_urbdrc_jni SHARED systemsgo_urbdrc_jni.c)

    target_include_directories(systemsgo_urbdrc_jni PRIVATE
        ${FREERDP_ABI_DIR}/include/freerdp3
        ${FREERDP_ABI_DIR}/include/winpr3
        ${OPENSSL_ABI_DIR}/include
    )

    target_link_libraries(systemsgo_urbdrc_jni
        freerdp-client
        freerdp
        winpr
        OpenSSL::SSL
        OpenSSL::Crypto
        android
        log
    )

    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        target_link_libraries(systemsgo_urbdrc_jni atomic)
    endif()
else()
    message(STATUS "SystemsGo: systemsgo_urbdrc_jni.c not present yet (or no FreeRDP prebuilt) — skipping native URBDRC bridge; USB redirection stays Android-side-only until Part 2 lands (see PART_2_PROMPT.md). UsbNativeBridge.isAvailable will read false and UsbRedirectionManager fails soft.")
endif()

# ── SPICE-PROTOCOL FEATURE (Part 3/N): systemsgo_spice_jni real bridge ──────
# Independent .so target, same fail-soft shape as every other optional
# native bridge above: only builds once BOTH (a) systemsgo_spice_jni.c exists
# in this directory and (b) a SPICE prebuilt (libspice-client-glib-2.0.so)
# has been placed under SPICE_ABI_DIR by main.yml's "Build SPICE prebuilt"
# step (Part 2/N). systemsgo_spice_jni.c itself now contains real
# SpiceSession/Main/Display/Inputs channel logic (Part 3/N) instead of the
# earlier Part 1/N scaffold — see that file's top-of-file doc comment for
# the thread model and a list of UNVERIFIED spice-client-glib API calls.
#
# systemsgo_spice_jni.c calls GLib/GObject functions directly (g_main_loop_*,
# g_signal_connect, g_object_set, ...), not just spice-client-glib's own
# API, so it must link against libglib-2.0/libgobject-2.0 explicitly —
# linking spice-client-glib alone is not guaranteed to re-export those
# symbols.
option(SYSTEMSGO_SPICE_BACKEND_AVAILABLE
    "Build the native SPICE (systemsgo_spice_jni) JNI bridge once both systemsgo_spice_jni.c and a SPICE prebuilt exist"
    ON)

if(SYSTEMSGO_SPICE_BACKEND_AVAILABLE AND
   EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/systemsgo_spice_jni.c" AND
   (EXISTS "${SPICE_ABI_DIR}/lib/libspice-client-glib-2.0.so" OR
    EXISTS "${SPICE_ABI_DIR}/lib/libspice-client-glib-2.0.a"))

    message(STATUS "SystemsGo: SPICE prebuilt + systemsgo_spice_jni.c found — building native SPICE bridge (SPICE-PROTOCOL FEATURE)")

    add_library(spice-client-glib SHARED IMPORTED)
    set_target_properties(spice-client-glib PROPERTIES
        IMPORTED_LOCATION "${SPICE_ABI_DIR}/lib/libspice-client-glib-2.0.so")

    add_library(glib-2.0 SHARED IMPORTED)
    set_target_properties(glib-2.0 PROPERTIES
        IMPORTED_LOCATION "${SPICE_ABI_DIR}/lib/libglib-2.0.so")

    add_library(gobject-2.0 SHARED IMPORTED)
    set_target_properties(gobject-2.0 PROPERTIES
        IMPORTED_LOCATION "${SPICE_ABI_DIR}/lib/libgobject-2.0.so")

    add_library(systemsgo_spice_jni SHARED systemsgo_spice_jni.c)

    target_include_directories(systemsgo_spice_jni PRIVATE
        ${SPICE_ABI_DIR}/include
        ${SPICE_ABI_DIR}/include/spice-client-glib-2.0
        ${SPICE_ABI_DIR}/include/glib-2.0
        ${SPICE_ABI_DIR}/lib/glib-2.0/include
    )

    target_link_libraries(systemsgo_spice_jni
        spice-client-glib
        gobject-2.0
        glib-2.0
        android
        log
    )
else()
    message(STATUS "SystemsGo: SPICE prebuilt not found at ${SPICE_ABI_DIR} (or systemsgo_spice_jni.c not present yet) — skipping native SPICE bridge; SPICE connections unavailable until CI's \"Build SPICE prebuilt\" step (Part 2/N) succeeds for this ABI.")
endif()

# ── MOSH-PROTOCOL FEATURE (Part 1/N scaffold + Part 3/N crypto slice) ────
# نفس الشكل بالضبط لبقية الجسور: .so مستقل. لكن خلافاً لتوقّع Part 1/N
# الأصلي، لم يعد هذا الهدف يحتاج mosh prebuilt (libmoshcore.a) ليُبنى:
# systemsgo_mosh_crypto.c (فك مفتاح الجلسة + AES-128-OCB) يحتاج OpenSSL فقط،
# وهو متاح أصلاً عبر OPENSSL_ABI_DIR أعلاه (نفس الذي يستخدمه FreeRDP) —
# راجع mosh/NOTES.md لماذا شريحة src/crypto من mosh لم تعد تحتاج مصدر mosh
# نفسه. الهدف لا يزال لا يبني منطق نقل UDP/SSP فعلي أو مخطط الرسائل —
# systemsgo_mosh_jni.c's nativeConnect يفك المفتاح فقط بهذه المرحلة (لا اتصال
# شبكي)، لذلك هذا الهدف يبقى مستقلاً تماماً عن MOSH_ABI_DIR/mosh-prebuilt
# حتى تلك الأجزاء (Part 2/N و باقي Part 3/N) تُبنى لاحقاً وتحتاج فعلاً
# libmoshcore.a. عند ذلك سيُضاف MOSH_ABI_DIR إلى هذا الهدف، لا تُستبدل هذه
# الكتلة بالكامل.
option(SYSTEMSGO_MOSH_BACKEND_AVAILABLE
    "Build the native Mosh (systemsgo_mosh_jni) JNI bridge crypto slice once systemsgo_mosh_jni.c/systemsgo_mosh_crypto.c exist and OpenSSL is available"
    ON)

if(SYSTEMSGO_MOSH_BACKEND_AVAILABLE AND
   EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/systemsgo_mosh_jni.c" AND
   EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/systemsgo_mosh_crypto.c" AND
   NOT OPENSSL_ABI_DIR STREQUAL "")

    message(STATUS "SystemsGo: systemsgo_mosh_jni.c + systemsgo_mosh_crypto.c + OpenSSL found — building native Mosh bridge, crypto slice only (MOSH-PROTOCOL FEATURE, see mosh/NOTES.md)")

    # OpenSSL::Crypto may already be defined above (inside the FreeRDP-found
    # branch); only define it here if that branch didn't run, so this target
    # doesn't need FreeRDP's prebuilt to exist at all.
    if(NOT TARGET OpenSSL::Crypto)
        add_library(OpenSSL::Crypto STATIC IMPORTED)
        set_target_properties(OpenSSL::Crypto PROPERTIES
            IMPORTED_LOCATION "${OPENSSL_ABI_DIR}/lib/libcrypto.a")
    endif()

    add_library(systemsgo_mosh_jni SHARED systemsgo_mosh_jni.c systemsgo_mosh_crypto.c)

    target_include_directories(systemsgo_mosh_jni PRIVATE
        ${OPENSSL_ABI_DIR}/include
    )

    target_link_libraries(systemsgo_mosh_jni
        OpenSSL::Crypto
        android
        log
    )
else()
    message(STATUS "SystemsGo: systemsgo_mosh_jni.c/systemsgo_mosh_crypto.c not present yet, or OPENSSL_ABI_DIR not set — skipping native Mosh bridge. See mosh/NOTES.md.")
endif()

# MOSH_ABI_DIR (Part 2/N, mosh-prebuilt/<ABI>/libmoshcore.a + libprotobuf-lite.a)
# is intentionally unused above — nothing builds against it yet. It stays
# defined (see the top of this file) so the variable name and directory
# layout are fixed before that CI step and the UDP-transport/message-schema
# code that consumes it are written, matching how FREERDP_ABI_DIR/SPICE_ABI_DIR
# were fixed ahead of their own prebuilt steps.
