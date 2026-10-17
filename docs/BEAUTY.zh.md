# 本地自然美颜：0.5 最小实装与边界

**Android 已接入现有拍照保存流程；原生 HarmonyOS 的美颜尚未接入。完整小头尚未实现，界面提供的是轻微小脸。** 没有真机连接，因此以下算法／构建检查不能表述为手机效果验收。

## 项目判断与接入位置

Android 是 Kotlin 原生界面、CameraX、WorkManager，加 C++ / JNI LUT 引擎；不是 Flutter、网页或跨平台 WebView。`harmony/` 是独立 ArkTS / ArkUI、Camera Kit、Image Kit、N-API 的原生 HAP 项目，不是 Android 兼容层。能运行 APK 的旧鸿蒙设备可以安装 Android 包，但这不能代表原生 HarmonyOS 实现。

保留相机和 15 款用户 LUT，新增处理顺序：

```
相机 JPEG 原片（不改写）
→ ImageDecoder：EXIF 旋转／镜像、转 SDR sRGB
→ 既有画幅裁切（旧照片沿用原几何）
→ 本地 BlazeFace 检测 + FaceMesh 关键点
→ 一次局部几何采样（部分对称修正 + 轻微小脸）
→ 局部法令纹明暗修正
→ 既有三线性 LUT + 滤镜强度
→ 全尺寸 JPEG 95 + 相册缩略图
```

CameraX 保存使用现有前置相机默认镜像约定，未另外反转成片。所有点位都从**归一化后的照片**检测，不用预览坐标，因此不会再套一遍前置镜像。ImageDecoder 的八种 EXIF 变换通过 host 原生图形测试；实际前置硬件输出仍待手机核验。

## 模型与运行库

| 资源 | 来源与版本 | 输入 | 输出 |
|---|---|---|---|
| `face_detection_short_range.tflite` | Google Maven `facemesh:0.10.14` 原始 AAR | float32 `[1,128,128,3]` RGB，`[-1,1]` | `[1,896,16]` 框／关键点回归、`[1,896,1]` 置信 logit |
| `face_landmark.tflite` | 同一 Google AAR 原始权重 | float32 `[1,192,192,3]` RGB，`[0,1]` | `[1,1,1,1404]` 的 468 个 xyz、`[1,1,1,1]` presence logit |
| canonical face / 镜像索引 | Google MediaPipe `v0.10.14` canonical OBJ | 标准 468 点拓扑 | 本地姿态拟合模板与左右拓扑映射 |

来源、精确 SHA-256、字节数记录于 [manifest](../models/beauty/manifest.json)；保留 [Google Maven POM](../models/beauty/SOURCE.pom) 和 [Apache-2.0 许可证](../models/beauty/LICENSE)。`tools/prepare_beauty_assets.py` 可从官方源重新提取同一权重，验证固定模型哈希；不转换、不伪造 `.task`。

已评估 MediaPipe Android Face Landmarker 静态图模式。其官方 task 模型下载地址在本云网络返回 CONNECT 403，无法验证；其 Tasks 图要求 478 点模型，不能把旧 468 点模型重新压成 `.task` 冒充兼容。旧 `facemesh/solution-core:0.10.14` Android 原生包缺 x86_64，且其 ELF 为 4KB 对齐，因此没有直接引入该旧 Java SDK。

实际采用 **LiteRT Android 1.4.1**，直接解释两份真实 TFLite 模型，自行实现官方图中的预处理、896 anchors、weighted NMS、眼线旋转裁切与回映。Android 两个 ABI 均打包；使用两线程 CPU / XNNPACK，初始化失败尝试无 delegate CPU。不启用未经设备验证的 GPU / NNAPI。主机另用 LiteRT 1.4.0，分别跑过 XNNPACK 与无默认 delegate 的真实推理，并与官方 MediaPipe Python 图比较点位（精度见验收 JSON）；这不是 Android JNI 真机验证。

模型随 APK 提供，安装和第一次使用均不需要模型下载；应用清单没有 INTERNET 权限。公开测试照片只在忽略的 `.build/` 中，既不进入 APK，也不随源码分发。应用不上传照片、点位或处理日志。

## 三个独立处理项

- **对称修正**：用 canonical 与检测的 xyz 做刚性相似姿态拟合，在脸部局部坐标中比较左右脸颊／轮廓点。只减弱偏差的一部分，单项位移上限为脸宽 0.5%；不移动眼睛、鼻翼和嘴唇，不加入大眼、隆鼻或尖下巴。它没有诊断人脸生理不对称的能力，也不承诺完全对称。
- **淡法令纹**：鼻翼外侧至嘴角外侧仅作为候选走廊。实际采样两侧亮度，要求中心为相对凹暗纹、两侧均较亮；以较暗侧为目标，降低方向光照带来的误提亮。拒绝过暗、高光、极端对比和不平衡阴影。只平滑局部亮度增益场，不模糊源图；在线性 sRGB 对 RGB 同比增益，保留纹理与色度，不做全脸美白。最大 log 增益 0.12，再乘参数、置信与羽化。效果非常轻，部分照片可能肉眼难辨；它不是精确语义法令纹分割。
- **轻微小脸（非小头）**：仅轻微向内调整下部脸颊轮廓，单项位移上限为脸宽 0.8%，再乘参数和质量。没有头发／完整头部分割，不会宣称缩小头发、颅顶或整个头部。完整小头还缺可靠头发／头部分割、颈肩遮挡关系、背景修复与经过设备验证的连续形变。

默认总开关开启，控制参数为对称 20%、淡纹 25%、小脸 10%。这些是算法控制数值，**不是像素移动／脸宽缩小百分比**。组合位移受局部权重归一化约束；实际样本默认位移远小于上限。

