#ifndef ANDROID_LOG_H
#define ANDROID_LOG_H
typedef enum { ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR } android_LogPriority;
int __android_log_print(int prio, const char* tag, const char* fmt, ...);
#endif
