# 验收报告 · 2026-10-16 / 0.3.0

## 结论

交付 Android 调试 APK、Android/HarmonyOS NEXT 原生源码、共享 C++ 影像引擎、完整参数协议、样本与 ZIP 导出工具、训练读取示例和文档。Android **编译、单元测试、Lint、签名和 ZIP 对齐检查通过**。共享算法与真实图像编解码/数据导出在本机验证通过。

**尚未满足任务书的最终双平台真机验收。** 鸿蒙端没有 SDK 编译或真机证据；Android 相机、RAW、编辑交互及分享也尚未在真机执行。不将源码、成功编译或生成 APK 描述为手机上已经运行成功。

## 0.3 本轮结果

相机顶部替换为 LUT 入口，右侧半圆胶卷暗盒选择器包含循环滚动、名称同步、吸附和触觉请求；相册仅显示照片网格及全屏浏览。拍摄固定所选 LUT，自动保存完整成片后回到相机。编辑/数据/导出移入相机设置。

用户上传的 15 个 33³ LUT 原字节接入 Android 与鸿蒙资源。共享 C++ 三线性插值在主机测试中与独立参考比较通过；真实 JPEG→LUT→PNG→重复重建→ZIP→读取流程通过，原 JPEG 不变。Android 测试验证胶卷拖动、名称、档位、触觉调用、只含照片的三列网格、LUT 绑定与导出引用。不能据此声称已测量手机马达或完成真实拍摄。

## 实际执行结果

| 检查 | 结果 | 范围 |
|---|---|---|
| Python unittest | 37 passed，0 failed/skip | 原图不变、参数效果、八色 HSL、曲线、分级、质感、种子、非法参数、瓦片边界、Schema 默认值一致性、ZIP/hash/配对及真实 JPEG/PNG 编解码 |
| C++ ASan / UBSan | 通过 | 中性跨瓦片渲染、10,000 个确定性畸形 JSON 输入，以及 LUT 网格/边界/alpha/行填充检查，无已报告内存错误 |
| CMake / CTest | 2 passed，0 failed | 独立 CMake 构建、基础 C ABI 与 LUT 插值/输入验证 |
| Android assembleDebug | BUILD SUCCESSFUL | Kotlin、arm64-v8a/x86_64 JNI/C++、资源、DEX 和 APK 打包 |
| Android testDebugUnitTest | 20 passed，0 failed/error/skip | 撤销重做、快照隔离、配对分析、采样统计、四种尺寸快门/相册布局、取景比例、并列像素显示、滚动参数表、ZIP/CSV/hash |
| Android lintDebug | 0 error/fatal，23 warnings | 没有关闭 Lint、创建基线或忽略错误；剩余为本地化、绘制分配、布局工具构造器和旧版备份声明等警告 |
| APK apksigner | 通过 | 开发调试签名 v2；不是发布证书 |
| APK zipalign | 通过 | `-c -P 16 4` |
| bootstrap 重复执行 | 通过 | 已安装工具与 Pillow 被复用；不改源码、锁文件或用户 HOME |
| HarmonyOS HAP / ArkTS / N-API | **未执行** | 没有 DevEco、HarmonyOS SDK、hvigor/ohpm/HDC |
| Android / HarmonyOS 真机 | **未执行** | 当前无可用设备连接；云端 ADB 启动试图写入只读 `/home/agent/.android` 而失败 |
| GitHub Actions | **未核验远程结果** | CI 配置已提供；本机执行了对应核心与 Android 检查，源码和 APK 已通过 Git 推送；远程任务状态未在本报告验证 |

当前 APK：`android/app/build/outputs/apk/debug/app-debug.apk`，20,435,880 bytes。

SHA-256：`c2d86804d203646e43c13601c6e07f67e86d860a260998d62e2c0278845a2858`。

本轮日志：`/workspace/toolchains/android-build-v03-final.log`、`/workspace/toolchains/core-tests-v03.log`；APK 签名记录 `.build/apk-signature-v03.log`。原生控件测试渲染截图在 [相机布局](images/camera-0.3.png) 与 [胶卷选择器](images/lut-picker-0.3.png)，它是 Robolectric API 35 的布局渲染，不是真机取景或已完成拍摄的证据。测试 SDK 由 Gradle 下载至工作区缓存，离线运行 Robolectric，不修改用户 HOME。

历史构建日志和报告：`.build/unit-final.log`、`.build/test-results.log`、`.build/ctest-final.log`、`.build/apk-signature.log`、`android/app/build/reports/`、`android/app/build/test-results/`；构建原始日志保存在 `/workspace/toolchains/android-build-final.log`。后续修改对应源码时，需要在相应平台重新执行构建和真机检查，不能依赖本报告作为未来提交的验证结果。

## CPU 渲染测量

Linux x86_64、GCC `-O3 -ffp-contract=off`、单线程，使用同时开启曝光、影调、HSL、曲线、分级、锐化/局部对比、颗粒和暗角的参数。测量脚本为 `core/tests/benchmark.cpp`，参数与原始结果在 `.build/benchmark-parameters.json` 和 `.build/performance-*.json`。

| 图像 | 渲染时间 | 进程峰值 RSS |
|---|---:|---:|
| 640×480 | 0.127 秒 | 5.8 MiB |
| 4000×3000 / 12MP | 14.15 秒 | 99.8 MiB |
| 6000×4000 / 24MP | 38.05 秒 | 193.6 MiB |

这是早期基础调色引擎的测量，不包含本轮 LUT 阶段，也不含移动端解码、Bitmap/PixelMap、编码与系统内存。无法据此保证手机速度或 30fps。已对中性阶段、参数常量和 halo 做优化；完整尺寸放后台处理，预览长边降到 640px。更低延迟的 GPU/SIMD、节流策略和设备内存标定仍是后续性能工作。

