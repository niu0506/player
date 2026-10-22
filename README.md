# player（影音盒）

基于 **Media3 / ExoPlayer** 的 Android 本地音视频播放器，Kotlin + 单 Activity + 前台服务。

扫描设备媒体库生成播放列表，支持后台播放、断点续播、手势控制、变速、画中画和应用内更新。

## 功能

**媒体库**
- 手动扫描 MediaStore 中的视频/音频，导入时长 ≥ 5 秒的媒体。
- 监听媒体库变化并防抖对账；启动和回前台时静默刷新。
- 删除判定采用保守策略：权限完整时单条复核，否则要求连续两轮缺失且超过 24 小时宽限期。
- 手动扫描会刷新文件名、时长和文件夹；静默扫描只清理和刷新，不追加新文件。
- 按「文件夹 + 文件名自然序」排列，同文件夹的分集连续播放，跨文件夹自动续播。

**播放**
- 播放/暂停、拖动进度、随机播放、顺序/单曲/列表循环。
- 多音轨切换、内嵌字幕切换、0.5x–3.0x 变速。
- 双击左/中/右区域快退、播放暂停、快进；横向滑动预览进度；左右半屏上下滑动调亮度/音量。
- 视频播放时保持亮屏；播放出错自动跳过下一项，全部失败时停止。
- 前台服务承载播放，支持后台/锁屏控制和耳机断开自动暂停。
- 全屏支持横屏沉浸式显示，顶部信息条显示文件名、时间和电量。
- PiP 小窗按视频真实比例自适应；播放视频时按 Home 可自动进入小窗。

**进度**
- 按 URI 记录播放进度，重启或切后台后恢复断点及上次播放项。
- 播放中每 5 秒落盘；seek、暂停、任务移除、内存紧张时立即落盘。
- 距末尾 5 秒以内（且不超过总时长 10%）视为看完，不保留断点。
- 0 值不覆盖已有进度；播完、删除条目或拖回开头时显式清除断点。

**更新**
- 通过 GitHub Releases API 检查更新，由系统 DownloadManager 下载到公共「下载」目录。
- 校验包名、版本号和 SHA-256；缺少校验和或校验失败直接判定失败（fail-closed）。
- 应用不代调安装器，由用户在系统「下载」目录手动安装。

## 环境

- JDK 17+（CI 使用 21）
- Android SDK，路径写入本地 `local.properties` 的 `sdk.dir`
- minSdk 24 / targetSdk 37 / compileSdk 37

## 构建

```bash
./gradlew assembleDebug      # 调试包：app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest  # 单元测试
./gradlew lintDebug          # 静态检查
```

## 项目结构

```
app/src/main/java/io/github/niu0506/player/
├── MainActivity.kt      # 应用入口、播放列表 UI、扫描对账、断点恢复、流程编排
├── PlayerRepository.kt  # 数据模型、MediaStore 扫描、排序、Room 持久化
├── PlayerService.kt     # 前台播放服务、媒体会话、进度落盘、错误处理
├── PlayerUi.kt          # 手势、全屏/PiP、播放列表适配器
└── UpdateManager.kt     # 检查更新、下载、校验与升级后清理

app/src/test/java/io/github/niu0506/player/PlayerTests.kt  # 纯逻辑单元测试
app/schemas/                                               # Room 导出 schema（需提交）
```

## 技术栈

- Kotlin，AGP + Gradle Kotlin DSL，版本集中在 `gradle/libs.versions.toml`
- Media3 1.11.1：ExoPlayer + MediaSession + MediaController
- Room（播放列表、进度、KV），ViewBinding + RecyclerView（DiffUtil）

## 开发注意事项

- **Room 迁移**：数据库未启用 `fallbackToDestructiveMigration`。升级版本必须补 `Migration` 并加入 `addMigrations(...)`，同时提交 `app/schemas/` 下对应 JSON。
- **下载目标**：DownloadManager 的 `setDestinationUri` 只接受 `file://`，因此使用 `setDestinationInExternalPublicDir` 写入公共「下载」目录，不要改成 `content://`。
- **签名**：优先读取环境变量 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，其次读取本地 `keystore.properties`。两者都没有时本地构建输出无签名包，CI 的 `v*` tag 构建则强制要求签名。
- **发布**：递增 `app/build.gradle.kts` 中的 `versionCode` / `versionName`，更新根目录 `version.json` 的 `notes`，推送与版本号同名的 `v<version>` tag。

## 已知限制

- 仅扫描主存储卷，SD 卡 / OTG 媒体暂不导入。
- 使用 `TextureView`（圆角与 PiP 过渡需要），功耗和 HDR 表现不如 `SurfaceView`。
- 字幕仅支持视频内嵌轨道，暂不支持外部 `.srt` / `.ass`。
- 1.6.0 起包名为 `io.github.niu0506.player`，与旧版 `com.example.player` 不兼容，需卸载旧版后安装。

## License

MIT，见 [LICENSE](LICENSE)。
