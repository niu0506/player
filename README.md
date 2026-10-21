# player（影音盒）

基于 **ExoPlayer (androidx.media3)** 的本地音视频播放器（Android 原生，Kotlin）。

手动扫描设备媒体库（视频/音频）生成播放列表，启动/回前台静默对账；支持后台播放、进度续播、手势控制、变速播放、画中画与应用内更新。

## 功能

### 播放

- **媒体库扫描与对账**：基于 MediaStore 扫描视频/音频（手动「扫描」导入 ≥5 秒的媒体）；监听媒体库变化并防抖对账，启动/回前台静默刷新。拥有对应类型完整权限时，对缺失条目再做一次单条复核，确认删除即清理；查询失败或可移除卷未挂载时，退回「连续两轮缺失 + 24 小时宽限期」，避免误删。手动扫描会刷新已存在条目的文件名、时长与文件夹；静默扫描只清理/刷新，不主动追加新文件
- **按文件夹分组连播**：记录每个文件的目录（API 29+ 取 `RELATIVE_PATH`，更低版本取父目录），按「文件夹 + 组内自然序」排序（`第2集` 先于 `第10集`），同文件夹的分集连续相邻。播完当前文件夹最后一项后会继续播放下一文件夹（跨剧连续播放，不会在剧末自动停止）；扫描时刷新文件名 / 时长 / 目录并重排，进度按 URI 记录，不受重排影响。**建议每部剧放在独立文件夹**；读不到目录的条目排在末尾
- **播放控制**：播放/暂停、拖动进度、随机播放、循环模式（顺序/单曲/列表）；快退/快进 15s 由双击手势完成，控制栏不再显示对应按钮；播放视频时保持屏幕常亮；列表可删除条目并同步清除进度；播放出错自动跳到下一项，无下一项或整份列表均失败时停止
- **音轨 / 字幕**：多音轨视频可切换音轨，内嵌字幕可切换
- **变速播放**：0.5x ~ 3.0x 共 7 档；倍速由 Service 侧播放器持有，controller 重连后按钮文案会重新同步
- **后台播放**：前台服务（`MediaSessionService`）承载播放，后台/锁屏持续，通知栏可控；会话拒绝不可信外部控制器，避免第三方应用读取媒体信息（系统入口与通知监听器不受影响）
- **耳机断开自动暂停**：音频焦点由会话托管的播放器内建处理（暂时丢失暂停并续播、可闪避时降音量、永久丢失只暂停），被暂时打断时界面给出提示

### 进度续播

- 按 **URI** 独立记忆每个文件的播放进度，应用重启/切后台后自动恢复断点，并回到上次播放项
- 播放中每 5 秒落盘一次（快照无变化则跳过）；seek、暂停、任务移除、内存吃紧时立即落盘；落盘失败重试一次，仍失败则提示
- **末尾容差**：距末尾 5 秒以内（且不超过总时长 10%）的进度视为已看完，不写断点——避免单曲循环每轮回绕都被 seek 回片尾
- 「合并非覆盖」写入语义：0 值不覆盖已有非零进度；播放到末尾、删除条目或把进度拖回开头时显式清除断点，防止残留进度「复活」

### 交互

- **手势控制**：单击 → 显隐控制栏；双击左 1/3 屏 → 快退 15s、双击中间 1/3 屏 → 播放/暂停、双击右 1/3 屏 → 快进 15s；横向滑动 → 快进/快退（实时预览目标进度）；左半屏上下滑动 → 亮度，右半屏 → 音量。手势亮度会被记住，「更多」菜单可恢复跟随系统亮度
- **小窗（PiP）与全屏**：小窗按视频真实宽高比自适应；全屏按真实比例完整显示（不裁剪，可能留黑边），系统返回键退出；全屏顶部显示文件名、系统时间与电量，并随控制栏自动隐藏；播放视频时按 Home / 手势自动进入小窗（API 31+ 经 `setAutoEnterEnabled`，API 26–30 由 `onUserLeaveHint` 进入，纯音频不进入）

### 更新

- 经 GitHub Releases API 检查更新（「更多」菜单手动触发）；系统 DownloadManager 直连 Release 资产下载到公共「下载」目录，应用不代调安装器，由用户手动安装（或点下载完成通知）
- **完整性校验（fail-closed）**：校验包名 / 版本 / SHA-256；SHA-256 取自 Release 资产的 `digest`，拿不到或不匹配即判定失败，提示手动下载
- **回调与收尾**：`DOWNLOAD_COMPLETE` 由「下载」提供方发出，动态接收方须用 `RECEIVER_EXPORTED` 注册；更新弹窗仅在 `RESUMED` 时创建，切走则由 `onResume` 补弹；恢复下载时若安装版本已达到目标版本，视为安装成功并静默清理；应用替换后经 `MY_PACKAGE_REPLACED` 清理旧包

### 系统适配

- **Android 15+（API 35）**：targetSdk 37 强制 edge-to-edge；普通界面消费 `systemBars` / `displayCutout` insets，全屏 / PiP 时让画面铺满窗口
- **Android 14+（API 34）**：支持 Selected Photos Access；部分授权时跳过对应类型的删除对账，避免误删未授权文件
- **Android 13+（API 33）**：动态申请通知权限（前台服务通知）与媒体读取权限

### 已知限制

- 扫描使用 `MediaStore` 的 `EXTERNAL_CONTENT_URI`，只覆盖主存储卷；SD 卡 / OTG 上的媒体暂不导入
- 播放器表面使用 `TextureView`（圆角卡片与 PiP 过渡需要），功耗与 HDR 表现不如 `SurfaceView`；以 HDR 优先时可改为 `surface_view` 并放弃圆角裁剪
- 字幕只支持视频内嵌轨道，外部 `.srt` / `.ass` 尚未加载

