# G23 Lab

Android 与 HarmonyOS NEXT 原生实验相机，配合 C++ 调色引擎、版本化样本协议与 Python 训练数据工具。默认调色为中性；不内置 AI，不把手机系统相机 HDR 或理光成像效果作为已实现能力。

**当前交付是可编译的 Android 开发版本与待鸿蒙 SDK 验证的原生源码，不是已通过双平台真机验收的成品。** 具体证据与差距见 [验收报告](docs/ACCEPTANCE.md)。


## 下载安卓安装包

**[点击下载 G23 Lab 0.3 安卓调试版 APK（约 20MB）](https://github.com/duckworthramser408-lang/mynewapp/raw/refs/heads/main/downloads/G23-Lab-debug.apk)**

如果 GitHub 显示文件无法预览，请点击 **Download raw file / 下载原始文件**。APK 用于 Android 手机安装，电脑用于下载；它不是电脑软件或网页应用。当前为开发调试版，真实设备上的相机和导出流程仍待验收。

安装包在 `downloads/G23-Lab-debug.apk`，校验值见 [SHA-256 文件](downloads/G23-Lab-debug.apk.sha256)。Android 源码使用 Kotlin + CameraX，鸿蒙使用 ArkTS + Camera Kit；共享图像引擎为 C++，数据工具为 Python。

GitHub Actions 配置会在 push / pull request 后执行核心与 Android 检查，并上传新构建的 APK。下载目录保留的是本次交付的构建，后续代码修改不会自动替换它。

| 目录 | 用途 |
|---|---|
| `android/` | Kotlin / CameraX 1.5.1；胶卷 LUT 相机、照片相册、实验编辑与 ZIP 保存 |
| `harmony/` | ArkTS / ArkUI / Camera Kit；C++ 异步 N-API 桥接 |
| `core/` | SDR sRGB、float 工作缓冲、非破坏性、分块渲染 |
| `protocol/` | 参数 / 元数据 JSON Schema、默认值、单位 |
| `tools/` | 安装、构建、测试、桌面参考渲染、数据集读取与导出 |
| `tests/` | 图像算法、真实编解码、配对、校验、导出及协议一致性测试 |

## 在此云环境继续开发

```bash
cd /workspace/mynewapp
python3 tools/bootstrap_cloud.py
source tools/env.sh
./tools/test.sh
python3 tools/android_build.py :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`。调试包仅供开发测试。

`bootstrap_cloud.py` 将经 SHA-256 校验的 JDK、Gradle、Android SDK/NDK 和 Python venv 安装到 `/workspace/toolchains`。不改系统 HOME，不关闭 TLS 或校验。`android_build.py` 为 Java 构建显式使用环境提供的 CONNECT 代理。普通电脑也可直接使用 Android Studio 或 `android/gradlew`。

## 数据集和参考渲染

```bash
# 批量校验并导出（默认只包含 approved；目录内是 G23_* 样本文件夹）
python3 -m tools.g23_dataset export /path/to/samples /path/to/G23.zip
# 直接读取 ZIP，校验所有哈希，统计参数，生成图片对照 HTML
python3 -m tools.g23_dataset analyze /path/to/G23.zip --report /path/to/report.html
# 也支持已经解压的 G23_Dataset_Export 目录
python3 -m tools.g23_dataset analyze /path/to/G23_Dataset_Export --report /path/to/report.html
# 使用同一个 C++ 引擎渲染，保留输入文件
python3 -m tools.render input.jpg protocol/default_parameters.json output.png
```

ZIP 读取器将图片流式解压到报告旁独立的 `g23-report-assets-*` 目录；移动报告时同时移动该目录。训练读取代码见 `tools/g23_dataset.py` 的 `analyze()`：每行 JSONL 对应原图、PNG 目标及最终参数，历史不会自动作为标签。

## 0.3 胶卷 LUT 相机

用户提供的 15 个 33³ LUT 已全部接入。相机顶部的品牌文字改为 LUT 选择按钮：右侧半圆排列圆柱胶卷暗盒，左侧箭头标记当前选择，名称随滚动更新。支持循环拨动、短距离惯性、吸附、轻微触觉反馈和保存上次选择。

点击“使用并返回拍摄”，拍照后自动生成带所选 LUT 的完整成片并回到相机。取景目前是原始实时画面。原 JPEG 保留，每张照片绑定独立的 LUT 文件与记录，后来换胶卷不会影响旧照片。

相册只保留三列照片和全屏浏览。原有编辑、并列数据和导出移到相机设置的“照片工具”。导出的 ZIP 包含原图、成片、参数、分析、绑定 LUT 和校验信息；普通导出不会将草稿自动确认为训练样本。

**[中文使用说明](docs/USAGE.zh.md)** · [胶卷选择器预览（原生控件测试渲染）](docs/images/lut-picker-0.3.png) · [LUT 格式与处理流程](docs/LUT.md)

Android 可根据 CameraX 能力请求 RAW+JPEG。导出定位清理在设置中控制；开启时只清理导出副本并省略 DNG。鸿蒙同步的是待 SDK 编译验证的源码，包含相同的 LUT 资源和共享 C++ 处理。

详细说明：[构建与真机运行](docs/BUILD.md) · [模块架构](docs/ARCHITECTURE.md) · [算法公式](docs/PIPELINE.md) · [数据协议](docs/DATASET.md) · [验收与待办](docs/ACCEPTANCE.md) · [原始任务书](docs/REQUIREMENTS.zh.md)
