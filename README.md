# player（影音盒）

基于 **Media3 / ExoPlayer** 的 Android 本地音视频播放器：Kotlin + 单 Activity + 前台服务。

## 功能

- **媒体库**：扫描 MediaStore 视频/音频，按「文件夹 + 文件名自然序」排列；手动扫描全量刷新并提示结果，静默扫描同样对账增删改，新增只在列表顶部轻提示。
- **播放**：后台/锁屏播放、断点续播、自动连播、多音轨与内嵌字幕、0.5x–3.0x 变速、随机与循环；出错自动跳过。
- **交互**：双击快退/暂停/快进，滑动调进度、亮度、音量；全屏沉浸式显示，PiP 按视频比例自适应；支持从文件管理器「用影音盒打开」或分享音视频直接入队播放。
- **进度**：按 URI 记录，播放中定时落盘，暂停/seek/切后台立即落盘；看完不保留断点。
- **更新**：GitHub Releases 检查更新，DownloadManager 下载，校验包名、版本与 SHA-256 后调起系统安装器。

## 环境

JDK 17+（CI 使用 21）；Android SDK 路径写入 `local.properties` 的 `sdk.dir`；minSdk 24 / targetSdk 37 / compileSdk 37。

## 构建

```bash
./gradlew assembleDebug      # 调试包
./gradlew testDebugUnitTest  # 单元测试
./gradlew lintDebug          # 静态检查
```

## 项目结构

```
app/src/main/java/io/github/niu0506/player/
├── MainActivity.kt      # 入口、列表 UI、扫描对账、流程编排
├── PlayerRepository.kt  # 数据模型、MediaStore 扫描、排序、Room 持久化
├── PlayerService.kt     # 前台服务、MediaSession、进度落盘、错误处理
├── PlayerUi.kt          # 手势、全屏/PiP、列表适配器
└── UpdateManager.kt     # 检查更新、下载、校验与安装
app/src/test/            # 纯逻辑单元测试
app/schemas/             # Room 导出 schema（需提交）
```

## 技术栈

- Kotlin + Gradle Kotlin DSL，版本集中在 `gradle/libs.versions.toml`
- Media3：ExoPlayer + MediaSession + MediaController
- Room（播放列表、进度、KV），ViewBinding + RecyclerView

## 开发注意事项

- **Room 迁移**：未启用破坏性迁移，升级必须补 `Migration` 并提交 `app/schemas/` 下的 JSON。
- **下载目标**：DownloadManager 只接受 `file://`，更新包直接落到应用专属外部目录 `Android/data/<包名>/files/updates`（`setDestinationInExternalFilesDir`），校验与安装共用这一份，不要再复制到 `filesDir`，避免出现两份安装包。
- **签名**：优先读环境变量 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，其次读 `keystore.properties`；都缺失时本地输出无签名包，CI 的 `v*` tag 构建强制要求签名。
- **发布**：`version.json` 是版本唯一来源——递增其中的 `versionCode` / `versionName` 并更新 `notes`，推送同名 `v<versionName>` tag。CI 会校验 tag、notes 与 `versionName` 一致，并核对构建出的 APK。

## 已知限制

- 仅扫描主存储卷，SD 卡 / OTG 媒体暂不导入。
- 使用 `TextureView`（圆角与 PiP 过渡需要），功耗和 HDR 表现不如 `SurfaceView`。
- 字幕仅支持视频内嵌轨道，暂不支持外部 `.srt` / `.ass`。

## License

MIT，见 [LICENSE](LICENSE)。
