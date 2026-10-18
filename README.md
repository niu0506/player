# player

一个基于 **ExoPlayer (androidx.media3)** 的本地音视频播放器（Android 原生，Kotlin）。

自动扫描设备媒体库（视频/音频）生成播放列表，支持后台播放、进度续播、手势控制、变速播放、画中画与应用内更新。

## 功能特性

### 播放

- **本地媒体库扫描**：基于 MediaStore 扫描设备上的视频与音频，自动构建播放列表；自动监听媒体库变化（防抖合并的全量查询 + 差量对账），回前台时对账清理已被外部删除的文件——拥有对应类型完整权限时，对扫描未列出的条目再做一次单条精确查询确认后即刻清理，查询失败则退回「多次缺失 + 宽限期」保守判定，避免误删
- **按文件夹分组连播**：记录每个文件所属目录（API 29+ 取 `RELATIVE_PATH`，更低版本由绝对路径取父目录），列表按「文件夹 + 组内集数自然序」排序——`第2集` 排在 `第10集` 之前，同一部剧的分集连续相邻，自动连播不会在几部剧之间来回跳；已存列表在首次扫描时按扫描结果回填目录并重排（进度按 URI 记录，重排不影响续播）。**建议每部剧放在独立文件夹**：同一文件夹里混放多部剧时无法区分；读不到目录的条目统一排在列表末尾
- **播放控制**：播放/暂停、进度拖动、快退/快进 15s、随机播放、循环模式（顺序/单曲/列表）
- **音轨/字幕选择**：多音轨视频可切换音轨，内嵌字幕可切换
- **变速播放**：0.5x ~ 3.0x 共 7 档倍速；倍速由 Service 侧播放器持有，controller 重连（回前台）后按钮文案会重新同步，不会与真实速度脱节
- **后台播放**：前台服务（`MediaSessionService`）常驻，退到后台/锁屏仍持续播放，通知栏可控
- **耳机断开自动暂停**：拔掉耳机自动暂停；音频焦点由 MediaSession 会话托管的播放器内建处理（暂时丢失暂停并续播、可闪避时降音量、永久丢失只暂停），避免「双 App 同放」，被暂时打断时界面给出提示

### 进度续播

- 按 **URI** 独立记忆每个文件（音/视频统一）的播放进度，应用重启/切后台后自动恢复断点
- 进度每 5 秒落盘一次（快照无变化时跳过，零空闲 IO）；seek、暂停、任务移除、内存吃紧时立即落盘
- **末尾容差**：距末尾 5 秒以内（且不超过总时长 10%）的进度视为已看完，不写入断点——周期落盘的采样点本就常落在末尾几秒内，否则单曲循环每轮回绕都会被 seek 回片尾，表现为只重复最后几秒
- 「合并非覆盖」写入语义：0 值不覆盖已有非零进度；播放到末尾（含末尾容差）或删除条目时进度显式清除，防止残留进度「复活」

### 交互

- **手势控制**：
  - 单击 → 显隐控制栏
  - 双击左半屏 → 快退 15s；双击右半屏 → 快进 15s
  - 横向滑动 → 快进/快退（实时预览目标进度）
  - 左半屏上下滑动 → 调节亮度；右半屏上下滑动 → 调节音量
- **小窗（PiP）与全屏**：画中画小窗按视频真实宽高比自适应；全屏按真实比例完整显示（不放大裁剪画面，可能留黑边），系统返回键退出；播放视频时按 Home / 手势自动进入小窗（API 31+ 经 `setAutoEnterEnabled`，纯音频不进入）

### 更新

