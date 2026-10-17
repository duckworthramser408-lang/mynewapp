#include "g23_lut.h"
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <limits>
#include <vector>
int main() {
    std::vector<float> table;
    for (int b = 0; b < 2; ++b)
        for (int g = 0; g < 2; ++g)
            for (int r = 0; r < 2; ++r) {
                table.push_back(float(b));
                table.push_back(float(r));
                table.push_back(float(g));
            }
    const float lo[3] = {0, 0, 0}, hi[3] = {1, 1, 1};
    char error[256]{};
    std::array<uint8_t, 16> image{10, 30,  90,  127, 55, 55, 55, 55,
                                  80, 130, 240, 255, 55, 55, 55, 55};
    assert(g23_apply_lut(image.data(), 1, 2, 8, table.data(), table.size(), 2, lo, hi, error,
                         sizeof(error)) == 0);
    assert(image[0] == 90 && image[1] == 10 && image[2] == 30 && image[3] == 127 && image[4] == 55);
    assert(image[8] == 240 && image[9] == 80 && image[10] == 130 && image[11] == 255 &&
           image[15] == 55);
    auto original = image;
    // Strength mixes encoded sRGB once, with alpha and stride padding unchanged.
    for (float strength : {0.f, .5f, 1.f}) {
        image = original;
        assert(!g23_apply_lut_strength(image.data(), 1, 2, 8, table.data(), table.size(), 2, lo, hi,
                                       strength, error, sizeof(error)));
        for (int row : {0, 8}) {
            const int from[] = {2, 0, 1};
            for (int c = 0; c < 3; c++)
                assert(std::abs(int(image[row + c]) -
                                std::lround(original[row + c] * (1 - strength) +
                                            original[row + from[c]] * strength)) <= 1);
            for (int c = 3; c < 8; c++)
                assert(image[row + c] == original[row + c]);
        }
    }
    image = original;
    for (float strength : {-1.f, 1.01f, std::numeric_limits<float>::quiet_NaN()}) {
        assert(g23_apply_lut_strength(image.data(), 1, 2, 8, table.data(), table.size(), 2, lo, hi,
                                      strength, error, sizeof(error)));
        assert(image == original);
    }
    for (size_t count = 0; count < table.size(); ++count) {
        assert(g23_apply_lut(image.data(), 1, 2, 8, table.data(), count, 2, lo, hi, error,
                             sizeof(error)) == 1);
        assert(image == original);
    }
    for (int size : {-100, 0, 1, 66, std::numeric_limits<int>::max()})
        assert(g23_apply_lut(image.data(), 1, 2, 8, table.data(), table.size(), size, lo, hi, error,
                             sizeof(error)) == 1);
    table.back() = std::numeric_limits<float>::quiet_NaN();
    assert(g23_apply_lut(image.data(), 1, 2, 8, table.data(), table.size(), 2, lo, hi, error,
                         sizeof(error)) == 1);
    assert(image == original);
    assert(g23_apply_lut(nullptr, 1, 1, 4, table.data(), table.size(), 2, lo, hi, error,
                         sizeof(error)) == 1);
    std::puts("LUT channel order, alpha/padding, truncated grids and malformed inputs passed");
}
