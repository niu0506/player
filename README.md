# player

基于 **ExoPlayer (androidx.media3)** 的本地音视频播放器（Android 原生，Kotlin）。

自动扫描设备媒体库（视频/音频）生成播放列表，支持后台播放、进度续播、手势控制、变速播放、画中画与应用内更新。

## 功能

### 播放

- **媒体库扫描与对账**：基于 MediaStore 扫描视频/音频构建播放列表；监听媒体库变化（防抖合并的全量查询 + 差量对账），回前台时清理已被外部删除的文件。拥有对应类型完整权限时，对扫描未列出的条目再做一次单条精确查询，确认已删除即刻清理；查询失败则退回「多次缺失 + 宽限期」保守判定，避免误删
- **按文件夹分组连播**：记录每个文件的目录（API 29+ 取 `RELATIVE_PATH`，更低版本由绝对路径取父目录），按「文件夹 + 组内自然序」排序（`第2集` 先于 `第10集`），同一部剧的分集连续相邻，自动连播不会在几部剧之间来回跳；已存列表在首次扫描时回填目录并重排（进度按 URI 记录，重排不影响续播）。**建议每部剧放在独立文件夹**；读不到目录的条目排在列表末尾
- **播放控制**：播放/暂停、进度拖动、随机播放、循环模式（顺序/单曲/列表）；快进/快退 15s 由双击手势完成，控制栏不再显示对应按钮
- **音轨 / 字幕**：多音轨视频可切换音轨，内嵌字幕可切换
- **变速播放**：0.5x ~ 3.0x 共 7 档；倍速由 Service 侧播放器持有，controller 重连后按钮文案会重新同步
- **后台播放**：前台服务（`MediaSessionService`）常驻，后台/锁屏持续播放，通知栏可控
- **耳机断开自动暂停**：音频焦点由会话托管的播放器内建处理（暂时丢失暂停并续播、可闪避时降音量、永久丢失只暂停），被暂时打断时界面给出提示

### 进度续播

- 按 **URI** 独立记忆每个文件的播放进度，应用重启/切后台后自动恢复断点
- 每 5 秒落盘一次（快照无变化则跳过）；seek、暂停、任务移除、内存吃紧时立即落盘；落盘失败重试一次，仍失败则提示
- **末尾容差**：距末尾 5 秒以内（且不超过总时长 10%）的进度视为已看完，不写断点——避免单曲循环每轮回绕都被 seek 回片尾
- 「合并非覆盖」写入语义：0 值不覆盖已有非零进度；播放到末尾或删除条目时进度显式清除，防止残留进度「复活」

### 交互

- **手势控制**：单击 → 显隐控制栏；双击左 1/3 屏 → 快退 15s、双击中间 1/3 屏 → 播放/暂停、双击右 1/3 屏 → 快进 15s；横向滑动 → 快进/快退（实时预览目标进度）；左半屏上下滑动 → 亮度，右半屏 → 音量
- **小窗（PiP）与全屏**：小窗按视频真实宽高比自适应；全屏按真实比例完整显示（不放大裁剪画面，可能留黑边），系统返回键退出；全屏顶部显示文件名、系统时间与电量（替代被隐藏的系统状态栏）；播放视频时按 Home / 手势自动进入小窗（API 31+ 经 `setAutoEnterEnabled`，纯音频不进入）

### 更新

- 经 GitHub Releases API 检查新版本；下载经系统 DownloadManager 从 Release 资产直连到系统公共「下载」目录（DownloadManager 以系统身份写入，应用无需存储权限）
- **不代调安装器**：校验通过后提示用户到系统「下载」目录手动安装（或点系统下载完成通知自行安装）
- **更新包完整性（fail-closed）**：必须校验 SHA-256 才判定通过，校验和取自 Release 资产的 `digest`；**拿不到校验和即判定下载失败**并提示手动下载，不匹配视为残包/篡改
- **提示时机**：更新弹窗只在界面处于前台（`resumed`）时创建，检查期间切走则由 `onResume` 补弹；不响应「点外部取消」，避免残留触摸把刚弹出的提示框立刻关掉
- **下载完成回调**：由系统 `DOWNLOAD_COMPLETE` 广播驱动（校验、清理状态、提示手动安装）。该广播由「下载」提供方（另一个应用/uid）发出，动态接收方必须用 `RECEIVER_EXPORTED` 注册，否则会被系统以 `Exported Denial` 丢弃
- **已安装则静默收尾**：系统安装更新后会删除安装包（下载记录也可能一并消失）。恢复未完成的下载时，若当前安装版本已达到下载版本，说明安装成功，静默清理而非误报「下载失败」
- **收尾**：应用替换成功后自动清理「下载」目录中的旧更新包（`MY_PACKAGE_REPLACED` 广播）

### 系统适配

- **Android 14+（API 34）**：支持 Selected Photos Access（仅访问选中的照片/视频）；部分授权时跳过「删除对账」，避免误删未授权文件
- **Android 13+（API 33）**：动态申请通知权限（前台服务通知）与媒体读取权限

## 技术栈