- **应用内更新**：经 GitHub Releases API 检查新版本
- **下载**：经系统 DownloadManager 从 GitHub Release 资产直连下载到系统公共「下载」目录（DownloadManager 以系统身份写入，应用无需存储权限）
- **不代调安装器**：本应用**不**调起系统安装器；校验通过后提示用户到系统「下载」目录手动安装（或点系统下载完成通知自行安装）
- **更新包完整性（fail-closed）**：必须校验 SHA-256 才判定下载通过——校验和取自 GitHub Release 资产的 `digest`；**拿不到校验和时判定下载失败并提示手动下载**（包名/版本号可被伪造，不足以作为信任依据）。校验和不匹配视为残包/篡改，直接判定下载失败
- **更新提示时机**：更新弹窗只在界面处于前台（resumed）时创建，检查期间切走则由 `onResume` 补弹；且不响应「点外部取消」，避免菜单收起等残留触摸把刚弹出的提示框立刻关掉
- **下载完成回调**：下载完成由系统 DownloadManager 的 `DOWNLOAD_COMPLETE` 广播驱动（校验、清理状态、提示手动安装）。该广播由「下载」提供方（另一个应用/uid）发出，动态接收方必须用 `RECEIVER_EXPORTED` 注册，否则会被系统以 `Exported Denial` 丢弃——表现为下载完成后毫无反应，且残留状态会在之后被误判
- **已安装则静默收尾**：用户手动安装更新后系统会删除安装包（下载记录也可能一并消失）。恢复未完成的下载时，若当前安装版本已达到下载版本，说明安装成功，静默清理状态而非误报「下载失败」
- **收尾**：应用替换成功后自动清理「下载」目录中的旧更新包（`MY_PACKAGE_REPLACED` 广播）

### 系统适配

- **Android 14+（API 34）**：支持 Selected Photos Access（仅访问选中的照片/视频）；部分授权时跳过「删除对账」，避免误删未授权文件
- **Android 13+（API 33）**：动态申请通知权限（前台服务通知）与媒体读取权限

## 技术栈

- 语言：Kotlin
- 最低支持 / 目标版本：minSdk 24 / targetSdk 37（compileSdk 37，亦满足 media3 1.11 的传递依赖要求）
- 播放器：androidx.media3 1.11.1（ExoPlayer + MediaSession + MediaController）
- 架构：前台服务（`PlayerService`）+ 单 Activity（`MainActivity`），经 MediaController 通信；源码扁平化为单包 5 文件，UI 逻辑拆分为 `GestureController`、`FullscreenPipHelper`、`MediaStoreScanner` 等职责类（见 `PlayerUi.kt` / `PlayerRepository.kt` / `UpdateManager.kt`）；依赖版本集中在 `gradle/libs.versions.toml`
- 控制栏：使用应用自有的尺寸、颜色、样式与图标资源；仅依赖 Media3 文档化的控制视图 ID 和图标属性，避免绑定其私有布局资源
- 持久化：Room 数据库（播放列表、进度映射、KV 分表存储，统一「合并写」入口）；schema 导出至 `app/schemas/` 并纳入版本控制。加载失败**不是终态**：会按需重试（30 秒冷却），失败期间的写入不再被静默丢弃，界面给出提示
- 更新下载：系统 DownloadManager 经 `setDestinationInExternalPublicDir` 写入系统公共「下载」目录（DownloadManager 以系统身份写入，应用无需存储权限；文件对用户/文件管理器可见，便于手动安装）。校验时经 `getUriForDownloadedFile` 的 content uri 读取，并复制到缓存临时文件以解析 APK 元数据。注意：`setDestinationUri` 只接受 `file://`，传 `content://`（MediaStore 目标）会抛 `IllegalArgumentException` —— 这正是 v1.4.8 修复的「点立即更新闪退」根因，勿回退为 content:// 目标
- UI：ViewBinding + RecyclerView（DiffUtil 后台差分刷新）
- 构建：AGP + Kotlin DSL (Gradle)，GitHub Actions CI 自动构建

## 构建

需要 JDK 21 与已配置的 Android SDK。Gradle daemon 与 GitHub Actions CI 均使用 JDK 21。

```bash
# 构建调试版 APK
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 运行本地单元测试
./gradlew testDebugUnitTest

# 静态检查（CI 同样会执行）
./gradlew lintDebug
```

构建依赖的 SDK 路径在本地 `local.properties`（`sdk.dir=...`）中配置，该文件已被 `.gitignore` 忽略。

### 数据库迁移（重要）

`PlayerRepository` 的 Room 数据库**没有**启用 `fallbackToDestructiveMigration`：升级 `@Database(version = ...)` 时若缺少对应 `Migration`，应用会在打开数据库时报错（`IllegalStateException: A migration from N to M was required but not found`），而**不会**静默清空用户的播放列表与全部进度。

新增字段/表时的正确流程：

