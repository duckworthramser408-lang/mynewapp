#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build
c++ -std=c++17 -O2 -Werror -Wall -Wextra -Wpedantic -shared -fPIC -Icore/include core/src/g23.cpp -o .build/libg23.so
python3 -m unittest discover -s tests -v
c++ -std=c++17 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -Icore/include core/src/g23.cpp core/tests/core_test.cpp -o .build/core-sanitized
ASAN_OPTIONS=detect_leaks=1 .build/core-sanitized protocol/default_parameters.json
