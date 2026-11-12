# player（影音盒）

Android 本地音视频播放器：Kotlin + 单 Activity + `MediaSessionService` 前台服务，播放内核为 **Media3 / ExoPlayer**，本地数据用 **Room** 持久化。

- 包名 / applicationId：`io.github.niu0506.player`
- 应用名：影音盒
- 最低支持 Android 7.0（API 24）

## 功能

### 媒体库

- 扫描 MediaStore 的视频与音频，按「文件夹 + 文件名自然序」排列；同一文件夹的条目聚在一起，未分组条目排在最后（`naturalCompare` 逐段比较数字段，`1.10` 排在 `1.9` 之后）。
- 音频会过滤铃声、通知音、闹钟、非音乐条目与录音；新增条目要求时长 ≥ 5 秒，时长未知或过短的片段不会自动入库。
- 手动扫描（顶部「扫描」按钮）：全量对账增 / 删 / 改，结果在列表顶部内联提示；无变化时按钮文案短暂变为「无新增」。
- 静默扫描：启动时自动执行一次，并在 MediaStore 内容变化（ContentObserver，800ms 去抖）后再次对账；仅在有新增时于列表顶部轻提示。
- 缺失文件对账：扫描中消失的条目需「至少第二次未见 + 距首次缺失满 24 小时」才会被移除；当外部卷均已挂载且持有完整读权限时，可先按 URI 单条查询确认已删除，从而立即移除。
- 外部来源（文件管理器打开 / 分享）加入的条目不属于媒体库，只参与列表，不参与缺失对账。

### 播放

- 后台与锁屏播放：`MediaSessionService` + `WAKE_MODE_LOCAL`，通知与锁屏控件使用 Media3 默认实现。
- 断点续播：按 URI 记录进度；恢复时以播放器实时位置为准，避免把后台播到的进度退回旧断点。
- 自动连播；顺序播完（非循环模式）后自动暂停。
- 随机播放与循环（关闭 / 单曲 / 全部）由控制栏切换。
- 倍速 0.5x–3x，共 7 档：0.5 / 0.75 / 1 / 1.25 / 1.5 / 2 / 3。
- 多音轨切换与内嵌字幕选择：字幕可「关闭 / 自动 / 指定轨道」，音轨只提供「自动 / 指定轨道」（不提供关闭，避免静音难以恢复）。
- 播放出错自动跳到下一项；整份列表都无法播放时弹出阻断弹窗，引导重新扫描。
- 仅在播放视频时保持屏幕常亮；纯音频不阻止息屏。耳机拔出自动暂停，音频焦点交由 ExoPlayer 处理。

### 手势与控制栏

- 单击画面：显示 / 隐藏控制栏（默认 3 秒后自动隐藏）。
- 双击画面：左 1/3 快退 15 秒，中间 1/3 播放 / 暂停，右 1/3 快进 15 秒。
- 横向滑动：拖动进度（整屏滑动约等于 1.2 倍时长），松手才真正 seek。
- 竖向滑动：左半屏调亮度，右半屏调音量；浮层实时显示百分比，松手后隐藏。
- 亮度会记忆在 SharedPreferences，可通过「更多 → 亮度跟随系统」恢复跟随系统。
- 控制栏（`custom_player_control_view.xml`，不依赖 Media3 私有资源）：当前时间 / 总时长、音轨、字幕、随机、循环、倍速、全屏、更多；居中为上一项 / 播放暂停 / 下一项；全屏时左上角有返回键。快进快退只由双击手势提供，不设独立按钮。
- 全屏顶部信息条：文件名 / 系统时间 / 电量百分比，随控制栏一起显隐，每 30 秒刷新。

### 全屏与小窗

- 全屏：切横屏（`SENSOR_LANDSCAPE`）并隐藏系统栏与刘海区域，画面按原始比例完整显示（不裁剪）；返回键优先退出全屏。
- 画中画：宽高比取自视频尺寸，带进入 / 退出动画锚点与无缝缩放（API 31+）；播放视频时离开应用自动进入小窗（API 31+ 由 `setAutoEnterEnabled` 处理，低版本在 `onUserLeaveHint` 处理），小窗内铺满、无圆角。
- 播放器卡片在普通界面为 18dp 圆角；未加载视频时「小窗」按钮禁用（半透明）；暂停状态下仍可进入小窗。
- 关闭小窗（界面带着画中画标记进入 `onStop`）时会暂停播放，音量与播放进度保持。

### 播放列表与删除

