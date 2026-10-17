# 0.4.0 验收状态

本轮产品方向为简单胶卷相机。已实现照片删除、滑线预览、原片／胶片保存和不阻塞取景的后台冲洗，移除设置中的实验编辑与整组导出；新照片不再生成实验参数、统计和历史。Android 编译、自动化检查、签名和 APK 对齐通过。鸿蒙提交了对应原生源码与系统相册分析，尚未编译 HAP。

## 实际执行

| 检查 | 结果 | 覆盖范围 |
|---|---|---|
| Android assembleDebug | 通过 | Kotlin、CameraX、WorkManager、双 ABI JNI 与资源打包 |
| Android testDebugUnitTest | 38 passed，0 failed/error/skip | 原有兼容检查；新增 18 项覆盖胶卷／画幅快照、无实验文件、冲洗完成提交、处理中再次拍摄登记、删除竞态、失败和内存不足后继续队列、重试、中断恢复、旧照片删除、LUT 缓存、滑线像素、胶卷占位、画幅，以及系统图库写入／失败清理 |
| Android lintDebug | 0 error/fatal，24 warnings | 包括既有与视图绘制分配、国际化、工具构造器及备份声明警告；没有关闭检查 |
| Python unittest | 37 passed，0 failed/error/skip | 全部 15 款上传 LUT 的独立参考比较、真实 JPEG/PNG 编解码、资源哈希、原片不变及旧协议／数据工具兼容 |
| C++ ASan / UBSan | 通过 | LUT 输入、alpha／stride、基础中性跨瓦片及 10,000 个畸形输入，无报告内存错误 |
| CMake / CTest | 2 passed，0 failed | core 和 lut 独立 C ABI 检查 |
| apksigner | 通过 | v2 开发调试签名，证书 SHA-256 延续上一版 |
| zipalign | 通过 | `-c -P 16 4` |
| APK 内资源 | 通过 | 15 个用户原始 LUT 的 SHA-256 全部匹配 catalog，包含 arm64-v8a 与 x86_64 |
| 12 MP 主机新旧路径测速 | 通过 | 三次中位数 3.58 秒 → 0.57 秒，约 6.26 倍；编码前完整像素一致。不是手机实测，详见[性能报告](PERFORMANCE.md) |
| HarmonyOS SDK / ArkTS / HAP | 未执行 | 当前无 DevEco、鸿蒙 SDK、hvigor/ohpm 或签名环境 |
| Android / HarmonyOS 真机 | 未执行 | 当前无相机手机或可连接的设备；未声明真机拍摄、振动手感、图库或端到端耗时已经验收 |
| 远程 GitHub Actions | 未核验 | 本机已运行相应检查；远程 CI 结果不由本报告代替 |

## 安装包

包名 `lab.g23`，版本 `0.4.0` / versionCode `4`，应用名称“G23 相机”。文件：`downloads/G23-Lab-debug.apk`，21,454,059 bytes。

SHA-256：`74c123dd5e7b8c07ae8dace5d2dc0aa6180423384e7550695d9f03e384c4127f`。

开发签名证书 SHA-256：`90e3d67e0a78e3d685358f5377b1567d92748b4c9289906ae61e01c8bae3cd5d`。这不是商店发布签名。可以覆盖相同签名旧包，避免卸载时清除应用内照片。

## 自动化证据的边界

并发与删除测试使用真实文件和受控渲染任务来确定交错顺序；图库测试使用 ContentProvider 检查真实字节复制、pending 发布和失败清理。它们验证状态与数据完整性，不能替代 CameraX 相机硬件或系统图库实际显示。C++ LUT 算法另外由主机原始像素测试覆盖。

[相机布局](images/camera-0.4.png)、[胶卷轮盘](images/lut-picker-0.4.png)、[冲洗占位](images/album-developing-0.4.png)、[滑线对比](images/photo-comparison-0.4.png) 均为 Robolectric 原生控件测试渲染。红／蓝对比图是测试夹具，空取景框没有伪造真实拍摄画面。

日志位于 `/workspace/toolchains/android-build-v04-final.log`、`core-v04.log`、`cmake-v04.log`，签名记录 `.build/apk-signature-v04.log`；JUnit 与 Lint 报告在 `android/app/build/`。主机基准原始记录在 [film-host-0.4.json](performance/film-host-0.4.json)。

## 真机剩余验收

- 连续拍摄时快门能在 JPEG 落盘后恢复；后续冲洗不重新绑定相机、不弹“正在应用 LUT”。确认不同照片仍采用各自按快门时的 LUT 和画幅。
- 多张冲洗占位自动更新；缩放和滑线原片／胶片构图对齐。处理失败或存储不足时原片保留，重试可继续。
- 冲洗中、冲洗后分别删除，不出现照片复活。分别保存原片和胶片，确认系统相册实际内容、方向和色彩；Android 9 检查文档选择器。
- 关闭／重建 Activity、系统回收进程后恢复队列。后台调度可能延迟，强行停止应用后不承诺即时完成。
- 鸿蒙先在 API 12 目标 SDK 编译，再验证 TaskPool、N-API 生命周期、PixelMap、SaveButton 和系统相册。应用级队列在下次启动恢复，不声明已具备长期后台驻留。
