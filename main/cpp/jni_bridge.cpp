/**
 * jni_bridge.cpp — Android JNI ↔ Inkling C ABI glue.
 *
 * Package: com.dictation.server → JNI mangling: com_dictation_server
 */
#include <jni.h>
#include <android/log.h>
#include <cstring>

#include "inkling/api.h"

#define LOG_TAG "InklingJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct ProgressCtx {
    JavaVM*   vm;
    jobject   listener;
    jmethodID on_progress_mid;
};

void progress_trampoline(const char* job_id, int stage, int percent,
                         void* userdata) {
    auto* ctx = static_cast<ProgressCtx*>(userdata);
    if (!ctx || !ctx->vm || !ctx->listener) return;

    JNIEnv* env = nullptr;
    bool need_detach = false;
    jint rc = ctx->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if (ctx->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        need_detach = true;
    } else if (rc != JNI_OK) {
        return;
    }

    jstring j_job = env->NewStringUTF(job_id ? job_id : "");
    env->CallVoidMethod(ctx->listener, ctx->on_progress_mid,
                        j_job, static_cast<jint>(stage), static_cast<jint>(percent));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(j_job);

    if (need_detach) ctx->vm->DetachCurrentThread();
}

void log_trampoline(int level, const char* tag, const char* msg, void* userdata) {
    (void)userdata;
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case INK_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case INK_LOG_INFO:  prio = ANDROID_LOG_INFO;  break;
        case INK_LOG_WARN:  prio = ANDROID_LOG_WARN;  break;
        case INK_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
    }
    __android_log_print(prio, tag ? tag : "inkling", "%s", msg ? msg : "");
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_dictation_server_InklingNative_nativeVersion(
        JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(ink_version());
}

JNIEXPORT jint JNICALL
Java_com_dictation_server_InklingNative_nativeConvert(
        JNIEnv* env, jclass /*clazz*/,
        jstring j_input_path, jstring j_output_path, jstring j_options_json,
        jstring j_job_id, jobject j_listener) {

    if (!j_input_path || !j_output_path || !j_options_json || !j_job_id) {
        LOGE("nativeConvert: NULL string argument");
        return INK_ERR_INVALID_OPTIONS;
    }

    const char* input_path  = env->GetStringUTFChars(j_input_path,  nullptr);
    const char* output_path = env->GetStringUTFChars(j_output_path, nullptr);
    const char* options     = env->GetStringUTFChars(j_options_json, nullptr);
    const char* job_id      = env->GetStringUTFChars(j_job_id,      nullptr);

    LOGI("nativeConvert input='%s' output='%s' job='%s'",
         input_path, output_path, job_id);

    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);

    ProgressCtx ctx{};
    ctx.vm = vm;
    ctx.listener = nullptr;
    ctx.on_progress_mid = nullptr;

    if (j_listener) {
        ctx.listener = env->NewGlobalRef(j_listener);
        jclass listener_cls = env->GetObjectClass(ctx.listener);
        ctx.on_progress_mid = env->GetMethodID(
            listener_cls, "onProgress", "(Ljava/lang/String;II)V");
        env->DeleteLocalRef(listener_cls);
        if (!ctx.on_progress_mid) {
            LOGE("nativeConvert: ProgressListener.onProgress not found");
            env->DeleteGlobalRef(ctx.listener);
            ctx.listener = nullptr;
        }
    }

    ink_status_t st = ink_convert(input_path, output_path, options, job_id,
                                  ctx.listener ? &progress_trampoline : nullptr,
                                  ctx.listener ? &ctx : nullptr,
                                  &log_trampoline, nullptr);

    if (ctx.listener) env->DeleteGlobalRef(ctx.listener);
    env->ReleaseStringUTFChars(j_input_path,   input_path);
    env->ReleaseStringUTFChars(j_output_path,  output_path);
    env->ReleaseStringUTFChars(j_options_json, options);
    env->ReleaseStringUTFChars(j_job_id,       job_id);

    return static_cast<jint>(st);
}

}  // extern "C"
