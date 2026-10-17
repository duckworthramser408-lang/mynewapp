#!/usr/bin/env python3
"""Optional HOST validation fixtures. Never called by the app or its build.
Photos are not redistributed in the APK/repository. Check original sources' terms.
"""
import hashlib
from pathlib import Path
import urllib.request

SAMPLES = {
    "grace.jpg": ("https://raw.githubusercontent.com/matplotlib/matplotlib/v3.10.1/lib/matplotlib/mpl-data/sample_data/grace_hopper.jpg", "a8ca6d734765703b09728ab47fe59f473d93ae3967fc24c7c0288c3c7adb7130"),
    "test_portrait": ("https://raw.githubusercontent.com/tensorflow/tfjs-models/master/face-landmarks-detection/test_data/portrait.jpg", "a6f11efaa834706db23f275b6115058fa87fc7f14362681e6abe14e82749de3e"),
    "lena.jpg": ("https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/lena.jpg", "7de7ed51a1594fff247f4cae2301eceacf5313d6011e37b4a4c8733f7bb72c07"),
}

def main():
    target = Path(__file__).resolve().parents[1] / ".build/beauty-research"
    target.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in SAMPLES.items():
        path = target / name
        data = path.read_bytes() if path.is_file() else urllib.request.urlopen(url, timeout=30).read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"Fixture changed: {name}")
        path.write_bytes(data)
        print(name, expected)

if __name__ == "__main__":
    main()
