#ifndef JNI_H
#define JNI_H
typedef int jint; typedef long jlong; typedef unsigned char jboolean;
typedef void* jobject; typedef jobject jclass; typedef jobject jstring;
typedef jobject jbyteArray; typedef jobject jobjectArray; typedef signed char jbyte;
typedef long jsize; typedef void* jmethodID;
#define JNI_VERSION_1_6 0x00010006
#define JNI_OK 0
#define JNI_EDETACHED (-2)
#define JNIEXPORT
#define JNICALL
typedef struct JNINativeInterface {
  void* dummy;
  jclass (*FindClass)(void*, const char*);
  void (*ExceptionClear)(void*);
  void* (*NewGlobalRef)(void*, void*);
  void (*DeleteLocalRef)(void*, void*);
  jmethodID (*GetStaticMethodID)(void*, jclass, const char*, const char*);
  jboolean (*CallStaticBooleanMethod)(void*, jclass, jmethodID, ...);
  void* (*CallStaticObjectMethod)(void*, jclass, jmethodID, ...);
  void (*CallStaticVoidMethod)(void*, jclass, jmethodID, ...);
  jint (*CallStaticIntMethod)(void*, jclass, jmethodID, ...);
  jsize (*GetArrayLength)(void*, jobject);
  void (*GetByteArrayRegion)(void*, jobject, jsize, jsize, jbyte*);
  void (*SetByteArrayRegion)(void*, jobject, jsize, jsize, const jbyte*);
  jobject (*NewByteArray)(void*, jsize);
  jstring (*NewStringUTF)(void*, const char*);
  const char* (*GetStringUTFChars)(void*, jstring, void*);
  void (*ReleaseStringUTFChars)(void*, jstring, const char*);
  jobject (*GetObjectArrayElement)(void*, jobject, jsize);
} JNINativeInterface;
typedef const JNINativeInterface* JNIEnv;
typedef struct JNIInvokeInterface {
  void* dummy;
  jint (*GetEnv)(void*, void**, jint);
  jint (*AttachCurrentThread)(void*, JNIEnv**, void*);
  jint (*DetachCurrentThread)(void*);
} JNIInvokeInterface;
typedef const JNIInvokeInterface* JavaVM;
#endif
