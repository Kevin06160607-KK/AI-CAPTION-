#include <jni.h>
#include <algorithm>
#include <string>
#include <thread>
#include <whisper.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_aitamil_livesubtitles_WhisperEngine_00024Companion_createNative(JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aitamil_livesubtitles_WhisperEngine_transcribeNative(JNIEnv *env, jobject, jlong handle,
                                                               jfloatArray samples, jstring lang) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");
    const jsize count = env->GetArrayLength(samples);
    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);
    const char *language = env->GetStringUTFChars(lang, nullptr);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, std::min(4, static_cast<int>(std::thread::hardware_concurrency())));
    params.no_context = true;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = language;
    params.detect_language = std::string(language) == "auto";
    whisper_full(ctx, params, audio, count);
    std::string output;
    for (int i = 0; i < whisper_full_n_segments(ctx); ++i) output += whisper_full_get_segment_text(ctx, i);
    env->ReleaseStringUTFChars(lang, language);
    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aitamil_livesubtitles_WhisperEngine_releaseNative(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}
