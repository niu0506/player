# player

一个基于 **ExoPlayer (androidx.media3)** 的本地音视频播放器（Android 原生，Kotlin）。

自动扫描设备媒体库（视频/音频）生成播放列表，支持后台播放、进度续播、手势控制、变速播放、画中画与应用内更新。

## 功能特性

### 播放

- **本地媒体库扫描**：基于 MediaStore 扫描设备上的视频与音频，自动构建播放列表；自动监听媒体库变化（防抖合并增量扫描），回前台时对账清理已被外部删除的文件
- **播放控制**：播放/暂停、进度拖动、快退/快进 15s、随机播放、循环模式（顺序/单曲/列表）
- **音轨/字幕选择**：多音轨视频可切换音轨，内嵌字幕可切换
- **变速播放**：0.5x ~ 3.0x 共 7 档倍速
- **后台播放**：前台服务（`MediaSessionService`）常驻，退到后台/锁屏仍持续播放，通知栏可控
- **耳机断开自动暂停**：拔掉耳机自动暂停；音频焦点由 ExoPlayer 托管，避免「双 App 同放」

### 进度续播

- 按 **URI** 独立记忆每个文件（音/视频统一）的播放进度，应用重启/切后台后自动恢复断点
- 进度每 2 秒落盘一次（快照无变化时跳过，零空闲 IO）；seek、暂停、任务移除、内存吃紧时立即落盘
- 「合并非覆盖」写入语义：0 值不覆盖已有非零进度；播放到末尾或删除条目时进度显式清除，防止残留进度「复活」

### 交互

- **手势控制**：
  - 单击 → 显隐控制栏
  - 双击左半屏 → 快退 15s；双击右半屏 → 快进 15s
  - 横向滑动 → 快进/快退（实时预览目标进度）
  - 左半屏上下滑动 → 调节亮度；右半屏上下滑动 → 调节音量
- **小窗（PiP）与全屏**：画中画小窗按视频真实宽高比自适应；全屏等比放大铺满（无黑边），系统返回键退出

### 更新

- **应用内更新**：检查 GitHub Releases 新版本（GitHub API 直连 + jsDelivr CDN 兜底），确认后经系统 DownloadManager 下载（CDN 反代加速在前、直连兜底，失败自动换源），完成后调起系统安装器
- **权限适配**：Android 8+ 的「安装未知应用」授权流程；应用替换成功后自动清理下载目录中的旧更新包

### 系统适配

- **Android 14+（API 34）**：支持 Selected Photos Access（仅访问选中的照片/视频）；部分授权时跳过「删除对账」，避免误删未授权文件
- **Android 13+（API 33）**：动态申请通知权限（前台服务通知）与媒体读取权限

## 技术栈

- 语言：Kotlin
- 最低支持 / 目标版本：minSdk 24 / targetSdk 35
- 播放器：androidx.media3 1.5.1（ExoPlayer + MediaSession + MediaController）
- 架构：前台服务（`PlayerService`）+ 单 Activity（`MainActivity`），经 MediaController 通信
- 持久化：SharedPreferences + JSON（播放列表与进度映射分离存储，统一「合并写」入口并跨线程互斥）
- 更新下载：系统 DownloadManager + FileProvider 暴露 APK 给安装器
- UI：ViewBinding + RecyclerView（DiffUtil 后台差分刷新）
- 构建：AGP + Kotlin DSL (Gradle)，GitHub Actions CI 自动构建

## 构建

需要 JDK 17 与已配置的 Android SDK。

```bash
# 构建调试版 APK
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 运行本地单元测试
./gradlew testDebugUnitTest
```

构建依赖的 SDK 路径在本地 `local.properties`（`sdk.dir=...`）中配置，该文件已被 `.gitignore` 忽略。

### 签名（Release）

Release 签名信息按优先级取自：

1. 环境变量：`KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`（CI 经 Secrets 注入）
2. 本地文件 `keystore.properties`（已加入 `.gitignore`，不会提交）

两者都没有时，release 构建自动回退为无签名产物（`*-unsigned.apk`），构建不会失败。

### CI

`.github/workflows/build.yml`：push 到 `main` 或打 `v*` tag 时自动构建签名 Release APK；PR 构建在无 Secrets 时回退无签名构建。

## 安装

将构建出的 `app-debug.apk` 传输到设备后直接安装；或使用 Android Studio 运行到设备/模拟器。首次使用需授予媒体访问权限；应用内更新安装还需在系统设置中授予本应用「安装未知应用」权限。

## 目录结构

```
app/src/main/java/com/example/player/
├── MainActivity.kt      # UI、播放列表、手势、PiP/全屏、进度恢复与更新下载安装
├── PlayerService.kt     # 前台服务、后台播放、进度持久化（统一合并写入口）
├── MediaListAdapter.kt  # 播放列表 RecyclerView 适配器（DiffUtil 后台差分）
├── MediaItemData.kt     # 数据模型与时长格式化工具
└── UpdateChecker.kt     # 应用内更新检查（GitHub Releases / jsDelivr 兜底）

app/src/test/java/com/example/player/
├── PlaybackProgressTest.kt              # 进度合并语义（非覆盖/0 值保护/移除优先）
├── ProgressDiskIOTest.kt                # 并发读写盘互斥（丢更新/进度复活回归）
├── MediaListAdapterCurrentPlayingTest.kt# 列表高亮越界防护
├── UpdateCheckerTest.kt                 # 语义化版本比较
└── FormatTimeTest.kt                    # 时长格式化
```

## License

未指定。
