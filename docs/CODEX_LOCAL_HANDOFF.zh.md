# 本地 Codex 接手：G23 安卓／原生鸿蒙 LUT 相机

交接日期：2026-10-17。请在整个仓库根目录工作，先检查 Git 状态与已有本地修改，再继续实现。用户希望本地 Codex 直接使用电脑上的 DevEco / SDK，完成原生鸿蒙实现、构建和验证。

## 当前目标与产品约定

这是已有的简单拍照出图相机。保留拍照、15 款用户 LUT、滤镜强度、画幅、前后置／方向处理、后台冲洗、相册删除和保存原片／胶片；界面尽量中文、简洁。相册只展示照片，预览用一条可拖动竖线比较。不要恢复旧实验调色台、参数分析表或整组数据导出功能。

当前开发目标是**完全本地、模型随包提供、默认开启、拍摄后自动使用的自然美颜**，不是演示项目：

- 轻微部分对称修正：可靠关键点、脸部局部坐标和姿态门控，不用整幅照片垂直中线硬做镜像，不添加默认大眼／隆鼻／尖下巴。
- 轻微淡法令纹：关键点仅辅助定位，实际图像判断沟纹／阴影；局部保边保纹理，不做全脸模糊、额外美白或白条。
- 完整小头应包含头发和颅顶，依赖可靠头部／头发边界及颈肩、背景处理。用户允许首版只能做轻微小脸，但必须清楚标注“小脸”，不能把它称为完整小头。
- 只处理最大且可靠的主脸，保护其他人物；侧脸、遮挡、不可靠点、夸张表情、贴边／重叠等降低强度或跳过。无人脸沿用 LUT，任何美颜失败回退正常 LUT 并保留原片。
- 顺序：解码并归一化 EXIF／镜像与 sRGB → 裁切 → 检测／关键点 → 合并几何 → 更新关键点／蒙版 → 局部淡纹 → 原有 LUT → 编码保存。
- 全尺寸处理；检测可缩小，但点位须准确映射。更换 LUT／强度从原片重生成，避免重复美颜。后台串行控制内存与连拍；记录分段耗时和峰值内存的实际范围。
- 中文总开关、三个独立强度、本地保存，说明拍后生效；可以比较“仅 LUT”与“美颜＋同一个 LUT”。强度数值不是实际位移／缩放百分比。

更完整的算法与实现约定见 [BEAUTY.zh.md](BEAUTY.zh.md)，用户使用方式见 [USAGE.zh.md](USAGE.zh.md)。不要只加开关、空接口或 TODO 就报告已实现。

## 已完成的代码与验证

- Android 0.5.0：Kotlin 原生控件、CameraX 1.5.1、WorkManager 2.10.0，C++ / JNI 引擎。已接入真实本地推理、几何／淡纹、默认开关与独立强度、同 LUT 全尺寸基线、从原片重出图及失败回退。
- Android 当前是**轻微小脸，完整小头未实现**。默认效果非常轻，部分样片几乎无变化；遮挡门控不是语义遮挡分割，需要进一步实际样片验证。
- 模型：Google 官方 `facemesh:0.10.14` AAR 中的 BlazeFace `face_detection_short_range.tflite` 与 468 点 `face_landmark.tflite`。资源、SHA、Apache-2.0 / POM 在 `models/beauty/`；Android 实际运行库为 LiteRT 1.4.1，不是旧 MediaPipe Java SDK。
- 已评估 Face Landmarker Tasks：云网络没能取得官方 task 模型，不能把旧 468 点模型伪装成要求 478 点的 task。旧 facemesh Android SDK 缺 x86_64、ELF 对齐不满足当前要求，因此直接使用真实 TFLite 模型与自行实现的预处理。
- 已执行 Android 构建、46 项单元／Robolectric 检查、Lint 0 errors / 25 warnings；37 项 Python 测试、ASan / UBSan、3 项 CTest；真实模型主机验证含 9 类真实／明确标记的派生样本。详见 [ACCEPTANCE.md](ACCEPTANCE.md) 和 `docs/performance/`。
- **没有 Android／鸿蒙真机验收，没有 Android JNI 在设备上的推理验收。** 主机推理不是设备推理；眼镜、刘海、真实弱光、高分辨率和复杂多人仍需扩充验证。
- APK 已交付为 `downloads/G23-Lab-debug.apk`，SHA 在同目录。原开发签名未随仓库提供；本机新生成的 Android 调试签名未必能覆盖旧包，安装时须核验实际签名与应用内照片保留情况。

主要提交：`fca378565b0e61a2eeea1f76ce51ef7d543565ca` 为安卓美颜实装；`aa1f610fdac094d8e5651baef0879b27427e43b5` 为 API 26 配置与 Windows 检查入口。之后还有本交接文档提交，以当前分支为准。

## 鸿蒙实际状态与本机环境

`harmony/` 是独立原生 ArkTS / ArkUI、Camera Kit、Image Kit、N-API 项目。它不是 Android APK 的兼容层。

**原生鸿蒙美颜尚未接入；HAP 尚未实际编译。** 现有鸿蒙 LUT 相机源码需先在真实 API 26 工具链构建，不能把安卓构建成功、共享 C++ 或 SDK 元数据当成鸿蒙实现成功。

用户已在 Windows PowerShell 读取 SDK 文件，确认：

