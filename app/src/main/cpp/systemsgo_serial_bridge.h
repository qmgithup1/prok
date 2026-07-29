/*
 * SERIAL-OVER-NETWORK FEATURE — see systemsgo_serial_bridge.c's top-of-file
 * doc comment for the full picture. This header only exists so the two
 * JNIEXPORT entry points are visible if another translation unit ever
 * needs to reference them directly (currently none does — systemsgo_jni.c
 * doesn't call into this file; Kotlin calls both entry points directly via
 * JNI's normal dynamic symbol lookup, same as every other `external fun`
 * in AFreeRdpBridge.kt).
 */
#ifndef SYSTEMSGO_SERIAL_BRIDGE_H
#define SYSTEMSGO_SERIAL_BRIDGE_H

#include <jni.h>

JNIEXPORT jlong JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSerialBridgeOpen(
    JNIEnv* env, jobject thiz,
    jstring jLocalSocketName, jobject jKotlinBridge, jobjectArray jSlavePathOut);

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSerialBridgeClose(
    JNIEnv* env, jobject thiz, jlong jBridgeHandle);

#endif /* SYSTEMSGO_SERIAL_BRIDGE_H */
