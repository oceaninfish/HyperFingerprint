# 锁屏指纹

澎湃 OS 4 的 LSPosed 模块。亮屏后显示屏下指纹图标，默认显示 5 秒。轻点锁屏或按下指纹感应区时，按所选时长重新计时。超时后图标隐藏。

模块通过系统的指纹绘制视图控制亮屏锁屏的图标。

## 安装

1. 在 [GitHub Releases](https://github.com/ocean910/FingerprintFiveSeconds/releases/latest) 下载并安装 `FingerprintFiveSeconds-1.1.0.apk`。已装 1.0.0 的设备可以直接覆盖安装。
2. 打开 LSPosed，启用「锁屏指纹」，勾选「系统界面」`com.android.systemui`。
3. 在 HyperMusicCover、HyperLight 等其他模块里关闭「隐藏指纹图标」选项，避免同一个图标被多个模块同时控制。
4. 重启手机使模块生效。停用时在 LSPosed 关闭本模块后重启。

## 设置时长

从桌面打开「锁屏指纹」，拖动滑块或点按减号、加号，从 1 到 20 秒中选择。设置自动保存，在页面显示「已同步到 LSPosed」后，下一次亮屏或点击会使用新时长。首次安装或更新后需让系统界面重新加载模块。

## 适配与验证

需要支持现代 Xposed API 102 的 LSPosed。实现依据 REDMI K90 Pro Max（`myron`）的 `OS4.0.6.0.XPMCNXM`（Android 17）系统界面类和资源编写。其他机型和系统版本的适配情况待验证。

启用后可用以下操作确认效果。

1. 将时长设为 5 秒。锁屏并亮屏，等待 5 秒，观察图标隐藏。
2. 轻点锁屏空白处，观察图标重新出现。隔 2 秒再点一次，从最后一次点击起等 5 秒。也可以分别设为 1 秒和 20 秒，确认图标按新时长隐藏。
3. 等图标隐藏后直接按指纹区，确认仍可指纹解锁。熄屏后再次唤醒，确认重新开始计时。

如果 LSPosed 日志中出现 `SystemUI fingerprint hooks unavailable on this build`，说明当前系统镜像缺少模块依赖的接口，系统会继续使用原有的绘制逻辑。

## 从源码构建

使用 JDK 21 和 Android SDK 37.2。设置 `ANDROID_HOME`，或者让 Android Studio 生成 `local.properties` 后，在项目目录执行 `gradlew.bat :app:assembleDebug`（Windows）或 `./gradlew :app:assembleDebug`（macOS、Linux）。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。从交付版切换到自编译调试版时，需要卸载再安装，因为两者的签名证书不同。

## 参考与依赖

- 指纹绘制接口、锁屏触摸和设置同步的实现参考 [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover)。
- 功能设计参考 [HyperLight](https://github.com/Xposed-Modules-Repo/com.kiminonawa.HyperLight) 的锁屏指纹隐藏功能。
- 项目使用 [libxposed API](https://github.com/libxposed/api) 和 [libxposed Service](https://github.com/libxposed/service) `102.0.0`。两者均采用 Apache-2.0 许可证。
