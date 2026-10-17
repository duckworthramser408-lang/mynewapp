#include "../src/beauty_canonical.h"
#include "g23_beauty.h"
#include "g23_lut.h"
#include <cassert>
#include <cmath>
#include <cstring>
#include <iostream>
#include <limits>
#include <vector>
int main() {
    constexpr int w = 256, h = 256, stride = w * 4 + 8;
    std::vector<uint8_t> image(stride * h, 199);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            auto *p = image.data() + y * stride + x * 4;
            p[0] = uint8_t(100 + (x + y) % 30);
            p[1] = uint8_t(70 + (x * 3 + y) % 25);
            p[2] = uint8_t(60 + (x + y * 3) % 20);
            p[3] = 255;
        }
    const auto original = image;
    std::vector<float> points(1404);
    auto mesh = [&](float yaw, float roll) {
        float cy = std::cos(yaw), sy = std::sin(yaw), cr = std::cos(roll), sr = std::sin(roll);
        for (int i = 0; i < 468; i++) {
            auto *a = kBeautyCanonical[i];
            float rx = cy * a[0] + sy * a[2], rz = -sy * a[0] + cy * a[2];
            points[i * 3] = (128 + 8 * (cr * rx - sr * a[1])) / w;
            points[i * 3 + 1] = (125 + 8 * (sr * rx + cr * a[1])) / h;
            points[i * 3 + 2] = 8 * rz / w;
        }
    };
    mesh(0, 0);
    G23BeautySettings settings{1, 0, 0};
    G23BeautyReport r{};
    char error[256]{};
    // Mathematics fixtures, NOT fabricated model inference.
    points[93 * 3 + 1] += .02f;
    points[323 * 3 + 1] -= .015f;
    assert(g23_beautify(image.data(), w, h, stride, points.data(), 468, nullptr, 0, .99f, .005f,
                        &settings, &r, error, sizeof(error)) == 0);
    assert(r.applied & 1);
    assert(r.changed_pixels > 0);
    assert(r.max_displacement_px < 1);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++)
            assert(image[y * stride + x * 4 + 3] == 255);
        for (int x = w * 4; x < stride; x++)
            assert(image[y * stride + x] == 199);
    }
    assert(std::memcmp(image.data(), original.data(), stride * 20) == 0);
    // Rigid yaw estimate is independent of image roll.
    for (float roll : {0.f, .35f}) {
        mesh(.20f, roll);
        image = original;
        assert(g23_beautify(image.data(), w, h, stride, points.data(), 468, nullptr, 0, .99f, .005f,
                            &settings, &r, error, sizeof(error)) == 0);
        assert(std::abs(r.yaw_degrees - .20f * 57.2957795f) < .02f);
    }
    // Large yaw, overlap and unreliable landmarks skip instead of morphing.
    mesh(.7f, 0);
    image = original;
    assert(!g23_beautify(image.data(), w, h, stride, points.data(), 468, nullptr, 0, .99f, .005f,
                         &settings, &r, error, sizeof(error)));
    assert(image == original);
    mesh(0, 0);
    float other[] = {.25f, .25f, .75f, .75f};
    assert(!g23_beautify(image.data(), w, h, stride, points.data(), 468, other, 1, .99f, .005f,
                         &settings, &r, error, sizeof(error)));
    assert(image == original);
    assert(!g23_beautify(image.data(), w, h, stride, points.data(), 468, nullptr, 0, .5f, .005f,
                         &settings, &r, error, sizeof(error)));
    assert(image == original);
    points[7] = std::numeric_limits<float>::quiet_NaN();
    assert(g23_beautify(image.data(), w, h, stride, points.data(), 468, nullptr, 0, .99f, .005f,
                        &settings, &r, error, sizeof(error)));
    assert(image == original);
    mesh(0, 0);
    settings.folds = 2;
    assert(g23_beautify(image.data(), w, h, stride, points.data(), 468, nullptr, 0, .99f, .005f,
                        &settings, &r, error, sizeof(error)));
    assert(image == original);
    std::cout << "beauty pose/roll, bounded real displacement, alpha/padding, overlap, yaw and "
                 "invalid input passed\n";
}
