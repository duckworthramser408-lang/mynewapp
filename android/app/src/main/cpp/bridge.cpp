#include "g23.h"
#include <android/bitmap.h>
#include <cstring>
#include <jni.h>
#include <vector>
extern "C" JNIEXPORT void JNICALL Java_lab_g23_NativeBridge_renderInto(JNIEnv *env, jobject,
                                                                       jobject src, jobject dst,
                                                                       jstring json) {
    AndroidBitmapInfo a{}, b{};
    auto fail = [&](const char *s) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), s);
    };
    if (AndroidBitmap_getInfo(env, src, &a) || AndroidBitmap_getInfo(env, dst, &b) ||
        a.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || b.format != a.format || a.width != b.width ||
        a.height != b.height) {
        fail("RGBA8888 bitmap dimensions required");
        return;
    }
    void *in = nullptr, *out = nullptr;
    if (AndroidBitmap_lockPixels(env, src, &in)) {
        fail("source lock failed");
        return;
    }
    if (AndroidBitmap_lockPixels(env, dst, &out)) {
        AndroidBitmap_unlockPixels(env, src);
        fail("output lock failed");
        return;
    }
    const char *s = env->GetStringUTFChars(json, nullptr);
    if (!s) {
        AndroidBitmap_unlockPixels(env, dst);
        AndroidBitmap_unlockPixels(env, src);
        return; // JNI already has an allocation exception pending.
    }
    char error[256]{};
    int status = 1;
    try {
        std::vector<uint8_t> packedIn, packedOut;
        const uint8_t *input = static_cast<uint8_t *>(in);
        uint8_t *output = static_cast<uint8_t *>(out);
        if (a.stride != a.width * 4) {
            packedIn.resize(size_t(a.width) * a.height * 4);
            for (unsigned y = 0; y < a.height; ++y)
                std::memcpy(packedIn.data() + size_t(y) * a.width * 4, input + size_t(y) * a.stride,
                            a.width * 4);
            input = packedIn.data();
        }
        if (b.stride != b.width * 4) {
            packedOut.resize(size_t(b.width) * b.height * 4);
            output = packedOut.data();
        }
        status = g23_render(input, output, a.width, a.height, s, error, sizeof(error));
        if (!status && !packedOut.empty())
            for (unsigned y = 0; y < b.height; ++y)
                std::memcpy(static_cast<uint8_t *>(out) + size_t(y) * b.stride,
                            output + size_t(y) * b.width * 4, b.width * 4);
    } catch (...) {
        std::strcpy(error, "native allocation failed");
    }
    env->ReleaseStringUTFChars(json, s);
    AndroidBitmap_unlockPixels(env, dst);
    AndroidBitmap_unlockPixels(env, src);
    if (status)
        fail(error);
}

#include "g23_lut.h"
extern "C" JNIEXPORT void JNICALL Java_lab_g23_NativeBridge_applyCube(
    JNIEnv *env, jobject, jobject bitmap, jfloatArray table, jint size, jfloatArray minimum,
    jfloatArray maximum, jfloat intensity) {
    auto fail = [&](const char *s) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), s);
    };
    AndroidBitmapInfo info{};
    if (!bitmap || !table || !minimum || !maximum || env->GetArrayLength(minimum) != 3 ||
        env->GetArrayLength(maximum) != 3 || AndroidBitmap_getInfo(env, bitmap, &info) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        fail("LUT 需要有效的标准色彩图像与网格");
        return;
    }
    const jsize count = env->GetArrayLength(table);
    jfloat *values = env->GetFloatArrayElements(table, nullptr);
    if (!values)
        return;
    jfloat *lo = env->GetFloatArrayElements(minimum, nullptr);
    if (!lo) {
        env->ReleaseFloatArrayElements(table, values, JNI_ABORT);
        return;
    }
    jfloat *hi = env->GetFloatArrayElements(maximum, nullptr);
    if (!hi) {
        env->ReleaseFloatArrayElements(minimum, lo, JNI_ABORT);
        env->ReleaseFloatArrayElements(table, values, JNI_ABORT);
        return;
    }
    void *pixels = nullptr;
    char error[256]{};
    int code = 1;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0) {
        code = g23_apply_lut_strength(static_cast<uint8_t *>(pixels), info.width, info.height,
                                      info.stride, values, count, size, lo, hi, intensity, error,
                                      sizeof(error));
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    env->ReleaseFloatArrayElements(maximum, hi, JNI_ABORT);
    env->ReleaseFloatArrayElements(minimum, lo, JNI_ABORT);
    env->ReleaseFloatArrayElements(table, values, JNI_ABORT);
    if (code)
        fail(error[0] ? error : "无法读取图像像素");
}

#include "g23_beauty.h"
#include <sys/resource.h>
extern "C" JNIEXPORT jlong JNICALL Java_lab_g23_NativeBridge_peakRssKiB(JNIEnv *, jobject) {
    struct rusage value{};
    return getrusage(RUSAGE_SELF, &value) == 0 ? value.ru_maxrss : 0;
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_lab_g23_NativeBridge_beautify(
    JNIEnv *env, jobject, jobject bitmap, jfloatArray landmarks, jfloatArray others,
    jfloat presence, jfloat flipError, jfloatArray controls) {
    auto fail = [&](const char *s) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), s);
    };
    AndroidBitmapInfo info{};
    if (!bitmap || !landmarks || !others || !controls || env->GetArrayLength(landmarks) != 1404 ||
        env->GetArrayLength(controls) != 3 || env->GetArrayLength(others) % 4 ||
        env->GetArrayLength(others) > 256 || AndroidBitmap_getInfo(env, bitmap, &info) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        fail("美颜输入无效");
        return nullptr;
    }
    // Allocate Java result BEFORE committing pixels, so an allocation failure still falls back
    // safely.
    jfloatArray result = env->NewFloatArray(12);
    if (!result)
        return nullptr;
    float points[1404], boxes[256], params[3];
    env->GetFloatArrayRegion(landmarks, 0, 1404, points);
    env->GetFloatArrayRegion(controls, 0, 3, params);
    jsize n = env->GetArrayLength(others);
    if (n)
        env->GetFloatArrayRegion(others, 0, n, boxes);
    if (env->ExceptionCheck())
        return nullptr;
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels)) {
        fail("无法读取美颜图像");
        return nullptr;
    }
    G23BeautySettings settings{params[0], params[1], params[2]};
    G23BeautyReport report{};
    char error[256]{};
    int code = g23_beautify(static_cast<uint8_t *>(pixels), info.width, info.height, info.stride,
                            points, 468, boxes, n / 4, presence, flipError, &settings, &report,
                            error, sizeof(error));
    AndroidBitmap_unlockPixels(env, bitmap);
    if (code) {
        fail(error);
        return nullptr;
    }
    float values[] = {float(report.applied),
                      float(report.reason),
                      report.yaw_degrees,
                      report.pitch_degrees,
                      report.pose_error,
                      report.max_displacement_px,
                      report.fold_confidence,
                      float(report.geometry_ms),
                      float(report.folds_ms),
                      float(report.scratch_bytes),
                      float(report.changed_pixels),
                      float(Java_lab_g23_NativeBridge_peakRssKiB(env, nullptr))};
    env->SetFloatArrayRegion(result, 0, 12, values);
    return result;
}
