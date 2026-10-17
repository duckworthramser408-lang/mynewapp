# 构建与运行

## Android

- Android Studio，JDK 17，Gradle 8.9，AGP 8.7.3，Kotlin 2.0.21。
- compile/target API 35；min API 28。Build Tools 35.0.0、NDK 27.0.12077973、CMake 3.22.1。
- CameraX 1.5.1、WorkManager 2.10.0、LiteRT 1.4.1；arm64-v8a 真机和 x86_64 开发构建。

打开 `android/`，让 Android Studio 设置 SDK 路径；或设置 ANDROID_HOME。release 签名不存入仓库。

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n lab.g23/.MainActivity
```

云环境使用 README 中的 bootstrap/env/android_build 命令；完整 JDK 是必要条件，基础镜像的精简 JRE 没有 javac/jlink。工具与依赖官方下载校验记录在 `tools/toolchains.json`，代理使用系统 CA。

手机允许相机权限。验证连续拍摄、相册冲洗占位、滑线对比、原片／胶片保存和删除。构建通过不能代替真机检查。

## HarmonyOS 原生状态（API 26 目标尚未验证）

此云环境没有 DevEco Studio、HarmonyOS SDK、hvigor、ohpm、HDC 或可连接的鸿蒙真机。**未编译 HAP，未声明鸿蒙相机运行成功。**

用户目标为 HarmonyOS 7.0 / API 26，DevEco Studio 5.0.3.800 或更新版本。现已收到用户 Windows SDK 元数据：API 26 Release / 26.0.0.105，位于 `E:\DevEco Studio\sdk\default`；云端仍没有 SDK 本体。原生美颜本轮尚未实现，详见 [BEAUTY.zh.md](BEAUTY.zh.md)。下面是既有 LUT 相机源码的环境恢复步骤，不能当成 API 26 构建通过。

1. 在安装了目标 HarmonyOS SDK 的 DevEco Studio 中打开 `harmony/`。
2. 项目现已显式指定编译／目标 API 26，最低兼容声明保留 API 12 / 5.0.0；在实际 SDK 中核验产品配置、Stage 模型与 Native C++ 工具链。同步 hvigor/ohpm。
3. 在 IDE 配置实际产品和调试签名。签名证书及设备授权留在本机，不提交源码。
4. 运行 entry/default。IDE 提供的 hvigor 命令行环境可执行 `hvigorw --mode module -p module=entry@default -p product=default assembleHap`；仓库不伪造 SDK wrapper 或签名文件，使用 IDE 对应工具。
5. 逐项完成 `ACCEPTANCE.md` 真机清单，特别确认 photoAvailable/JPEG、PixelMap 方向和色彩空间、TaskPool／N-API、冲洗与相机并行、删除竞态以及 SaveButton 写入系统相册。

已提供 Windows 一次性检查／构建入口：见 [本机 API 26 操作说明](HARMONY_WINDOWS.zh.md)。脚本可回传实际 SDK 声明和工具状态，无需重复寻找 SDK 路径。

参考的是 OpenHarmony 官方 Camera Kit / Image Kit API 文档，API 符号经过文档核对，但不等同于 HarmonyOS SDK 编译。若目标 SDK 版本的接口发生变化，应修正并在该 SDK 编译后更新验收状态。

## 主机算法和数据测试

```bash
cd /workspace/mynewapp
source tools/env.sh
./tools/test.sh
# 另一条已支持的 CMake 路径
cmake -S core -B .build/cmake -G Ninja -DG23_TESTS=ON
cmake --build .build/cmake -j2
ctest --test-dir .build/cmake --output-on-failure
```

测试图片是程序生成的编解码测试夹具，仅用于图像算法和文件协议验证，不作为已完成真实相机拍摄的证据。手机真实拍摄测试需要设备。

## 0.4 验证与性能

`testDebugUnitTest` 使用 Robolectric 4.14.1 / API 35 原生图形，离线读取工作区内的固定 instrumented SDK。输出 `android/app/build/reports/ui/` 中的相机、胶卷选择器、冲洗占位与滑线对比渲染。它们不是手机拍摄证据。

上传的 LUT 在 `looks/luts/`。Android assets 直接使用该目录；鸿蒙 rawfile 是 `python3 tools/sync_luts.py` 同步的原字节副本。`./tools/test.sh` 检查资源哈希、15 款 LUT 插值和旧数据兼容，并执行 ASan/UBSan。

`python3 -m tools.benchmark_film` 执行主机 12MP 新旧路径基准。解释与范围见 [性能报告](PERFORMANCE.md)；不要把云端时间当成手机时间。

## 0.5 美颜验证复现

两份真实模型直接在 `models/beauty/` 并随 APK 打包，普通构建无需运行模型提取脚本或下载模型。手机安装后首次离线使用不依赖 Gradle 网络。

主机验证另建 Python 3.12 环境，避免覆盖构建工具；可安装 `tools/beauty-validation-requirements.txt`。然后：

```bash
python3 tools/fetch_beauty_validation_samples.py
mkdir -p .build
c++ -std=c++17 -O2 -shared -fPIC -Icore/include core/src/beauty.cpp core/src/lut.cpp -o .build/libg23beauty.so
python3 -m tools.validate_beauty
```

照片下载仅为开发者主机验证，不由应用或构建触发。验证结果 `.build/beauty-validation.json` 与 PNG 留在本地。已执行报告见 [beauty-host-0.5.json](performance/beauty-host-0.5.json)。这覆盖真实模型与共享 C++，不覆盖 Android JNI 相机设备，也不覆盖原生鸿蒙。
