#include "g23.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include <iterator>
#include <sys/resource.h>
#include <vector>
int main(int argc, char **argv) {
    if (argc != 4)
        return 2;
    int w = std::stoi(argv[2]), h = std::stoi(argv[3]);
    std::ifstream f(argv[1]);
    std::string p((std::istreambuf_iterator<char>(f)), {});
    std::vector<uint8_t> in(size_t(w) * h * 4), out(in.size());
    for (size_t i = 0; i < in.size(); ++i)
        in[i] = i % 4 == 3 ? 255 : uint8_t(i * 19);
    char error[256];
    auto start = std::chrono::steady_clock::now();
    int code = g23_render(in.data(), out.data(), w, h, p.c_str(), error, sizeof(error));
    auto end = std::chrono::steady_clock::now();
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    std::cout << "{\"width\":" << w << ",\"height\":" << h
              << ",\"seconds\":" << std::chrono::duration<double>(end - start).count()
              << ",\"peak_rss_kib\":" << usage.ru_maxrss << ",\"status\":" << code << "}\n";
    if (code)
        std::cerr << error << '\n';
    return code;
}
