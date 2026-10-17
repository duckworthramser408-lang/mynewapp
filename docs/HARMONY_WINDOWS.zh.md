# 使用现有 Windows DevEco / API 26 SDK

2026-10-17，用户提供了实际 `sdk-pkg.json`：

| 项目 | 已知值 |
|---|---|
| DevEco 安装目录 | `E:\DevEco Studio` |
| SDK 根目录 | `E:\DevEco Studio\sdk\default` |
| metadata 结构 | `meta` + `data`，版本信息在 `data` 内 |
| API | `26` |
| displayName | `HarmonyOS 26.0.0` |
| platformVersion / package path | `26.0.0` / `HarmonyOS-26.0.0` |
| SDK 构建版本 | `26.0.0.105` |
| 发布类型 | `Release` |

本机安装信息已明确。云端无法直接读取这块 Windows 磁盘；原生 HAP 需要在该电脑运行真实工具链，或另外提供可供云端使用的 SDK。下面的检查入口使用已有安装，默认仅检查。可选构建沿用项目自己的签名配置；SDK 安装和向设备部署不在脚本范围内。

## 一次运行收集必要信息

1. 从 GitHub 下载当前整个仓库并解压；也可更新现有 Git 检出。保留 `harmony/`、`core/` 和 `tools/` 的相对位置。
2. 在解压后的仓库根目录（能看到 `README.md` 与 `tools`）打开 PowerShell。
3. 执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\harmony_windows.ps1
```

脚本默认使用上表中的两个路径。`-ExecutionPolicy Bypass` 只用于这次进程执行仓库内的脚本，不修改机器策略；可先查看脚本内容。

输出在仓库根目录的 `.build\harmony-windows\`：

- `environment.txt`：实际 SDK 组件元数据、项目编译／目标／最低 API、Node / Hvigor / OHPM / Java 的位置，以及是否执行构建。
- `sdk-face-api.txt`：当前 SDK 内人脸、视觉、关键点、分割、MindSpore、相机相关声明文件的名称和有限接口摘录。报告中会明确写出这些声明不等于推理／精度验证。

把这两个文本文件回传即可。检查报告只汇总 SDK 元数据、工具路径和 API 声明；输出目录 `.build` 已被 Git 忽略。

SDK 没有这些名称的文件不代表一定不支持：Kit 聚合、命名变化或模块未安装都需要根据报告进一步判断。仅有检测框／少量五官点也不能直接假定满足 468 点几何和局部淡纹需求。

## 在本机实际构建现有 HAP

先在 DevEco Studio 打开仓库的 `harmony/` 项目并完成同步。如果要安装手机，在 IDE 里配置自己的调试签名；签名资料留在本机。

项目默认 product 已显式设置 `compileSdkVersion: 26`、`targetSdkVersion: 26`。原 `compatibleSdkVersion: 5.0.0(12)` 仍是最低兼容声明，不是编译目标；保留它不代表已经在 API 12 设备验证。

检查报告中的工具都找到后，可在同一 PowerShell 中执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\harmony_windows.ps1 -Build
```

构建模式依次调用实际安装的 OHPM `install` 和 Node / Hvigor `assembleHap`，使用 default product、entry/default、debug。该操作会安装项目依赖并生成普通构建缓存／锁文件；首次依赖安装可能访问工具链的官方仓库。

临时 `DEVECO_SDK_HOME` 指向包含 `default` 的 SDK 目录，Java / Node 使用 DevEco 安装下的工具；脚本退出会恢复进程环境。若实际 DevEco 布局不同，报告会列出 NOT FOUND，脚本不会臆造工具或安装其他版本。也可直接使用 IDE 的 Build HAP，并回传 Build 窗口的第一条错误。

构建日志同在 `.build\harmony-windows\ohpm.log` 和 `hvigor.log`。只有工具成功且发现本次新生成的 `.hap`，才报告构建产物和 SHA-256；已有旧 HAP 不计作成功。未签名 HAP 不能据此声称可直接安装手机。

## 当前验证边界

云端使用官方 PowerShell 7.4.6 的真实解析器／运行时检查脚本，用明确标记的元数据与声明夹具验证目录含空格、`data` 结构、API 26 匹配、API 不匹配／无效目录拒绝、未找到工具和禁止在 Linux 调用 Windows 构建分支。夹具不是 SDK、模型或人脸推理结果；不当作 HAP 构建证据。Windows PowerShell 5.1、真实 DevEco 工具布局、Hvigor 插件对 API 26 产品的校验、ArkTS 编译、模型推理和设备行为尚待用户本机执行。

原生鸿蒙自然美颜仍未接入；先依据 SDK 声明与真实构建确定系统人脸能力和可用推理路径。Android 美颜 APK 和模型资源维持已交付的版本。