只处理最大且可靠的主脸。其他检测框加保护边界，人物相交时跳过；脸贴边时禁用几何项。检测分数、presence、正反裁切点位一致性、姿态拟合残差、面部亮度、嘴巴张开／微笑程度共同降强度或跳过。较大转头／抬低头完全跳过。该质量门控不是遮挡分割，可能漏判局部遮挡；真实刘海、手遮脸、深色镜框等仍需扩充样片验收。

几何变形合并为一次 ROI 采样，眼镜／鼻翼／嘴角／其他人物区域固定，边界平滑衰减。后续关键点用同一场前向更新，再定位淡纹。ROI 外像素不改动。没有可靠人脸时直接运行原有 LUT。

## 原片、对比、重出图与失败回退

`photo.json` 只保存本张不可变出图配方与任务状态，不生成实验分析数据。原片 JPEG 保留。更换胶卷、滤镜强度或美颜时，预览“按当前设置重新出图”始终从原片重新解码，不在美颜成片上叠加。

“仅 LUT／美颜”比较两张相同画幅、相同 LUT、相同强度和 JPEG 质量的完整照片。实际美颜改变像素时，额外生成 `lut_only.jpg`；无变化时两边可复用同一 LUT 成片。为控制内存，先编码／释放美颜结果，再二次解码原片生成基线，代价是额外一次解码、LUT 和编码。对比基线失败时保留已保存的美颜成片，不伪造对比。

模型／JNI／美颜出错或内存预算不足时回退正常 LUT；必要时重新解码原片，避免部分修改泄漏。原片永远保留。设备连普通 LUT 处理的内存或磁盘都不足时显示可重试失败，不能承诺物理资源耗尽时还能生成 JPEG。删除中途的照片不会复活。

WorkManager 的持久化串行队列处理推理、像素和编码，取景界面可以继续拍照。每个任务结束关闭模型并回收 Bitmap；只缩小**检测图**至最长边 960，成片在原输出尺寸处理，不将低清检测图放大成成片。C++ scratch 限于主脸 ROI 与增益网格。

`adb logcat -s NaturalBeauty FilmDevelop` 可读取 detection、geometry、folds、LUT、encoding、baseline 分段耗时及 scratch、heapUsed、RSS high-water。RSS 是**进程自启动以来峰值**，不是某张照片独占内存峰值。未持久化照片分析表或点位。

## 鸿蒙 API 26 的实际状态

用户提供目标：HarmonyOS 7.0 / API 26，DevEco Studio 5.0.3.800 及以上。现已收到用户 Windows SDK 的实际元数据：API 26 Release / 26.0.0.105，SDK 位于 `E:\DevEco Studio\sdk\default`，但当前云环境仍没有 DevEco、API 26 SDK、hvigor、ohpm、HDC、原生推理库或设备。因此没有编译 HAP、没有核验系统人脸检测输出拓扑／精度，也没有验证 MindSpore Lite 模型转换和算子支持。**本轮未向鸿蒙加入美颜开关、空接口或未经构建的推理调用。其现有 LUT 相机源码保留，美颜尚未实现。**

Android `LocalFaceDetector` 只是 Android 适配器，C ABI 则接受标准归一化 468 点及其他人物框，可供未来原生适配调用，但 C++ 可编译不等于鸿蒙模型可运行。系统检测若仅提供框／少量五官点，不能直接满足姿态、局部几何与法令纹走廊；必须在实际 SDK 核验点位定义后决定是否引入独立的本地密集关键点模型。不得假设 `.tflite` 可直接被 MindSpore Lite 加载，也未声称成功转换。

用户补充实际 SDK 元数据后，已将默认 product 的 compile/target API 显式设为 26；原 `compatibleSdkVersion: 5.0.0(12)` 保留为最低兼容声明。配置更新不代表编译通过；[Windows 检查／构建入口](HARMONY_WINDOWS.zh.md) 用于回传 SDK 实际声明与本机构建结果，之后才能继续原生模型输出对照、离线安装与拍摄保存验证。

## 主要修改文件

| 文件 | 职责 |
|---|---|
| `android/app/build.gradle.kts` | LiteRT 依赖、离线模型 assets、0.5.0 版本 |
| `BeautySettings.kt` | 独立强度、轻微默认值、本地持久化和每张快照 |
| `LocalFaceDetector.kt` | 真正的本地检测／468 点模型推理、坐标回映与多脸框 |
| `NaturalBeauty.kt` | 检测、美颜调用、可靠性状态与回退 |
| `PhotoStore.kt` | 原片→美颜→LUT→保存、基线、重出图、耗时和内存预算 |
| `MainActivity.kt`、`PhotoGallery.kt` | 中文设置、同 LUT 滑线比较、原片重出图 |
| `Imaging.kt`、`LutLibrary.kt`、`cpp/bridge.cpp` | JNI 接口及滤镜强度，复用现有方向／sRGB 解码 |
| `core/include/g23_beauty.h`、`core/src/beauty.cpp` | 平台无关、真实局部几何与淡纹算法 |
| `core/src/beauty_canonical.h`、`models/beauty/` | 官方模型、拓扑与许可证／哈希 |
| `core/src/lut.cpp`、`g23_lut.h` | 带强度的 LUT；原 C ABI 仍可供鸿蒙使用 |
| `BeautyIntegrationTest.kt`、`core/tests/beauty_test.cpp`、`lut_test.cpp` | 配方、回退、EXIF、变形边界与滤镜回归 |
| `tools/*beauty*.py`、`tools/test.sh`、相关文档 | 可复现真实模型验证、来源记录与交付说明 |