## 阶段交付状态

1. **工程与模型**：Android、HarmonyOS NEXT Stage 工程、C++ C ABI、正式参数/元数据 Schema、默认值和单位均已建立。
2. **真实相机接口**：Android CameraX 实时预览、对焦、闪光/EV 能力、JPEG 与能力检测后的 RAW+JPEG 已写入并编译。鸿蒙 Camera Kit 会话、输入输出、photoAvailable JPEG 保存已写源码，未编译。两端都没有静态相机或网页模拟替代。
3. **编辑与非破坏性渲染**：两个原生编辑器和桥接源码；Android 已编译。核心实际改变像素；中性结果、重复输出、原图不被覆盖有主机测试。保存走完整尺寸，不拿缩略图冒充训练目标。
4. **高级调色**：八色 HSL、四曲线、三段分级、纹理参数、确定性颗粒全部有实际 C++ 运算。曲线图形控件含新增、拖动、长按删除和复位；鸿蒙还提供精确 JSON 控制点输入。平台交互仍待真机验证。
5. **记录与样本**：完整快照、独立历史、恢复游标、draft/approved、手工多标签、备注/评分、预设 CRUD/JSON，以及分页并列相册和批量选择已有源码。Android 历史、分析和视图/导出经过 20 个 JVM/Robolectric 单测；设备上的异常断电恢复未测。
6. **数据导出**：Python 真正生成/读取 ZIP、JSONL、CSV、SHA-256、重复和缺失检测、错配拒绝、图片对照报告已经测试。Android 流式 ZIP、系统另存为和保存后分享，鸿蒙暂存目录/zlib/系统保存已有源码。Android ZIP、并列 CSV 与快照校验有 Robolectric 测试；系统文件选择器与手机导出仍待真机验收。
7. **最终测试**：主机和 Android 编译级检查完成；设备、鸿蒙 SDK 与性能验收未完成。

## 已知差距

- 鸿蒙代码必须先在目标 DevEco/SDK 编译，修复任何 ArkTS/API/签名兼容问题，再做实机验收。当前不能承诺直接导入就编译成功。
- 鸿蒙只采集 JPEG；尚未实现 RAW+JPEG 或无损移除 GPS。0.2 源码导出保留原始 EXIF。
- Android RAW 同拍和 JPEG 降级是编译通过的实现，仍需在分别支持/不支持 RAW 的设备上验证两次回调、DNG 有效性及流组合兼容。
- Android GPS 清理模式会省略 DNG；关闭清理才会导出真实 DNG。定位 EXIF 清理及分享权限仍需用真实文件和分享目标验证。
- 当前输出为 8-bit sRGB PNG；没有 16-bit PNG、广色域/HDR 目标或 RAW 显影。float 处理不会恢复 JPEG 已丢失的动态范围。
- 0.2 重做中文暗色界面、快门/相册位置、并列照片/参数与一键文件导出；相机布局在 320×568、360×760、412×860、760×360 测量验证。0.3 改为仅照片相册，LUT 选择器有原生图形测试；实际震动强弱、长时滑动与相机保存速度仍需真机验收。手工标签仍是逗号输入、日期仍是前缀筛选；鸿蒙安全区/方向/预览比例仍需设备适配。
- 移动端端到端存储不是数据库事务；崩溃可能留下 draft、临时文件或未完成的拍摄目录。校验会拒绝不一致样本，需重新保存衍生文件；未建立 metadata 的原始拍摄文件需要恢复处理。
- 大数据集缩略图按页加载，导出使用文件流/系统压缩；数千条元数据的筛选仍含同步磁盘访问，需要真实数据压力测试。
- 同构建重复渲染有测试；跨平台不同解码器/浮点库的逐字节一致尚未验证。暂不自动迁移未知算法版本。

## 真机验收清单（全部待执行）

在 Android 和 HarmonyOS NEXT **分别**记录型号、系统/SDK、相机 ID、测试日期、结果和日志：

- 首次允许/拒绝权限；前后摄像头预览，对焦；支持与不支持闪光/EV 时的反馈；前后台和旋转。
- 连续拍摄多张真实照片，验证方向、原始尺寸、原生输出与 3:2/4:3/1:1 裁切的区别。保留原 JPEG，禁止伪造 RAW。
- 修改全部调色分类，验证图像效果、曲线点操作、单项复位、撤销/重做、缩放/拖动、长按和分割对比。
- 12MP、24MP 完整渲染；测量耗时、内存、发热；低内存时必须保留原图并清晰报错。
- 保存草稿后杀进程、重开、恢复参数；重新编辑 approved 后状态变 draft；再次确认才 approved。
- 预设命名、覆盖、删除、跨端 JSON 导入导出；非法 JSON 不得破坏当前样本。
- 创建多组 approved/draft，分别筛选、分页、跨页批量选择；缺失文件、损坏校验和、重复原图应明确拒绝导出。
- 15 个胶卷逐一选择、快速拨动/吸附/震动、返回与重开保留选择，拍照自动套色；换胶卷不改变旧照片，重复编辑不叠色。
- ZIP 进度、取消系统分享、保存至文档、低磁盘空间；PNG 必须为完整目标尺寸，原图与标签一一配对。
- 使用 `python3 -m tools.g23_dataset analyze <设备导出的ZIP> --report report.html` 校验并查看报告。包括带真实定位信息的导出隐私检查。

## 云环境配置

`install_script` 与 `start_skill` 已保存为环境草稿，包含经过执行的 bootstrap、工具激活、核心构建、测试和移动平台限制。没有添加密钥或扩大网络策略。保存不代表发布；需要在环境设置中审阅、保存后发布，后续任务快照恢复也尚未另行验证。
