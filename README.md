# player

一个基于 **ExoPlayer (androidx.media3)** 的本地音视频播放器（Android 原生，Kotlin）。

自动扫描设备媒体库（视频/音频）生成播放列表，支持后台播放、进度续播、手势控制、变速播放与多种循环模式。

## 功能特性

- **本地媒体库扫描**：基于 MediaStore 扫描设备上的视频与音频，自动构建播放列表
- **播放控制**：播放/暂停、进度拖动、快速进退、随机播放、循环模式（顺序/单曲/列表）
- **手势控制**：
  - 左右滑动 → 快进/快退
  - 左侧上下滑动 → 调节亮度
  - 右侧上下滑动 → 调节音量
  - 双击 → 快进 15s
- **变速播放**：倍速调节
- **后台播放**：前台服务常驻，退到后台仍持续播放
- **进度续播**：按文件 URI 独立记忆每个文件的播放进度，应用重建/重启后自动恢复断点
- **小窗（PiP）与全屏**：支持画中画模式和全屏播放
- **耳机断开自动暂停**：拔掉耳机自动暂停播放
- **Android 14 适配**：支持 Selected Photos Access（仅访问选中的媒体）

## 技术栈

- 语言：Kotlin
- 播放器：androidx.media3 (ExoPlayer + MediaSession)
- 架构：前台服务 (`PlayerService`) + 单 Activity (`MainActivity`)
- 持久化：SharedPreferences + JSON
- 构建：AGP + Kotlin DSL (Gradle)

## 构建

需要 JDK 17 与已配置的 Android SDK。

```bash
# 构建调试版 APK
./gradlew assembleDebug

# 产物：
# app/build/outputs/apk/debug/app-debug.apk
```

构建依赖的 SDK 路径在本地 `local.properties`（`sdk.dir=...`）中配置，该文件已被 `.gitignore` 忽略。

## 安装

将构建出的 `app-debug.apk` 传输到设备后直接安装；或使用 Android Studio 运行到设备/模拟器。首次使用需授予媒体访问权限。

## 目录结构

```
app/src/main/java/com/example/player/
├── MainActivity.kt      # UI、播放列表、控制与进度恢复
├── PlayerService.kt     # 前台服务、后台播放、进度持久化
├── MediaListAdapter.kt  # 播放列表 RecyclerView 适配器
└── MediaItemData.kt     # 数据模型与工具函数
```

## License

未指定。