| 项目 | 已知值 |
|---|---|
| DevEco Studio 安装目录 | `E:\DevEco Studio` |
| SDK 根目录 | `E:\DevEco Studio\sdk\default` |
| SDK 元数据文件 | `E:\DevEco Studio\sdk\default\sdk-pkg.json` |
| 元数据结构 | `meta` 与 `data`；版本字段在 `data` 内 |
| `data.apiVersion` | `26` |
| `data.displayName` | `HarmonyOS 26.0.0` |
| `data.path` | `HarmonyOS-26.0.0` |
| `data.platformVersion` | `26.0.0` |
| `data.version` | `26.0.0.105` |
| `releaseType` / `stage` | `Release` / `Release` |

默认 product 已显式指定 `compileSdkVersion: 26`、`targetSdkVersion: 26`；`compatibleSdkVersion: 5.0.0(12)` 是保留的最低兼容声明。API 26 配置尚待实际 Hvigor 插件校验。已有 `hvigor-config.json5` 的 modelVersion 为 5.0.0，需结合本机工具版本检查，不凭空选插件版本。

本地直接检查这些路径与真实工具即可，无需再让用户查找已知 SDK 位置。若 Codex 运行在 WSL，项目宜放在 Windows 磁盘并通过 Windows PowerShell 调用 Windows 工具；`/mnt/e/DevEco Studio/...` 是 WSL 对 E 盘的映射。Windows SDK 的可执行文件不能作为 Linux 原生工具链直接运行。

## 建议从这里继续

1. 阅读 `docs/HARMONY_WINDOWS.zh.md`，检查本机 DevEco / SDK / Node / Java / Hvigor / OHPM 的实际布局。在仓库根目录使用已有脚本：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\harmony_windows.ps1
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\harmony_windows.ps1 -Build
   ```

   输出在 `.build\harmony-windows\`：`environment.txt`、`sdk-face-api.txt`、`ohpm.log`、`hvigor.log`。默认诊断不会构建；`-Build` 安装项目依赖并实际调用本机工具。脚本只在云端 PowerShell 7.4.6 用元数据／声明夹具做过检查，**Windows 5.1、真实工具布局与构建分支尚未验证**；本地需要修正实际发现的问题。
2. 先修复现有原生项目构建问题，保持 `harmony/` 与同级 `core/` 的相对位置。记录实际命令与 HAP 产物、错误和签名状态。
3. 读取实际 SDK 人脸检测／关键点／分割接口，确认点位拓扑、坐标、精度、支持设备和 API 限制。框或少量五官点不能直接代替共享内核要求的密集点。必要时评估真实轻量模型与 MindSpore Lite 等本地推理；先验证算子、输入输出和模型转换，再接入。不要假设 Android MediaPipe / LiteRT SDK 可运行于原生鸿蒙，也不要假设 TFLite 可直接被 MindSpore Lite 加载。
4. 在鸿蒙照片实际保存流程接入原生检测／点位映射与共享算法，完成独立控制、每张配方、同 LUT 对比、方向／色彩、后台性能与失败回退。保持安卓既有功能与资源；共享引擎变动需跑相应回归。
5. 在有设备时实际验证离线首次使用、前后置与八种方向、无脸／多人、弱光、眼镜／刘海、几何与纹理保护、连拍、低内存与删除竞态。没有设备的项目明确标为未验证。
6. 最终交付修改文件、真实模型来源／版本／许可证和预处理、构建安装方法、实际检查结果、耗时／内存范围及限制。完整小头未具备可靠分割时继续如实标“小脸”。

## 实现入口

| 路径 | 用途 |
|---|---|
| `android/app/src/main/java/lab/g23/PhotoStore.kt` | 已完成的 Android 原片→美颜→LUT→保存、基线、重出图 |
| `BeautySettings.kt`、`LocalFaceDetector.kt`、`NaturalBeauty.kt`（同目录） | Android 配方、实际模型预处理／映射与回退，可参考其算法约定 |
| `core/include/g23_beauty.h`、`core/src/beauty.cpp` | 共享 C ABI：468 个归一化 xyz、其他人物框，局部真实美颜；不依赖 Android SDK |
| `core/src/lut.cpp`、`core/include/g23_lut.h` | LUT 与强度，新旧 C ABI |
| `harmony/entry/src/main/ets/model/CameraService.ets` | 鸿蒙相机输出 |
| `harmony/entry/src/main/ets/model/PhotoStore.ets` | 鸿蒙照片队列、原片／胶片、删除和保存配方 |
| `harmony/entry/src/main/ets/model/Imaging.ets` | 鸿蒙 EXIF／sRGB、画幅与现有 LUT 处理节点 |
| `harmony/entry/src/main/cpp/bridge.cpp`、`types/libg23/index.d.ts` | 鸿蒙异步 N-API 与 ArkTS 类型 |
| `harmony/entry/src/main/ets/pages/Index.ets` | 鸿蒙相机、设置、相册与预览界面 |
| `looks/luts/`、鸿蒙 `rawfile/luts/` | 15 款用户 LUT，保持原字节与目录同步 |

## 给本地 Codex 的启动指令

> 请阅读 docs/CODEX_LOCAL_HANDOFF.zh.md 接手现有项目。直接检查本机 E:\DevEco Studio 的 API 26 SDK 和工具，运行已有检查与构建脚本，修复真实构建问题，继续实现原生鸿蒙完全本地的拍后自然美颜。保留安卓已完成的功能与 15 款 LUT。按文档验证真实模型、坐标、保存流程和回退，准确报告小脸／小头能力与未验证项。