## 技术栈

- 语言 Kotlin；minSdk 24 / targetSdk 37（compileSdk 37）
- 播放器 androidx.media3 1.11.1（ExoPlayer + MediaSession + MediaController）
- 架构：前台服务 `PlayerService` + 单 Activity `MainActivity`，经 MediaController 通信；源码扁平化为单包 5 文件，UI 与扫描逻辑拆分为 `GestureController` / `FullscreenPipHelper` / `MediaStoreScanner` 等职责类
- 控制栏：使用应用自有的尺寸、颜色、样式与图标资源；仅依赖 Media3 文档化的控制视图 ID 与图标属性，避免绑定其私有布局资源
- 持久化：Room（播放列表、进度映射、KV 表，统一写入入口）；schema 导出至 `app/schemas/` 并纳入版本控制；加载失败会提示，后续写入可触发重载（30 秒冷却）
- UI：ViewBinding + RecyclerView（DiffUtil 后台差分刷新）
- 构建：AGP + Kotlin DSL (Gradle)，GitHub Actions CI 自动构建；依赖版本集中在 `gradle/libs.versions.toml`

## 构建

需要 JDK 17+（推荐 21，CI 使用 21）与已配置的 Android SDK（路径写在本地 `local.properties` 的 `sdk.dir`，该文件已被 `.gitignore` 忽略）。

```bash
./gradlew assembleDebug      # 调试 APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest  # 运行本地单元测试
./gradlew lintDebug          # 静态检查（CI 同样会执行）
```

## 开发须知（易踩的坑）

- **Room 迁移**：数据库未启用 `fallbackToDestructiveMigration`。升级 `@Database(version)` 时必须补 `Migration(N, M)` 并加入 `addMigrations(...)`，否则打开数据库会直接报错。实体的 `@ColumnInfo(defaultValue = "''")` 要与迁移 SQL 字面量一致，否则 `TableInfo` 校验会报 `Migration didn't properly handle`。构建后提交 `app/schemas/io.github.niu0506.player.PlayerDatabase/<M>.json`，它是后续迁移的 diff 基准
- **更新下载目标**：DownloadManager 的 `setDestinationUri` 只接受 `file://`，传 `content://`（MediaStore 目标）会抛 `IllegalArgumentException` 导致闪退；因此使用 `setDestinationInExternalPublicDir` 写入公共「下载」目录，勿改回 `content://`

## 签名（Release）

签名信息按优先级取自：

1. 环境变量：`KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`（CI 经 Secrets 注入）
2. 本地文件 `keystore.properties`（已加入 `.gitignore`，不会提交）

两者都没有时，普通本地构建会回退为无签名产物（`*-unsigned.apk`），构建不会失败；但 CI 的 `v*` tag 构建强制要求签名 Secrets，缺少会直接失败，避免把未签名包发布给用户。

## CI 与发布

`.github/workflows/build.yml`：push 到 `main`、打 `v*` tag 或提 PR 时执行**单元测试 → lint → 构建 Release APK**（前两步失败即中止）。`v*` tag 额外要求：tag 与 `versionName` 一致、签名 Secrets 齐全，并用 `apksigner` 验证产物签名，随后触发 Release job；普通分支 / PR 无 Secrets 时才允许输出无签名包。

发布新版本：

1. 递增 `app/build.gradle.kts` 的 `versionCode` 与 `versionName`（`versionCode` 必须大于上一版，否则 Android 会拒绝覆盖安装）；
2. 更新仓库根 `version.json` 的 `notes`（供 CI 生成 Release 说明；版本号以 `build.gradle.kts` 为唯一来源）；
3. 提交并推送 `main`，再推送与 `versionName` 同名的 `v<版本>` tag；
4. CI 构建签名 Release APK（资产名 `player-v<版本>-release.apk`，同时生成同名 `.sha256`）并创建 GitHub Release，正文附 SHA-256；应用内更新直接取资产 `digest`，缺失则 fail-closed。

## 安装

将构建出的 `app-debug.apk` 传输到设备后直接安装，或使用 Android Studio 运行到设备/模拟器。首次使用需授予媒体访问权限。应用内更新只负责下载并校验更新包，安装由用户在系统「下载」目录手动完成。

> **包名迁移（1.6.0）**：从 1.6.0 起包名为 `io.github.niu0506.player`。旧版（`com.example.player`）无法覆盖安装或通过应用内更新升级到 1.6.0+，需要卸载旧版后安装新包；旧版的播放列表与进度不会迁移。

## 目录结构

```
app/src/main/java/io/github/niu0506/player/     # 单包，共 5 个文件
├── MainActivity.kt      # UI 入口（含 PlayerApp 初始化）、播放列表、进度恢复与更新流程编排
├── PlayerRepository.kt  # 数据模型 + 媒体库扫描 + Room 数据库与统一写入入口
├── PlayerService.kt     # 前台服务、后台播放、进度持久化、不可信控制器拦截
├── PlayerUi.kt          # 手势、全屏/画中画、播放列表适配器（DiffUtil 后台差分）
└── UpdateManager.kt     # 更新检查/下载/校验，及升级后清理包（PackageReplacedReceiver）

app/src/test/java/io/github/niu0506/player/PlayerTests.kt    # 纯逻辑单测（扫描对账、进度状态机、排序、校验、格式化等）
app/schemas/io.github.niu0506.player.PlayerDatabase/         # Room 导出 schema（须提交，作为迁移 diff 基准）
app/src/main/res/values/strings.xml                    # 全部用户可见文案（均经资源引用）
```

## License

MIT，见 [LICENSE](LICENSE)。
