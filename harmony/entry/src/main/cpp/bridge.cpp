#include "g23.h"
#include "g23_lut.h"
#include "napi/native_api.h"
#include <cstring>
#include <new>
#include <string>
#include <vector>
struct Work {
    napi_async_work work{};
    napi_deferred promise{};
    const uint8_t *input = nullptr;
    std::vector<uint8_t> *output = nullptr;
    napi_ref source{};
    std::string json;
    int w = 0, h = 0;
    std::vector<float> cube;
    int cubeSize = 0;
    float lo[3]{}, hi[3]{};
    char error[256]{};
    int code = 1;
};
static bool String(napi_env env, napi_value v, std::string &out) {
    size_t n = 0;
    if (napi_get_value_string_utf8(env, v, nullptr, 0, &n) != napi_ok || n > 65536)
        return false;
    try {
        out.resize(n + 1);
    } catch (...) {
        return false;
    }
    if (napi_get_value_string_utf8(env, v, out.data(), n + 1, &n) != napi_ok)
        return false;
    out.resize(n);
    return true;
}
static napi_value Render(napi_env env, napi_callback_info info) {
    size_t argc = 8;
    napi_value args[8];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *w = new (std::nothrow) Work;
    if (!w) {
        napi_throw_error(env, nullptr, "memory allocation failed");
        return nullptr;
    }
    void *bytes = nullptr;
    size_t count = 0;
    if ((argc != 4 && argc != 8) ||
        napi_get_arraybuffer_info(env, args[0], &bytes, &count) != napi_ok ||
        napi_get_value_int32(env, args[1], &w->w) != napi_ok ||
        napi_get_value_int32(env, args[2], &w->h) != napi_ok || w->w <= 0 || w->h <= 0 ||
        int64_t(w->w) * w->h > 100000000 || count != size_t(w->w) * w->h * 4 ||
        !String(env, args[3], w->json)) {
        delete w;
        napi_throw_type_error(env, nullptr, "RGBA buffer, dimensions and JSON required");
        return nullptr;
    }
    if (argc == 8) {
        void *table = nullptr, *minimum = nullptr, *maximum = nullptr;
        size_t tableBytes = 0, loBytes = 0, hiBytes = 0;
        if (napi_get_value_int32(env, args[5], &w->cubeSize) != napi_ok || w->cubeSize < 2 ||
            w->cubeSize > 65 ||
            napi_get_arraybuffer_info(env, args[4], &table, &tableBytes) != napi_ok ||
            tableBytes != size_t(w->cubeSize) * w->cubeSize * w->cubeSize * 3 * sizeof(float) ||
            napi_get_arraybuffer_info(env, args[6], &minimum, &loBytes) != napi_ok ||
            loBytes != 3 * sizeof(float) ||
            napi_get_arraybuffer_info(env, args[7], &maximum, &hiBytes) != napi_ok ||
            hiBytes != 3 * sizeof(float)) {
            delete w;
            napi_throw_type_error(env, nullptr, "LUT 网格或输入范围无效");
            return nullptr;
        }
        try {
            w->cube.resize(tableBytes / sizeof(float));
            std::memcpy(w->cube.data(), table, tableBytes);
            std::memcpy(w->lo, minimum, loBytes);
            std::memcpy(w->hi, maximum, hiBytes);
        } catch (...) {
            delete w;
            napi_throw_error(env, nullptr, "LUT 内存分配失败");
            return nullptr;
        }
    }
    try {
        w->input = static_cast<uint8_t *>(bytes);
        w->output = new std::vector<uint8_t>(count);
    } catch (...) {
        delete w->output;
        delete w;
        napi_throw_error(env, nullptr, "memory allocation failed");
        return nullptr;
    }
    if (napi_create_reference(env, args[0], 1, &w->source) != napi_ok) {
        delete w->output;
        delete w;
        napi_throw_error(env, nullptr, "could not retain source buffer");
        return nullptr;
    }
    napi_value promise, name;
    if (napi_create_promise(env, &w->promise, &promise) != napi_ok ||
        napi_create_string_utf8(env, "g23_render", NAPI_AUTO_LENGTH, &name) != napi_ok) {
        napi_delete_reference(env, w->source);
        delete w->output;
        delete w;
        napi_throw_error(env, nullptr, "could not allocate promise");
        return nullptr;
    }
    napi_status status = napi_create_async_work(
        env, nullptr, name,
        [](napi_env, void *data) {
            auto *w = static_cast<Work *>(data);
            w->code = g23_render(w->input, w->output->data(), w->w, w->h, w->json.c_str(), w->error,
                                 sizeof(w->error));
            if (!w->code && !w->cube.empty())
                w->code = g23_apply_lut(w->output->data(), w->w, w->h, size_t(w->w) * 4,
                                        w->cube.data(), w->cube.size(), w->cubeSize, w->lo, w->hi,
                                        w->error, sizeof(w->error));
        },
        [](napi_env env, napi_status status, void *data) {
            auto *w = static_cast<Work *>(data);
            napi_value result;
            if (status == napi_ok && !w->code) {
                if (napi_create_external_arraybuffer(
                        env, w->output->data(), w->output->size(),
                        [](napi_env, void *, void *hint) {
                            delete static_cast<std::vector<uint8_t> *>(hint);
                        },
                        w->output, &result) == napi_ok) {
                    w->output = nullptr;
                    napi_resolve_deferred(env, w->promise, result);
                } else {
                    napi_value msg;
                    napi_create_string_utf8(env, "result allocation failed", NAPI_AUTO_LENGTH,
                                            &msg);
                    napi_create_error(env, nullptr, msg, &result);
                    napi_reject_deferred(env, w->promise, result);
                }
            } else {
                napi_value msg;
                napi_create_string_utf8(env, w->error[0] ? w->error : "native render failed",
                                        NAPI_AUTO_LENGTH, &msg);
                napi_create_error(env, nullptr, msg, &result);
                napi_reject_deferred(env, w->promise, result);
            }
            napi_delete_async_work(env, w->work);
            napi_delete_reference(env, w->source);
            delete w->output;
            delete w;
        },
        w, &w->work);
    if (status != napi_ok || napi_queue_async_work(env, w->work) != napi_ok) {
        if (w->work)
            napi_delete_async_work(env, w->work);
        napi_delete_reference(env, w->source);
        delete w->output;
        delete w;
        napi_throw_error(env, nullptr, "could not schedule render");
        return nullptr;
    }
    return promise;
}
static napi_value Validate(napi_env env, napi_callback_info info) {
    size_t n = 1;
    napi_value arg;
    napi_get_cb_info(env, info, &n, &arg, nullptr, nullptr);
    std::string s;
    uint8_t in[4] = {100, 100, 100, 255}, out[4];
    char error[256]{};
    if (n != 1 || !String(env, arg, s)) {
        napi_throw_type_error(env, nullptr, "JSON required");
        return nullptr;
    }
    if (g23_render(in, out, 1, 1, s.c_str(), error, sizeof(error)))
        napi_throw_error(env, nullptr, error);
    napi_value v;
    napi_get_undefined(env, &v);
    return v;
}
static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor d[] = {
        {"render", nullptr, Render, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"validate", nullptr, Validate, nullptr, nullptr, nullptr, napi_default, nullptr}};
    napi_define_properties(env, exports, 2, d);
    return exports;
}
static napi_module module = {1, 0, nullptr, Init, "g23", nullptr, {0}};
extern "C" __attribute__((constructor)) void RegisterG23() { napi_module_register(&module); }
