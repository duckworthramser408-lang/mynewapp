# 鸿蒙照片能否自动保存到系统相册

可以通过系统接口把照片保存进系统相册，但要区分相机提供的照片资源和应用自己生成的 LUT 成片。当前原生源码走 `photoAvailable` 获取 JPEG、私有存储冲洗，再由用户选择保存原片或胶片。这条流程不会仅因私有文件已写入而自动出现在系统相册。

## 当前实现

0.4 的照片预览提供原片／胶片选择，再通过 ArkUI `SaveButton` 的成功回调，立即调用 `MediaAssetChangeRequest.createImageAssetRequest()` 和 `PhotoAccessHelper.applyChanges()`。这是官方文档支持的临时授权保存方式，无需为读取其他照片申请权限，也不把广泛写图库权限视作已获授权。

Android 兼容模式运行 APK 时使用 Android 的 MediaStore 保存路径；HarmonyOS NEXT 原生应用则使用上述鸿蒙接口，二者不能混为一个安装包。

## 相机原片的直接落库路径

Camera Kit 自 API 12 提供 `photoAssetAvailable`，返回系统 `PhotoAsset`。官方两阶段拍照示例在收到资源后调用 `MediaAssetChangeRequest(photoAsset).saveCameraPhoto()`，然后 `applyChanges()` 保存照片。这条系统相机资源路径可以由程序在拍摄后触发保存，无需每张都再经过自建文件的安全保存按钮。

但是：

- 官方不建议同时注册 `photoAvailable` 与 `photoAssetAvailable`；当前实现不能简单增加另一个回调就宣称两条路径同时工作。
- 支持的设备可能先提供轻处理图片，再把高质量图片回填图库。直接修改同一个相机资源来套 LUT，有被第二阶段原片回填覆盖的风险。
- 自己编码的 LUT JPEG 是新资源，不能假定继承了系统相机资源的保存能力。需要另行验证目标 SDK 的资源变更接口、可获得的图库写入授权，以及最终保存的是胶片还是被系统回填的原片。

因此当前交付选择明确的“保存原片／胶片”操作，没有加入未经验证的“鸿蒙静默自动保存胶片”开关。若后续启用自动保存，应先在目标机型验证相机资源流程，并将 LUT 成片作为独立资源保存，避免系统回填覆盖。

## 证据与验证范围

本次核对了 OpenHarmony 官方 API 和开发文档。它们是平台接口依据，不替代 HarmonyOS NEXT 目标 SDK 编译与真机验收；当前云环境没有鸿蒙 SDK 或设备。

- [拍照：photoAvailable 获取的 JPEG 需另存媒体库](https://github.com/openharmony/docs/blob/master/en/application-dev/media/camera/camera-shooting.md)
- [保存媒体库资源：SaveButton 和授权弹窗](https://github.com/openharmony/docs/blob/master/en/application-dev/media/medialibrary/photoAccessHelper-savebutton.md)
- [两阶段拍照：photoAssetAvailable、落库与系统高质量回填](https://github.com/openharmony/docs/blob/master/en/application-dev/media/camera/camera-deferred-capture.md)
- [MediaAssetChangeRequest / saveCameraPhoto](https://github.com/openharmony/docs/blob/master/en/application-dev/reference/apis-media-library-kit/arkts-apis-photoAccessHelper-MediaAssetChangeRequest.md)

真机验收需要确认：保存按钮授权成功／拒绝、原片／胶片各自落库、方向与 sRGB、连续拍摄、后台中断恢复，以及等待系统高质量回填后已保存的 LUT 图片仍保持色调。