1. 在 `PlayerDatabase` 的 `@Database` 上递增 `version`；
2. 在同处补充 `Migration(N, M)` 并加入 `Room.databaseBuilder(...).addMigrations(...)`；
3. 构建后确认 `app/schemas/com.example.player.PlayerDatabase/<M>.json` 已生成**并提交到版本控制** —— 该文件是后续迁移的 diff 基准，缺失会让后续迁移无法正确编写。

> 示例（v1 → v2，播放列表新增文件夹分组键）：实体字段写 `@ColumnInfo(defaultValue = "''")`，
> 迁移执行 `ALTER TABLE playlist ADD COLUMN folder TEXT NOT NULL DEFAULT ''`。
> 注意两者必须同时存在且字面量一致（`defaultValue = "''"` 要自带引号），否则迁移后 Room 的
> `TableInfo` 校验会报 `Migration didn't properly handle`。

> 早期版本使用 `fallbackToDestructiveMigration(dropAllTables = true)`，在升版本忘记写迁移时会静默删除全部用户数据。该兜底已移除，改为构建/运行期显式暴露。

同理，**加载失败不会被当成永久状态**：`PlayerRepository.ensureLoaded` 在失败后会重新发起加载（写入路径按 30 秒冷却重试，避免库永久损坏时被 2 秒一次的进度写反复触发重建），`awaitLoaded` 返回失败结果供界面提示，写入不再被静默丢弃。

### 签名（Release）

Release 签名信息按优先级取自：

1. 环境变量：`KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`（CI 经 Secrets 注入）
2. 本地文件 `keystore.properties`（已加入 `.gitignore`，不会提交）

两者都没有时，release 构建自动回退为无签名产物（`*-unsigned.apk`），构建不会失败。

### CI

`.github/workflows/build.yml`：push 到 `main` 或打 `v*` tag 时依次执行**单元测试 → lint → 构建签名 Release APK**（前两步失败即中止，回归不会进入产物）；PR 构建在无 Secrets 时回退无签名构建。

### 发布新版本

1. 递增 `app/build.gradle.kts` 的 `versionCode` 与 `versionName`；
2. 更新仓库根 `version.json` 的 `notes`（供 CI 生成 Release 说明；版本号以 `build.gradle.kts` 为唯一来源）；
3. 提交并推送 `main`，再推送与 `versionName` 同名的 `v<版本>` tag（CI 的 Release job 只在 `v*` tag 上触发）；
4. CI 自动构建**签名** Release APK 并创建 GitHub Release，资产名 `player-v<版本>-release.apk`，同时生成同名 `.sha256` 并在 Release 正文附上 `SHA-256`；
5. 应用内更新的 SHA-256 直接取自 GitHub Release 资产的 `digest`（GitHub 对上传资产自动计算）。若该字段缺失，应用会按 fail-closed **判定下载失败**，仅能手动下载安装。

## 安装

将构建出的 `app-debug.apk` 传输到设备后直接安装；或使用 Android Studio 运行到设备/模拟器。首次使用需授予媒体访问权限。应用内更新只负责下载并校验更新包，安装由用户在系统「下载」目录手动完成。

## 目录结构

```
app/src/main/java/com/example/player/
├── MainActivity.kt                  # UI 入口（含 PlayerApp 初始化）、播放列表、进度恢复与更新流程编排
├── PlayerRepository.kt              # 数据模型 + 媒体库扫描 + Room 数据库与统一合并写入口
├── PlayerService.kt                 # 前台服务、后台播放、进度持久化
├── PlayerUi.kt                      # 手势控制、全屏与画中画切换、播放列表适配器（DiffUtil 后台差分）
└── UpdateManager.kt                 # 应用内更新检查（GitHub Releases）+ 下载与校验（不代调安装器）

app/src/test/java/com/example/player/
└── PlayerTests.kt                   # 进度合并语义 / 进度末尾裁决 / 时长格式化 / 列表高亮越界防护 / 版本比较 / 安装包完整性校验 / 文件夹分组键与集数自然序

app/schemas/com.example.player.PlayerDatabase/
├── 1.json                           # Room 导出的 schema（须提交，作为后续 Migration 的 diff 基准）
└── 2.json                           # v2：playlist 新增 folder 列

app/src/main/res/values/
└── strings.xml                      # 全部用户可见文案（含控制栏与手势浮层，均经资源引用）
```

## License

未指定。
