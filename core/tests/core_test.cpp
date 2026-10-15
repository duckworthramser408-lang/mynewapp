#include "g23.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>
int main(int argc, char **argv) {
    if (argc < 2) {
        std::cerr << "default_parameters.json required\n";
        return 2;
    }
    std::ifstream stream(argv[1]);
    std::string json((std::istreambuf_iterator<char>(stream)), {});
    if (json.empty())
        return 2;
    std::vector<uint8_t> input(513 * 259 * 4), output(input.size());
    for (size_t i = 0; i < input.size(); ++i)
        input[i] = (i % 4 == 3) ? 255 : uint8_t(i * 37);
    char error[256]{};
    if (g23_render(input.data(), output.data(), 513, 259, json.c_str(), error, sizeof(error)) ||
        input != output) {
        std::cerr << error << " neutral mismatch\n";
        return 1;
    }
    const char *invalid[] = {"",           "{",    "{\"a\":", "{\"a\":\"\\", "[[]",
                             "{\"a\":1e}", "null", "true",    "{\"a\":0x1}", "{\"a\":1,}"};
    for (auto *text : invalid)
        if (g23_render(input.data(), output.data(), 1, 1, text, error, sizeof(error)) == 0)
            return 1;
    // Deterministic malformed-input corpus exercises parser bounds under ASan/UBSan.
    uint32_t seed = 7;
    for (int n = 0; n < 10000; ++n) {
        std::string text;
        for (int k = 0; k < n % 128; ++k) {
            seed = 1664525 * seed + 1013904223;
            text += char(1 + seed % 126);
        }
        g23_render(input.data(), output.data(), 1, 1, text.c_str(), error, sizeof(error));
    }
    std::cout << "neutral multi-tile image + 10000 invalid input cases passed\n";
    return 0;
}
