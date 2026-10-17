# G23 相机

一款简单的胶卷 LUT 相机：选择胶卷、拍照、出图。Android 0.5 默认启用完全本地的拍后自然美颜，提供轻微对称修正、淡法令纹和轻微小脸（不是完整小头）。拍摄后的冲洗在后台排队进行，相机可以继续拍摄。相册只展示照片；打开照片可拖动一根竖线对比原片与胶片，选择保存其中一种，或删除照片。

## 下载安卓安装包

**[点击下载 G23 相机 0.5 安卓 APK](https://github.com/duckworthramser408-lang/mynewapp/raw/refs/heads/main/downloads/G23-Lab-debug.apk)**

电脑网页版打开链接即可下载，再传到 Android 手机安装。如果 GitHub 显示“不支持预览”，点击 **Download raw file／下载原始文件**，无需解压。仓库位置：`downloads/G23-Lab-debug.apk`，校验值见 [SHA-256](downloads/G23-Lab-debug.apk.sha256)。包名和开发签名延续上一版，可覆盖升级，升级前不要卸载应用内尚未另存的照片。

这是 Android 开发调试包。原生 HarmonyOS 美颜本轮尚未接入；API 26 工具链不在当前环境中。构建、自动化检查与真实手机验收是不同环节；当前环境没有相机真机，鸿蒙原生 HAP 尚未编译，详见[验收状态](docs/ACCEPTANCE.md)。

## 使用

1. 顶部 LUT 按钮打开胶卷轮盘；上下拨动、吸附选择，点“使用并返回拍摄”。内置你提供的全部 15 款 LUT。
2. 中下方快门拍照。JPEG 落盘后快门恢复，胶卷色调在后台应用，不弹出处理进度框。
3. 左下角打开相册。未完成的照片显示胶卷暗盒和“冲洗中”，完成后自动变为照片。
4. 点击照片进入预览，拖动竖线查看原片与胶片；点“保存图片”选择保存原片或胶片。
5. 长按相册照片，或点预览页的删除图标，确认后删除应用内的原片和胶片。系统相册已经另存的副本保留。

Android 设置新增自然美颜总开关、三个独立强度及滤镜强度，均本地记忆。预览可切换“仅 LUT／美颜”以同一个 LUT 比较美颜效果，或“按当前设置重新出图”，始终从原片重新处理。没有实验编辑台、分析表或整组数据导出。取景仍为原始实时画面，美颜与 LUT 均在拍后后台生效。

仅处理可靠主脸；弱光、大侧脸、人物相交等情形会减弱或跳过，出错回退正常 LUT。模型随安装包提供，无需首次在线下载，应用没有网络权限。当前默认效果非常轻，部分照片可能几乎无变化。完整头部／头发缩小未实现；详见[实装、模型、限制与鸿蒙状态](docs/BEAUTY.zh.md)。

[完整中文使用说明](docs/USAGE.zh.md) · [慢速原因与测速](docs/PERFORMANCE.md) · [鸿蒙系统相册分析](docs/HARMONY_GALLERY.md) · [LUT 说明](docs/LUT.md)

## 开发

Android 使用 Kotlin 原生控件、CameraX 1.5.1、WorkManager 2.10.0；鸿蒙源码使用 ArkTS、ArkUI、Camera Kit 和 Image Kit；LUT 插值共享 C++ 引擎。Android 最低 API 28，构建包含 arm64-v8a 和 x86_64。

```bash
cd /workspace/mynewapp
python3 tools/bootstrap_cloud.py
source tools/env.sh
./tools/test.sh
python3 tools/android_build.py :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`。工具与缓存位于 `/workspace/toolchains`，不用改系统 HOME。详见[构建说明](docs/BUILD.md)。

鸿蒙 Windows 本机已确认 SDK 为 API 26 Release / 26.0.0.105。默认编译／目标 API 已设为 26；[本机检查与 HAP 构建步骤](docs/HARMONY_WINDOWS.zh.md) 可收集实际工具及人脸接口信息。原生美颜和 HAP 编译仍待本机验证。

旧版实验协议和桌面工具保留在 `protocol/`、`tools/g23_dataset.py` 和相关历史文档中，供读取旧数据；它们不是当前相机的功能，也不适用于 0.4 新照片。旧照片可在新版相册继续预览和保存；只有手动选择重新出图时才会采用新的美颜设置。