- 条目显示：序号（播放中替换为均衡器图标）、文件名、时长、删除按钮；有进度时额外显示进度条与「播放中 xx」「已看完」文案。
- 删除媒体库文件：经系统删除确认后删除设备上的原文件并清除进度（Android 11+ 走 `MediaStore.createDeleteRequest`；Android 10 先删除、被拒时转系统确认；Android 9 及以下需写权限）。
- 删除外部来源条目：只从播放列表移除，不动原文件。
- 移除条目后提供「撤销」提示（6 秒窗口），撤销窗口结束才真正清理播放进度；文件已被实际删除时不再提供撤销。

### 更多菜单

- **检查更新**：手动检查 GitHub Releases，已是最新时按钮文案内联提示。
- **亮度跟随系统**：清除手动亮度记忆。
- **重置本地数据**：二次确认后删除数据库（播放列表、进度、缺失文件记录）并重新扫描媒体库。

### 外部打开与分享

- 支持 `ACTION_VIEW`（文件管理器「打开方式」）与 `ACTION_SEND`（分享音视频）传入的媒体，立即加入列表并播放，同时尽力保留跨应用读权限。
- 媒体已在列表中时直接定位播放；界面重建不会重复播放同一次外部请求。

### 应用内更新

- 检查 `https://api.github.com/repos/niu0506/player/releases/latest`，按 `versionName` 分段数字比较判断是否有新版本。
- 用 `DownloadManager` 下载 `player-v<版本>-release.apk` 到应用专属外部目录 `Android/data/io.github.niu0506.player/files/updates/`。
- 安装前校验三项：APK 包名与当前应用一致、归档 `versionName` 与检查到的版本字符串一致、文件 SHA-256 与 Release asset 的 `digest` 一致；缺少 `digest` 的包按「无法验证完整性」处理，取消安装并提示手动下载。
- 通过 `FileProvider` 以 `content://` 交给系统安装器；Android 8+ 未授权时引导到「安装未知应用」设置页，回到前台自动续跑。升级完成后由 `PackageReplacedReceiver` 清理残留安装包。

## 环境要求

- JDK 17+（CI 使用 JDK 21）
- Android SDK，路径写入 `local.properties` 的 `sdk.dir`
- `minSdk 24` / `targetSdk 37` / `compileSdk 37`
- Gradle 9.9.1（wrapper）、AGP 9.5.1、KSP 2.3.11

## 构建

```bash
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 发布包（无签名信息时产出 *-unsigned.apk）
./gradlew testDebugUnitTest  # 单元测试
./gradlew lintDebug          # 静态检查
```

签名信息优先读环境变量 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，其次读 `keystore.properties`（已被 `.gitignore` 忽略）；两者都缺失时本地构建跳过签名而不报错。

## 项目结构

```
app/src/main/java/io/github/niu0506/player/
├── MainActivity.kt      # PlayerApp（Application）+ 入口 Activity：列表 UI、服务连接、扫描对账、
│                        #   队列差分、删除/撤销、外部打开、权限流程；文件末尾是队列差分的纯函数
├── PlayerRepository.kt  # 数据层：MediaItemData、MediaStore 扫描、自然序排序与对账、
│                        #   Room（playlist / progress / kv）与内存状态、缺失文件记录
├── PlayerService.kt     # MediaSessionService：ExoPlayer 与 MediaSession 托管、断点续播、
│                        #   进度落盘（ProgressCache）、错误跳过与事件上报
├── PlayerUi.kt          # 手势（GestureController）、全屏与小窗（FullscreenPipHelper）、
│                        #   选择面板、提示胶囊（HintHost/HintHandle）、列表适配器
└── UpdateManager.kt     # UpdateChecker + UpdateManager + PackageReplacedReceiver：
                         #   检查更新、下载、校验与安装
app/src/test/java/io/github/niu0506/player/PlayerTests.kt   # JVM 纯逻辑单元测试
app/src/test/java/android/net/FakeUri.kt                   # 测试用的 Uri 替身
app/src/main/res/        # 布局、样式与图标：控制器 / 提示图标均为 vector 或 shape，
                         #   仅启动图标含 PNG（mipmap-xxxhdpi/ic_launcher.png）
```

## 技术栈

- **Media3 1.11.1**：`media3-exoplayer` + `media3-ui` + `media3-session`（播放、控制视图、MediaSession）
- **Room 2.8.5**（KSP 2.3.11 编译），三张表 `playlist` / `progress` / `kv`
- **AndroidX**：AppCompat 1.8.0、RecyclerView 1.4.0、Activity 1.13.0、Core KTX 1.19.1、Lifecycle 2.11.0、Material 1.14.0
- **kotlinx-coroutines 1.11.0**；视图绑定用 ViewBinding，主题为 Material3 深色
- **Kotlin**：未声明独立的 Kotlin Gradle 插件，由 AGP 9 内置的 Kotlin 支持编译
- **测试**：JUnit 4.13.2 + `org.json`

