#include "g23_lut.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>

extern "C" int g23_apply_lut(uint8_t *rgba, int width, int height, size_t stride,
                             const float *table, size_t count, int size, const float lo[3],
                             const float hi[3], char *error, size_t error_size) {
    auto fail = [&](const char *message) {
        if (error && error_size)
            std::snprintf(error, error_size, "%s", message);
        return 1;
    };
    if (!rgba || !table || !lo || !hi || size < 2 || size > 65 ||
        count != size_t(size) * size * size * 3 || width <= 0 || height <= 0 ||
        int64_t(width) * height > 100000000 || stride < size_t(width) * 4 ||
        size_t(height) > std::numeric_limits<size_t>::max() / stride)
        return fail("LUT 数据或图像尺寸无效");
    for (int c = 0; c < 3; ++c)
        if (!std::isfinite(lo[c]) || !std::isfinite(hi[c]) || !(lo[c] < hi[c]))
            return fail("LUT 输入范围无效");
    for (size_t i = 0; i < count; ++i)
        if (!std::isfinite(table[i]) || std::abs(table[i]) > 64.f)
            return fail("LUT 包含无效数值");
    // Only 256 possible channel values: normalize once for the whole image.
    float coords[3][256];
    for (int c = 0; c < 3; ++c)
        for (int v = 0; v < 256; ++v)
            coords[c][v] = float(
                std::clamp((v / 255.0 - lo[c]) / (double(hi[c]) - lo[c]), 0.0, 1.0) * (size - 1));
    const int gs = size * 3, bs = size * size * 3;
    for (int y = 0; y < height; ++y) {
        uint8_t *p = rgba + size_t(y) * stride;
        for (int x = 0; x < width; ++x, p += 4) {
            const float r = coords[0][p[0]], g = coords[1][p[1]], b = coords[2][p[2]];
            const int ri = std::min(size - 2, int(r)), gi = std::min(size - 2, int(g)),
                      bi = std::min(size - 2, int(b));
            const float fr = r - ri, fg = g - gi, fb = b - bi;
            const float *v = table + bi * bs + gi * gs + ri * 3;
            for (int c = 0; c < 3; ++c) {
                auto mix = [](float a, float z, float t) { return a + (z - a) * t; };
                const float front =
                    mix(mix(v[c], v[3 + c], fr), mix(v[gs + c], v[gs + 3 + c], fr), fg);
                const float back = mix(mix(v[bs + c], v[bs + 3 + c], fr),
                                       mix(v[bs + gs + c], v[bs + gs + 3 + c], fr), fg);
                p[c] = uint8_t(std::lround(std::clamp(mix(front, back, fb), 0.f, 1.f) * 255.f));
            }
        }
    }
    return 0;
}