- 语言 Kotlin；minSdk 24 / targetSdk 37（compileSdk 37，亦满足 media3 1.11 的传递依赖要求）
- 播放器 androidx.media3 1.11.1（ExoPlayer + MediaSession + MediaController）
- 架构：前台服务 `PlayerService` + 单 Activity `MainActivity`，经 MediaController 通信；源码扁平化为单包 5 文件，UI 逻辑拆分为 `GestureController` / `FullscreenPipHelper` / `MediaStoreScanner` 等职责类（见 `PlayerUi.kt`、`PlayerRepository.kt`、`UpdateManager.kt`）；依赖版本集中在 `gradle/libs.versions.toml`
- 控制栏：使用应用自有的尺寸、颜色、样式与图标资源；仅依赖 Media3 文档化的控制视图 ID 与图标属性，避免绑定其私有布局资源
- 持久化：Room 数据库（播放列表、进度映射、KV 分表存储，统一写入入口）；schema 导出至 `app/schemas/` 并纳入版本控制；加载失败**不是终态**，会按需重试（30 秒冷却）并提示，写入不静默丢弃
- UI：ViewBinding + RecyclerView（DiffUtil 后台差分刷新）
- 构建：AGP + Kotlin DSL (Gradle)，GitHub Actions CI 自动构建

## 构建

需要 JDK 21 与已配置的 Android SDK（路径写在本地 `local.properties` 的 `sdk.dir`，该文件已被 `.gitignore` 忽略）。Gradle daemon 与 CI 均使用 JDK 21。

```bash
./gradlew assembleDebug      # 调试 APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest  # 运行本地单元测试
./gradlew lintDebug          # 静态检查（CI 同样会执行）
```

## 开发须知（易踩的坑）

- **Room 迁移**：数据库**没有**启用 `fallbackToDestructiveMigration`。升级 `@Database(version)` 时必须补 `Migration(N, M)` 并加入 `Room.databaseBuilder(...).addMigrations(...)`，否则打开数据库直接报错（而不会静默清空用户数据）。构建后确认 `app/schemas/com.example.player.PlayerDatabase/<M>.json` 已生成**并提交**——它是后续迁移的 diff 基准。另外，实体的 `@ColumnInfo(defaultValue = "''")` 与迁移 SQL 的字面量必须同时存在且一致，否则 `TableInfo` 校验会报 `Migration didn't properly handle`。
- **更新下载目标**：DownloadManager 的 `setDestinationUri` 只接受 `file://`，传 `content://`（MediaStore 目标）会抛 `IllegalArgumentException` 导致闪退，因此使用 `setDestinationInExternalPublicDir` 写入公共「下载」目录，勿改回 `content://`。
- **下载完成广播**：必须用 `RECEIVER_EXPORTED` 注册，原因见上文「更新」一节。
- **数据加载失败**：`PlayerRepository.ensureLoaded` 会在失败后按 30 秒冷却重试（避免库永久损坏时被高频进度写反复触发重建），`awaitLoaded` 返回失败结果供界面提示。

## 签名（Release）

签名信息按优先级取自：

1. 环境变量：`KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`（CI 经 Secrets 注入）
2. 本地文件 `keystore.properties`（已加入 `.gitignore`，不会提交）

两者都没有时，release 构建自动回退为无签名产物（`*-unsigned.apk`），构建不会失败。

## CI 与发布

`.github/workflows/build.yml`：push 到 `main` 或打 `v*` tag 时依次执行**单元测试 → lint → 构建签名 Release APK**（前两步失败即中止，回归不会进入产物）；PR 构建在无 Secrets 时回退无签名构建。

发布新版本：

1. 递增 `app/build.gradle.kts` 的 `versionCode` 与 `versionName`；
2. 更新仓库根 `version.json` 的 `notes`（供 CI 生成 Release 说明；版本号以 `build.gradle.kts` 为唯一来源）；
3. 提交并推送 `main`，再推送与 `versionName` 同名的 `v<版本>` tag（CI 的 Release job 只在 `v*` tag 上触发）；
4. CI 自动构建**签名** Release APK 并创建 GitHub Release，资产名 `player-v<版本>-release.apk`，同时生成同名 `.sha256` 并在 Release 正文附上 `SHA-256`；
5. 应用内更新的 SHA-256 直接取自 GitHub Release 资产的 `digest`（GitHub 对上传资产自动计算）。若该字段缺失，应用会按 fail-closed **判定下载失败**，仅能手动下载安装。

## 安装

将构建出的 `app-debug.apk` 传输到设备后直接安装，或使用 Android Studio 运行到设备/模拟器。首次使用需授予媒体访问权限。应用内更新只负责下载并校验更新包，安装由用户在系统「下载」目录手动完成。

## 目录结构

```
app/src/main/java/com/example/player/     # 单包，共 5 个文件
├── MainActivity.kt      # UI 入口（含 PlayerApp 初始化）、播放列表、进度恢复与更新流程编排
├── PlayerRepository.kt  # 数据模型 + 媒体库扫描 + Room 数据库与统一写入入口
├── PlayerService.kt     # 前台服务、后台播放、进度持久化
├── PlayerUi.kt          # 手势控制、全屏与画中画切换、播放列表适配器（DiffUtil 后台差分）
└── UpdateManager.kt     # 应用内更新检查（GitHub Releases）+ 下载与校验（不代调安装器）

app/src/test/java/com/example/player/PlayerTests.kt    # 纯逻辑单测（进度合并与末尾裁决、排序、校验、格式化等）
app/schemas/com.example.player.PlayerDatabase/         # Room 导出 schema（须提交，作为迁移 diff 基准）
app/src/main/res/values/strings.xml                    # 全部用户可见文案（均经资源引用）
```

## License

未指定。
