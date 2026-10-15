# 工程架构

```
CameraX / Camera Kit
    → 私有 samples/G23_<UUID>/original.jpg [+ real original.dng]
    → ImageDecoder / Image Kit / Pillow（方向、ICC → SDR sRGB）
    → 裁切与旋转（原图坐标）
    → g23_render RGBA8 → float 分块管线 → RGBA8
    → 原生预览 / 完整尺寸 PNG
    → parameters.json + metadata.json + 独立 history.json
    → 校验、ZIP、JSONL、CSV → 本地系统分享
```

每次渲染都读取保留的原图或原图派生的预览缓存，加同一份最终参数。不会把上一次压缩预览作为下一次运算输入。图像留在应用沙箱，Android 不声明网络权限；相机本身不请求定位。

## 边界

- `core/include/g23.h` 是两平台与桌面的唯一图像算法入口。版本 `1.0.0`。无平台头文件，无外部 C++ 依赖；每个调用的参数和临时缓冲独立。
- Android `Imaging` 负责解码、ICC、方向、几何、PNG；`NativeBridge` 负责 Bitmap/JNI。`SampleStore` 负责磁盘和确认状态；`EditHistory` 负责可持久化快照与游标；`DatasetExport` 负责流式 ZIP。
- 鸿蒙 `CameraService` 管理设备、输入输出和拍照回调；`Imaging` 管理 PixelMap 和 ICC；N-API 将重计算放到异步工作线程，使用保活的输入 ArrayBuffer 和外部输出缓冲。调用方在 Promise 完成前不能修改输入缓冲。
- `protocol/default_parameters.json` 和 Schema 是协议权威来源。鸿蒙显式类型的默认值和数值控件覆盖由自动测试检查，避免 ArkTS 使用无类型动态字段。
- 桌面适配器用 Pillow 实际解码和色彩管理，并调用同一 C ABI。它是算法参考与数据验证工具，不是替代手机相机的界面。

## 存储和异常

ID 使用 UUID，避免计数器竞争和跨设备碰撞。original 文件只在拍摄时新建。参数和元数据使用同目录临时文件、sync 和 rename 更新；编辑立即将状态设为 draft。PNG 和缩略图保存后计算 SHA-256，最后提交 approved 元数据。中途中断可能留下 draft、临时文件或哈希不匹配的样本，导出会拒绝它们，不会静默加入训练集；重新打开编辑器保存即可重新生成衍生文件。

history 独立记录参数快照、分支和游标，默认不进训练 ZIP。命名预设只含参数文档，不携带图片。相同名字保存覆盖规则可用于编辑；删除和导入导出走本地文件或系统文件选择器。

Android 用串行工作队列协调草稿、预览、完整渲染和导出；预览有代次编号，丢弃已过期任务和结果。鸿蒙预览合并为最新请求，原生运算不占 ArkUI 主线程。鸿蒙磁盘校验目前仍有同步文件读操作，需要在 SDK 验证后进一步转移到 TaskPool 并做性能测量。

核心仅分配带 halo 的 float 像素和 double 积分图，不分配全幅 float 工作图；输入输出为整幅 RGBA8。移动适配器还需 PixelMap/Bitmap 内存，执行显式预算检查；拒绝超过预算的完整渲染并保留原图，不谎报已经导出。预览可降到 640px 长边，保存走完整尺寸。12/24MP 的云端核心测量不代表手机端内存、发热和耗时。

## 当前限制

没有 GPU 加速、DNG 显影、HDR/广色域目标导出或 AI 模型。RAW 仅保留真实采集文件，编辑的输入是配对 JPEG。鸿蒙源码尚无 SDK 编译和真机证据，系统分享路由、摄影方向和 API 兼容性必须在 DevEco 中确认。跨编译器浮点数学不承诺 PNG 字节一致；同一构建、输入与参数的重复输出有自动测试。数据中固定保存算法版本，变更公式、插值或阶段顺序必须升级版本，不能在老版本下改变含义。