## 核心机制

- **内存即读源**：`PlayerRepository` 把播放列表、进度、缺失记录、lastItem 放在内存里，读接口不查库；所有写入经单并发 IO scope + Mutex 串行落盘，失败重试 2 次后通过 `writeFailures` 上报（界面只记日志）。
- **队列差分**：界面列表变化不整体重建播放队列，而是用 `planQueueDiff` 算出最小的 Add / Remove / Move / Replace 操作序列；当前播放项被移除或出现重复 URI 等异常时才整体重建。
- **进度状态机**：进度每 5 秒差量落盘，并在暂停、播放结束、换项、seek、内存紧张、任务移除、服务销毁、回到前台等时机补记；距末尾 `min(5s, 时长/10)` 内视为「已看完」，写入的是「位置 = 总时长」的完成标记，因此记录仍在但不再作为断点。
- **扫描对账**：`reconcilePlaylist` 合并扫描结果并刷新元数据，`decidePruneCandidates` 负责缺失判定（24 小时宽限期 + 完整权限/卷挂载时的即时确认），两者都是不依赖 Android API 的纯函数。
- **数据库**：Room `version = 3`、`exportSchema = false`，仅使用 `fallbackToDestructiveMigration(dropAllTables = true)`，没有 Migration，表结构变化即清库重建。

## 测试

`app/src/test/` 下有 29 个测试类、171 个 `@Test`，全部是 JVM 纯逻辑测试：自然序排序、扫描对账与剪枝、进度写入/续播决策、进度缓存状态机、队列差分、更新包 SHA-256 与版本比较、缺失记录序列化等。

不依赖 Robolectric / Mockito / androidx.test，Android API 由 `unitTests.isReturnDefaultValues = true` 返回默认值；`android.net.Uri` 用测试目录下的 `FakeUri` 替身（仅 `equals` / `hashCode` / `toString` 有效）。

## 开发注意事项

- **版本唯一来源**：`version.json` 中的 `versionCode` / `versionName` / `notes` 是全项目唯一版本来源，构建脚本与 CI 都从这里读取；发布时递增版本并推送同名的 `v<versionName>` tag。
- **CI**：`.github/workflows/build.yml` 在 JDK 21 上跑单测与 lint、构建 Release APK，校验 tag 与 `versionName` 一致、用 `aapt2` 核对 APK `versionName`、用 `apksigner` 校验签名，并计算 SHA-256 生成 `.sha256` 附件；`v*` tag 强制要求签名，缺签名密钥直接失败。
- **更新包落点**：`DownloadManager` 只接受 `file://`，因此更新包直接下载到应用专属外部目录 `files/updates`（`setDestinationInExternalFilesDir`），校验与安装共用这一份，不要再复制到 `filesDir`，避免出现两份安装包。
- **更新校验口径**：版本比较与安装校验都用 `versionName`，不使用 `versionCode`；校验只覆盖包名、`versionName`、SHA-256，不做 APK 签名证书校验，因此 Release asset 必须带合法的 `sha256:` digest。
- **提示分层**：扫描结果、仓库加载失败用列表顶部内联提示（4 秒 / 8 秒）；按钮内联文案 2 秒；底部胶囊提示分短 2 秒、长 3.5 秒、带操作 6 秒，并对纯提示做 1.2 秒去重，小窗模式下退回系统 Toast。
- **播放器视图**：使用 `TextureView` 以支持圆角裁剪与小窗过渡，功耗和 HDR 表现不如 `SurfaceView`；如改回 `surface_view` 需同步去掉卡片圆角。
- **权限时机**：启动只申请 Android 13+ 的通知权限；媒体读权限在点击扫描、全部播放失败弹窗、重置数据完成后才申请。

## 已知限制

- 只扫描主外部存储卷（`EXTERNAL_CONTENT_URI`），SD 卡 / OTG 等可移动卷的媒体不会被导入。
- 时长未知或短于 5 秒的媒体不会自动入库，避免把提示音、片段混进列表。
- 字幕仅支持视频内嵌轨道，不支持外部 `.srt` / `.ass`。
- 前台服务通知使用 Media3 默认渠道与样式，未做自定义。
- 进度、缺失记录与播放列表都以原始 URI 字符串为键，去查询参数 / 去尾斜杠的归一化只用于去重与扫描匹配，两处口径并不完全一致。
- 「选中项」部分授权（`READ_MEDIA_VISUAL_USER_SELECTED`）下扫描不做特殊提示，缺失判定会更保守地依赖 24 小时宽限期。
- 未覆写 `onSaveInstanceState`，界面重建后的状态依赖仓库与当前 Intent。

## License

MIT，见 [LICENSE](LICENSE)。
