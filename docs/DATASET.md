# 数据协议 1.0

应用内每个 `samples/G23_<uuid>` 保留 original.jpg、parameters.json、metadata.json 和独立 history.json。完整保存后有 edited.png 及两张 thumbnail JPEG；只有 Android 实际完成 RAW+JPEG 同拍时才存在 original.dng。不将 JPEG 改扩展名当 RAW。

- `parameters.json` 是完整的 `protocol/default_parameters.json` 形状：schema_version、render_pipeline_version、固定 processing_order、parameters。所有基础、8×3 HSL、4 条曲线、三段分级、Balance/Blending、质感、种子与几何信息都必须出现。
- `metadata.json` 格式见 `protocol/metadata.schema.json`。缺失的可靠拍摄参数为 null；曝光秒数、焦距 mm 等与参数面板的 UI 单位分开。capture.captured_at 使用 ISO8601 UTC，记录设备型号、平台、相机 ID 和原始尺寸。scene 保存人工多选标签、备注、风格特征、满意度和 1–5 评分。
- draft 表示还没有人工确认的可训练标签；只有“保存为 G23 训练样本”完成完整渲染、哈希后才写 approved。开始重新编辑便撤销 approved。历史和最终参数分开存储，undo/redo 游标可持久化。
- sha256 保存图片、缩略图、参数文档的 SHA-256。metadata 自身不对自己做递归哈希，ZIP manifest 对导出的 metadata 做校验。原图和 PNG 始终来自同一 ID 的目录，导出会校验 ID 与文件夹一致。

## ZIP

```
G23_Dataset_Export/
  manifest.json
  dataset.jsonl
  samples/G23_<uuid>/
    original.jpg
    original.dng             # 仅真实 RAW；隐私选项可能省略
    edited.png
    thumbnail_before.jpg
    thumbnail_after.jpg
    parameters.json
    metadata.json
  reports/dataset_summary.json
  reports/parameters_summary.csv
  README.md
```

JSONL 每行引用同一 ID 的相对路径，包括原图、成片、参数、元数据、scene、device_model、rating、approved 与管线版本。没有图片 Base64。CSV 展平所有数字参数，曲线和裁切数组引用 parameters.json。

导出默认筛选 approved，也可显式包含有完整衍生文件的 draft；没有 PNG 或文件校验不通过的 draft 会被拒绝。对重复的原始文件哈希报错（当前默认把重复输入视为数据质量问题；不同参数的多版本实验应单独管理，不能无提示混入默认数据集）。ZIP 不包含试错历史。

Python 和 Android 以文件流写 ZIP，不把全部图片一次读入内存；Python 使用 Zip64 和临时输出，成功后原子替换。鸿蒙通过系统 zlib 压缩暂存目录，使用额外磁盘而不是将数据集全部读入内存；进度目前按暂存样本完成数显示，压缩阶段为等待状态，仍需设备验证。

## 导出与读取

```bash
python3 -m tools.g23_dataset export ./samples ./G23.zip
python3 -m tools.g23_dataset export ./samples ./all.zip --include-drafts
python3 -m tools.g23_dataset analyze ./G23.zip --report ./report.html
```

读取器先校验 manifest，再逐行加载 JSONL，确认路径、ID、状态、参数版本和 metadata/manifest 的哈希相符；统计数值参数的 min/max/mean/count，输出原图和目标图并排 HTML。可以直接将 `analyze()` 中的 `safe_file(root,row['original'])`、`safe_file(root,row['edited'])` 和 `validate_parameters(...)` 用于后续训练 Dataset 类。这个工具尚不训练模型，不推断标签。

## 隐私与版本

本机 original 永不被 GPS 导出选项修改。Android 默认清理导出的 JPEG GPS；如有 DNG，此模式明确省略 DNG 并写 raw_omitted_for_privacy。关闭清理才可保留 RAW 导出。鸿蒙暂未实现 GPS 移除，明确提示保留 EXIF 或取消，不声称已经清理。部署前仍需用带定位 EXIF 的实际图片验证隐私选项及系统分享。

1.0 当前拒绝不认识的 schema/pipeline。后续迁移必须读取旧版本并显式生成新快照；原图不变，不能悄悄用新版公式重新解释旧训练标签。添加字段、改变 UI 映射或处理顺序要同步 Schema、默认值、平台类型、核心和协议测试，并更新版本。尚无跨版本自动迁移器。
