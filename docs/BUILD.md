# 构建与运行

## Android

- Android Studio，JDK 17，Gradle 8.9，AGP 8.7.3，Kotlin 2.0.21。
- compile/target API 35；min API 28。Build Tools 35.0.0、NDK 27.0.12077973、CMake 3.22.1。
- CameraX 1.5.1；arm64-v8a 真机和 x86_64 开发构建。

打开 `android/`，让 Android Studio 设置 SDK 路径；或设置 ANDROID_HOME。release 签名不存入仓库。

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n lab.g23/.MainActivity
```

云环境使用 README 中的 bootstrap/env/android_build 命令；完整 JDK 是必要条件，基础镜像的精简 JRE 没有 javac/jlink。工具与依赖官方下载校验记录在 `tools/toolchains.json`，代理使用系统 CA。

手机允许相机权限。拍摄后调整任意参数，先保存成片，再标记训练样本。构建通过不能代替这些真机检查。

## HarmonyOS NEXT

此云环境没有 DevEco Studio、HarmonyOS SDK、hvigor、ohpm、HDC 或可连接的鸿蒙真机。**未编译 HAP，未声明鸿蒙相机运行成功。**

1. 在具备 HarmonyOS NEXT SDK 的 DevEco Studio 5.x 或兼容版本中打开 `harmony/`。
2. 使用 API 12 / 5.0.0 产品，Stage 模型，启用 Native C++ 工具链。同步 hvigor/ohpm；项目不依赖第三方图像库。
3. 在 IDE 配置实际产品和调试签名。签名证书及设备授权留在本机，不提交源码。
4. 运行 entry/default。IDE 提供的 hvigor 命令行环境可执行 `hvigorw --mode module -p module=entry@default -p product=default assembleHap`；仓库不伪造 SDK wrapper 或签名文件，使用 IDE 对应工具。
5. 逐项完成 `ACCEPTANCE.md` 真机清单，特别确认 photoAvailable/JPEG、PixelMap 的方向和色彩空间、N-API 内存、会话关闭、ZIP 目录层级及系统分享路由。

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
