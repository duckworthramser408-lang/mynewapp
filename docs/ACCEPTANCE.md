# 0.5.0 实际验证状态

Android 自然美颜已经接入原片解码与现有 LUT 之间，包含默认开启的三个独立控制、离线真实模型、同 LUT 滑线对比与原片重新出图。**完整小头未实现，提供的是轻微小脸；原生鸿蒙美颜尚未接入，未构建 HAP。没有手机／模拟器连接，不声明真机效果、相机兼容性或首次离线安装已验收。**

| 检查 | 结果 | 实际范围 |
|---|---|---|
| Android assembleDebug | 通过 | Kotlin、LiteRT 1.4.1、WorkManager、arm64-v8a / x86_64 JNI 与模型资源 |
| Android testDebugUnitTest | **46 passed，0 failed/error/skip** | 38 项原相机／LUT／保存兼容检查，加 8 项美颜配方、默认／独立持久化、快门快照、原片重出图、基线提交清理、模型 SHA 与无网络权限、真实初始化失败不改像素、8 种 EXIF 变换 |
| Android lintDebug | **0 error/fatal，25 warnings** | 视图绘制分配、既有备份／国际化／工具构造器等；未禁用检查 |
| Python unittest | **37 passed** | 全部 15 个原始 LUT 独立参考插值、资源哈希、编解码与旧协议兼容 |
| C++ ASan / UBSan | 通过，无报告 | 基础核心／LUT 与美颜，几何实际修改、位移上限、姿态与 image roll、alpha／stride、其他人脸重叠、非法输入保持不变；LUT 0 / 0.5 / 1 强度 |
| CMake / CTest | **3 passed** | core、lut、beauty |
| 真模型 + C++ 主机验证 | **9 类输入完成** | 三张真实照片与明确标记的合成眼镜、额头遮挡、弱光、双人、无人脸、12MP 放大压力样本；不等于真实刘海／实拍弱光或相机高分辨率验收 |
| 坐标参考对照 | 通过 | 与同权重 MediaPipe Python 官方图比较，归一化 xy RMS 0.0004016 |
| 无 delegate CPU 降级 | 主机通过 | 对 XNNPACK 点位 RMS 0.0000060；Android JNI 设备降级仍未验证 |
| 其他人脸保护／无人脸 | 主机通过 | 双人场景确实检测到 2 脸；另一检测框内像素保持不变；无人脸整图不变 |
| 法令纹与纹理保护 | 主机通过有限样本 | 真实笑脸淡纹单项最大参数改变 347 像素，局部且增亮幅度受限；默认更轻，不能表述为广泛样片质量验收 |
| apksigner / zipalign | 通过 | v2 调试签名，与旧版相同证书；`-c -P 16 4` |
| 模型 / LUT / ELF | 通过 | APK 内 15 LUT + 2 模型 SHA 匹配，双 ABI 每个 ELF PT_LOAD 均 ≥16KB；不是设备启动验证 |
| Android CameraX / LiteRT JNI 真机 | **未验证** | 无手机、模拟器、相机硬件或 /dev/kvm；Robolectric 不能运行 Android LiteRT JNI，另用真实主机模型测试，不伪造 JNI 推理结果 |
| HarmonyOS 7.0 / API 26 | **美颜未接入、HAP 未编译、设备未验证** | 已收到用户 Windows API 26 Release / SDK 26.0.0.105 元数据；云端没有 DevEco / SDK / hvigor / ohpm / HDC / 原生推理库 |
| GitHub Actions 远程结果 | 未核验 | 本地检查已运行，不用本报告代替远程 CI 状态 |

## APK

包名 `lab.g23`，版本 `0.5.0` / code `5`。仓库 `downloads/G23-Lab-debug.apk`，**33,538,602 bytes**。

SHA-256：`2412470457717ebf70bc6d8d0218cf1484eb73880e6198269583fb56e9738a2b`。

调试证书 SHA-256：`90e3d67e0a78e3d685358f5377b1567d92748b4c9289906ae61e01c8bae3cd5d`。可覆盖同签名旧版；卸载会清除应用私有照片。签名不是应用商店发布签名。

机器可读记录：[APK 验证](performance/apk-0.5.json)、[主机美颜与 LUT 分段记录](performance/beauty-host-0.5.json)。[0.4 验证记录](ACCEPTANCE-0.4.md) 留存供回溯。

## 效果与性能证据的范围

9 个输入实际运行本地模型；正脸包括真实眼镜照片，额外镜框、额头遮挡、弱光和双人由真实照片派生，已在用例名称标注 synthetic。12MP 是原图放大至 3200×3750 的内存／边界压力，**不是原生高分辨率拍摄细节验证**。未重新分发测试人像。

该次云端 12MP 记录：检测 5.55ms，共享美颜调用 459.91ms（含主机数据复制；其中几何 225.07ms、淡纹 134.37ms），LUT 456.75ms，JPEG 编码 35.05ms。这是一次主机运行、复用已加载模型，不是手机端到端性能；不包括 CameraX、原片解码、UI、持久队列等待与对比基线。

进程 RSS high-water 290,296KiB，包含主机 Python／OpenCV、输入数组、复制和先前用例；C++ ROI / 增益 scratch 12,971,412 bytes。不能当成 Android 每张照片的峰值内存。Android 代码已记录分段时间、scratch、heap 与进程 high-water，需设备采集。

肉眼检查了真实人像及放大差分图：默认极轻；法令纹仅在两侧窄带改变，未出现全脸漂白。自动断言检查鼻尖、嘴部中心固定及另一人脸像素不变。眼镜边缘、头发、鼻翼、嘴角和颈部在更多姿态下的视觉质量、身份与表情保留，仍需真实照片和设备验收，不用小规模主机检查作完整质量承诺。

## 实机剩余检查

- 安装后首次开启飞行模式拍摄，验证模型从安装包读取、默认美颜、各独立项与同 LUT 对比。
- 前置／后置、所有旋转方向、画幅与不同镜像设置；确定硬件输出与 EXIF 归一化没有重复反转。
- 正脸、轻侧脸、微笑／大笑、真实不同眼镜、刘海、口罩／手部遮挡、真实弱光、肤色差异、多人相交／贴边、无脸和真实高分辨率照片。检查背景直线、镜架、头发、鼻翼、嘴角、脖子。
- 确认小脸名称准确，没有完整小头承诺；如需完整小头，先补可靠分割与背景／颈肩处理。
- 连拍、Activity 重建／进程结束恢复、内存低、存储满、删除竞态、保存原片／胶片、模型不可用回退和反复重出图不累加。
- 分别测量 Android 设备及未来真正接入的原生 HarmonyOS；API 26 工具链就绪后先核验系统关键点拓扑，再选择并实际验证原生模型路径。

本轮日志：`/workspace/toolchains/android-beauty-build.log`、`core-beauty.log`、`cmake-beauty.log`、`beauty-validation.log`。JUnit / Lint 报告在 `android/app/build/`。

SDK 元数据确认后的配置与 Windows 本机检查入口见 [HARMONY_WINDOWS.zh.md](HARMONY_WINDOWS.zh.md)。compile/target API 已显式设为 26，真实 HAP 构建与推理状态仍待本机执行，不用配置变更代替验证。
