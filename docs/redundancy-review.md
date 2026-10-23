# 冗余与可精简项：逐条核对

审查基线：`f4a2f8c`（versionName 1.6.2，工作区当前代码）。  
三份清单有大量重叠，已合并为 65 条独立主张。

判定口径：

- ✅ **成立**：事实描述准确，建议方向也基本可行。
- 🟡 **部分成立 / 有条件**：现象存在，但结论、收益或改法需要修正。
- ❌ **不成立 / 照做会引入问题**：不建议按原建议实施。

统计：✅ 42 条，🟡 22 条，❌ 1 条。另有几条虽然标为 🟡，但其中某个具体改法会直接引入 bug，单独在第一节列出。

## 执行状态（2026-10-23）

本轮已按“第一批 + 第二批（除 ListAdapter 迁移）”落地，`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过。

已执行：

- A1（单次拷贝 + `DigestInputStream`）、A2（删除已够不到的 app-specific Download 清理）、A4（`PendingDownload` 以 prefs 为唯一来源）、A5（`withResumed` 替换暂存弹窗机制）、A7（删 context 参数）、A16（删 `cachePosition` 包装）。
- A18 / F4：保留 `normalizeUri` 的 query/尾斜杠归一化，但删掉无意义的 `lastSeg` 后缀，新增 String 重载；适配器也改为 normalize，不再混用 raw `toString()`。
- B1–B4（清状态 / 删库 / closeDb / 内联 `dispatchDbWrite`）、B6（权限判断）、B7–B8（自然排序）。
- C1（`when` 改写）、C2（`MediaItem?.uriString`）、C3（删暂停回调里的冗余 flush，保留列表点击的）、C4（删扫描后两次重复调用，保留 restore）。
- D1 部分：`onDetachedFromRecyclerView` 取消差分；未迁移到 `ListAdapter`（见下）。
- D2（`isActive` 传参）、D3（删 `KEY_FOLLOW_SYSTEM`）、D4（PiP 条件合并）、D5（卡片样式合并）、D6（`applyInfoBar(false)`）。
- E2（`UpdateChecker` 改 object）、F3（合并失败路径）、F5/F10（`speedLevels` 入 companion）、F8（单条时长刷新，带差分中回退）、F9（buildList）、F11（参数改名）、F12（信息条 guard）。

未执行（保持原样或留待单独改动）：

- D1 的 `ListAdapter` 迁移：现有 JVM 测试直接注入 `adapter.items`，迁移会连带改测试执行器；本轮只修了实际的 `diffScope` 泄漏。
- A3：API <29 的公共 Downloads 清理需要 `WRITE_EXTERNAL_STORAGE` 或改用 `DownloadManager.remove`，不是补一个分支。
- A6：按原方案删 receiver 会引入清理时机问题，保留。
- A8：dialog 的 try/catch 保留。
- A9–A15、A17、A19–A21：涉及进度层重构、扫描任务收敛、pendingDrops 等，按报告仍应单独做、单独测。
- B5、B9–B12、C5–C7、D7–D8、E1/E3、F1/F2/F6/F7/F13/F14：收益低、需要迁移或属于风格项，未纳入本轮。

---

## 一、先看这几条：不要照做

### 1. `activeScanJobs` 不是冗余，`{scanJob, manualScanJob}` 覆盖不了它

反例就在 `startScan` 里：

1. 静默扫描 S 正在跑，`scanJob = S`。
2. 用户点扫描，创建手动任务 M；M 的协程先 `running?.join()` 等待 S。
3. 于是 `scanJob = M`、`manualScanJob = M`，而 S 仍然活着，只记录在 `activeScanJobs` 里。

见 [MainActivity.kt:470](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L470) 到 [MainActivity.kt:503](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L503)。`cancelScans()` 之所以需要集合，就是为了取消那个已经被覆盖掉的 S。清单里“`activeScanJobs` 恒等于 `{scanJob, manualScanJob}` 的非空子集”这句话是**错的**；同一份清单后面第 14 条（`activeScanJobs` 是必须的）才是对的。

可以简化成“一个 Job + 一个 pendingManual 标志 + `repeatOnLifecycle(STARTED)`”，但不能只靠一个 `Mutex`：Mutex 只负责串行，不会去重、也不会把手动扫描排队到静默扫描之后。

### 2. 删 `PackageReplacedReceiver` 不能只把清理塞进 `resumePendingDownload` 的“已安装”分支

当前成功安装的流程是：

- `installVerifiedApk()` 调起系统安装器后，**立刻** `clearPersistedDownload()`：[UpdateManager.kt:616](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L616)。
- 下次启动 `resumePendingDownload()` 读到 `id < 0`，在 [UpdateManager.kt:313](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L313) 就 return。
- “目标版本已安装”的分支在 [UpdateManager.kt:322](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L322)，它既到不了，内部也只清 prefs、没有调用 `cleanupUpdateApks()`。

所以 `PackageReplacedReceiver` 目前是安装完成后**唯一可靠**的清理触发点。真要删 receiver，需要把“清 prefs”推迟到确认安装完成之后，再在确认分支里清理；不能在启动时无条件清理，否则可能在系统安装器仍在使用 APK 时把文件删掉。

### 3. `MissingInfo.missCount` 不是“只差一个布尔值”，更不该换成 `Map<String, Long>`

`missCount` 确实只在 `>= 1` 的判断里出现（[PlayerRepository.kt:331](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L331)），但 0 和 1 的区别是真实的：

- `recordMissing()` 写入的是 `MissingInfo(now, 0)`：[PlayerRepository.kt:861](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L861)，表示“播放报错，但扫描还没确认缺失”。
- 扫描再次未见后才 +1（[PlayerRepository.kt:1195](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L1195)）。
- `decidePruneCandidates` 要求 `missCount >= 1`，才允许走 24 小时宽限期删除。

如果改成只存 `firstMissingMs` 的 `Map<String, Long>`，那么“仅播放报错”的记录在 24 小时后也会满足条件被删，改变了 fail-safe 行为。想简化应改成 `Boolean confirmedMissing + firstMissingMs`，而不是只存时间。

### 4. 列表点击前的 `flushCurrentPosition()` 不多余

`onIsPlayingChanged(false)` 里的那次确实基本冗余：Service 自己的监听器在暂停时已经 `persistProgress()`（[PlayerService.kt:79](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L79)）。

但列表点击里的那次不能删（[MainActivity.kt:777](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L777)）：

- 点击**当前条目**时，Service 收到的是 `sameItem` 的 SEEK。
- `onPositionDiscontinuity` 只在 `reason == SEEK && !sameItem` 时调用 `cacheOldPosition`（[PlayerService.kt:118](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L118)）。
- 同条目 seek 之前不先落盘，点击瞬间的精确位置就丢了，只能等下一次 ticker，最多丢 5 秒。

### 5. `notifyItemChanged(index)` 不能直接替代 `submitPlaylist()`

方向是对的（单条时长不该做全表 DiffUtil），但当前 `playlist` 和 adapter 的内部列表是两份：

- `MainActivity.playlist` 是界面侧列表。
- `MediaListAdapter.items` 只在 `submitList()` 的差分回调里更新（[PlayerUi.kt:659](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L659)）。

只调 `notifyItemChanged(index)`，adapter 仍会从旧的 `items[index]` 绑定，时长不会刷新。必须同时提供 `adapter.updateItem(index, newItem)`（先改内部列表再 notify），或者走带 payload 的局部更新。

另外，“删掉 `confirmedDeleted` 的第二次查询”也没有被证明安全：它是批量扫描之后的一次条目级二次确认（[MainActivity.kt:644](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L644)），测试里明确覆盖了“确认删除立即移除”的语义（`PruneDecisionTest`）。保留更稳。

---

## 二、UpdateManager / 更新下载

| # | 主张 | 判定与核对 |
|---|---|---|
| A1 | `validateDownloadedApk` 做了两次拷贝，可边拷边算哈希直接写最终目标 | ✅ 属实。当前先写 `cacheDir/update-verify.apk`（[UpdateManager.kt:524](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L524)），再读 temp 算 SHA-256（[542](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L542)），再复制到 `filesDir/updates`（[555](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L555)），最后比长度（[558](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L558)）。直接写 target + `DigestInputStream` 可行；失败删 target，`verifiedApkFile` 只在成功后赋值。收益是少一次完整写、一次临时文件。 |
| A2 | `cleanupUpdateApks` 里清 `getExternalFilesDir(DIRECTORY_DOWNLOADS)` 是遗留代码 | ✅ 结论对，但理由要修正：≤1.5.9 确实用 `setDestinationInExternalFilesDir` 往这里写过（`bb6d845^` 的 UpdateManager 第 470 行）；1.5.10 改公共 Downloads；1.6.0 又把 applicationId 从 `com.example.player` 改成 `io.github.niu0506.player`。当前包名的 `getExternalFilesDir` 看不到旧包目录，且跨包名不能原地升级，所以对当前包名已经够不到。保留无害；删的话建议注释说明历史。 |
| A3 | 公共 Downloads 清理只处理 API 29+，29 以下漏了 | ✅ 事实对：[UpdateManager.kt:197](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L197) 只有 Q+ 分支。但这不是补一个 `else` 就行：<29 没有 `MediaStore.Downloads`，直接 `File.delete` 需要 `WRITE_EXTERNAL_STORAGE`，而 Manifest 只声明了 `READ_EXTERNAL_STORAGE`（maxSdk 32）。要么声明 WRITE（maxSdk 28）并请求，要么接受残留/改下载目录。`deleteExistingDownloadFile` 的 <Q 分支同样可能静默失败。 |
| A4 | 下载状态在内存字段和 prefs 各一份，可合成 `PendingDownload` | ✅ 属实。字段在 [UpdateManager.kt:219](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L219)，prefs 键在 [180](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L180)，`resume` 回灌在 [313](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L313)。单一来源可行；若改 StateFlow，注意 receiver 过滤 id 和 `hasActiveDownload` 需要同步读。 |
| A5 | `pendingUpdate`、`pendingInstallPermissionPrompt`、`canShowDialog`、`flush...`、`resume...`、`onResume` 整套可换成 `lifecycle.withResumed { show() }` | 🟡 方向可行（lifecycle 2.8 有 `withResumed`），但不是纯删除。仍需保留 `shownUpdateDialog` 去重、`isFinishing/isDestroyed` 检查、多次 check 的合并；`withResumed` 在 destroy 时会取消。`canShowDialog` 还给 `showInstallPermissionDialog` 用。适合作为后续重构，不是一行替换。 |
| A6 | 删 `PackageReplacedReceiver`，把清理放进 `resumePendingDownload` 已安装分支 | ❌ 见第一节第 2 条。分支到不了，且清理时机不对。 |
| A7 | `handleDownloadOutcome` / `failDownload` 的 context 参数多余 | ✅ 属实且低风险。receiver 传入自己的 context（[UpdateManager.kt:256](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L256)），但最终都是对 activity 弹 Toast；直接用 activity 即可。 |
| A8 | 两处 `AlertDialog.show()` 外层 try/catch 是过度防御 | 🟡 主观判断，不建议按“冗余”删。`canShowDialog()` 覆盖常见状态，但 `show()` 仍可能因 window token 失效抛 `BadTokenException`；catch 成本很低且能记录日志。保留更稳。 |
| A9 | 进度数据在 `ProgressCache.cached`、`lastPersisted`、`progressState`、数据库各一份 | 🟡 结构上确实有多层，但职责不同：cached 是待写、lastPersisted 是差分基线、progressState 是读权威、Room 是持久化。不能简单合并；表 #9 的“保留 Cache 差分、Repo 退化为写通道”更合理。 |
| A10 | 删除有两条并行路径：`progress.clear()` 走 removals，`dropProgress()` 立即写库再清缓存 | ✅ 事实成立，但语义不同：`clear()` 是 seek 回 0 的延迟删除（[PlayerService.kt:451](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L451)），`dropProgress()` 是删条目的显式删除（[PlayerService.kt:386](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L386)）。后者是异步 dispatch，不是同步写库。可统一接口，但别当纯重复删。 |
| A11 | `pendingDrops` 最难读，清理散在 `retainPending`、`clearAllPendingDrops`、`rebuildQueueAndClearPending`，应单独重构/测试 | ✅ 判断正确。逻辑在 [PlayerService.kt:432](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L432) 到 [500](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L500)、[301](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L301)，以及 [MainActivity.kt:288](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L288)、[557](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L557)。确实应该单独做、单独测。 |
| A12 | `ProgressWriteDecision` 可换成返回 `Long?` | ✅ 技术上可以：`Store(position)` → Long，`Skip` → null。但 sealed 更自文档化，收益小；测试需同步改（`PlayerTests.kt:115` 起）。 |
| A13 | “位置 > 0”规则写了三遍 | ✅ 属实：`decideProgressWrite`（[PlayerRepository.kt:1146](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L1146)）、`filterValues`（[790](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L790)）、`mergeProgressMap`（[1159](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L1159)）。保留 merge 一处，dbPart 用合并后的 map。 |
| A14 | `PlayerService.instance` + 5 个静态转发函数，服务没启动时静默失效 | ✅ 基本属实：实际是 6 个函数/5 个名字（[PlayerService.kt:381](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L381) 到 [419](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L419)）。严格说 `dropProgress` 在 service 未启动时仍会写库（[389](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L389)），只有 cache/pending 部分跳过。 |
| A15 | 进度跟踪整体下沉到仓库层，Service 只喂事件，`instance` 和转发都不需要 | ✅ 设计方向可行。Repo 已是进程单例；接管 `ProgressCache` 后 UI 直接调 Repo，Service 只负责 cache/flush/retain。需要把当前队列 URI 传给 pendingDrops 逻辑。 |
| A16 | `cachePosition()` 是空包装 | ✅ 属实，[PlayerService.kt:253](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L253)，两处调用。 |
| A17 | 删 `lastPlayedUri`/`lastPlayedDurationMs`，改成在 `onPositionDiscontinuity` 对 AUTO_TRANSITION 调 `cacheOldPosition` | 🟡 未验证，风险中等。`cacheOldPosition` 目前只在跨条目 SEEK 调用（[PlayerService.kt:118](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L118)）；AUTO 且 sameItem（单曲循环）会走 [121](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L121) 的 return；`window.durationMs` 可能 `TIME_UNSET`。还要确认 `onMediaItemTransition` 与 `onPositionDiscontinuity` 的顺序。可做，但先写测试/日志。 |
| A18 | `normalizeUri()` 追加 `lastSeg` 后缀没有新信息；所有 URI 来自 `withAppendedPath`，可统一 `toString()` | ✅ 属实。`lastSeg` 由 base 决定，追加后缀后的相等性等价于 base；内部 URI 固定为 `content://.../id`，没有 query/尾斜杠差异。统一 `toString()` 可行。注意这与表 #7“保留 normalize”冲突，只能二选一。 |
| A19 | MainActivity 是 1093 行上帝类 | ✅ 属实。扫描、缺失裁决、卷检测、队列差分、权限、菜单、重置、更新编排都在；更新细节已委托 `UpdateManager`，但 Activity 仍是编排中心。 |
| A20 | `computePruneDeletedMedia`、`externalVolumesFullyMounted`、`isConfirmedDeleted` 约 70 行应下沉 | ✅ 合理。三者在 [MainActivity.kt:580](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L580) 到 [649](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L649)，依赖 Context；可抽 `ScanPruner` 或放扫描器层。注意 `decidePruneCandidates` 已经在 Repo 且是纯函数。 |
| A21 | 扫描任务四个东西互相配合，换成“onStop 取消的 Job + Mutex” | 🟡 四件套确实绕，但见第一节第 1 条：`activeScanJobs` 不是冗余；单 Job + pendingManual + `repeatOnLifecycle` 可以，Mutex 单独不够。 |

---

## 三、PlayerRepository

| # | 主张 | 判定与核对 |
|---|---|---|
| B1 | “清空四个内存状态”出现 3 次，抽 `clearMemoryState()` | ✅ 属实：[696](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L696)、[736](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L736)、[1067](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L1067)。抽 `clearMemoryStateLocked()`，调用方持 `stateLock`。 |
| B2 | `deleteDatabase` 失败→删旁路→`dbFile.exists()` 在 reset/backup 各写一遍 | ✅ 属实：[742](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L742) 与 [1056](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L1056)。可抽 `deleteDbFiles()`；备份目录逻辑不同，别混进去。 |
| B3 | `db?.close()` 外层 try/catch 出现 3 次，抽 `closeDb()` | ✅ 属实：[730](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L730)、[962](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L962)、[1033](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L1033)。可加 `logFailure` 参数统一。 |
| B4 | `dispatchDbWrite` 只有一处调用，内联 | ✅ 属实，仅 [894](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L894)。内联可，但名字表达了“内存已先行更新”，收益低。 |
| B5 | `nowWallClockMs()` 只是 `System.currentTimeMillis()` 包装 | 🟡 可内联，但它和 `nowMs()`（`nanoTime`）区分了墙钟/单调时钟，名字有意义。建议保留。 |
| B6 | `hasAnyMediaReadAccess` 里 `< 33` 特判多余 | ✅ 属实。`hasMediaReadAccess` 在 <33 时忽略 permission/includePartial（[481](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L481)），直接对 VIDEO / AUDIO 取或，对所有版本成立。C12 也指出命名参数只是表达意图。 |
| B7 | 自然排序的 `NATURAL_MAX_DIGITS` 和 `toLong` 分支可整个删 | ✅ 属实。去前导零后“先比长度再比字典序”对任意长度正确；[111](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L111) 到 [130](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L130) 可删，顺带避免溢出。 |
| B8 | `compareTextSegments` 的 -1/1 映射可返回比较结果 | ✅ 属实，比较器只需符号；直接返回 `compareTo` 结果。 |
| B9 | `MissingInfo.missCount` 相当于布尔值，可改 `Map<String, Long>` | 🟡 半对，见第一节第 3 条。`Int` 确实过重，可改 `Boolean confirmedMissing`；但 `Map<String, Long>` 会丢掉“仅报错未确认”的状态。 |
| B10 | `decidePruneCandidates` 里 `confirmedDeleted` 二次查 MediaStore 基本多余 | 🟡 不能确认。它是批量扫描之后的条目级二次确认（[MainActivity.kt:644](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L644)），既可能减少误删（文件恢复），也可能加速确认（扫描后删除）。测试明确覆盖。保留，除非有数据证明批量查询已足够。 |
| B11 | `formatTime` 的 `LruCache(512)` 是过早优化，改手写补零并移到 PlayerUi | 🟡 缓存是否过早优化有争议；手写补零确实更快、可去缓存。移到 PlayerUi 是组织问题，不是冗余。可选。 |
| B12 | `KvEntity` + `KvDao` 只存两个值，`lastItem` 放 prefs、缺失时间做 playlist 列 | 🟡 事实对，但把 `lastItem` 放 SharedPreferences 会增加一种持久化机制，和 D8 的目标相反；缺失时间放 playlist 列需要迁移。可选，不是优先项。 |

---

## 四、PlayerService / MainActivity

| # | 主张 | 判定与核对 |
|---|---|---|
| C1 | `onPositionDiscontinuity` 的 if/else-if/return 改显式 `when` 更清楚 | ✅ 属实，[PlayerService.kt:101](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L101) 到 [124](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L124)，行为可等价改写。 |
| C2 | 同一 URI 链在 Service/Activity 出现约 8 处，加 `MediaItem?.uriString` 扩展 | ✅ 属实，实际 10 处（`grep` 结果）。加扩展可读性明显提升。 |
| C3 | 两处 `flushCurrentPosition()` 很可能冗余 | 🟡 半对：`onIsPlayingChanged(false)` 的那次基本冗余；列表点击的那次不多余，见第一节第 4 条。 |
| C4 | `onStart` 扫描 join 后的 `rebuildQueueAndClearPending`、`restoreLastPlayed`、`refreshPlaylist` 基本冗余 | 🟡 前两个基本冗余：有变化时 `reconcileScanResult` 已 refresh/rebuild，无变化时队列也匹配。但 `restoreLastPlayed` 不完全冗余：扫描删除当前项时 `reconcile` 不做 restore（[MainActivity.kt:534](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L534) 到 [561](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L561)）。建议保留 restore，另两个按 changed 条件化。 |
| C5 | `updateDurationAt` + `updatePlaylistDuration` + `updateDuration` DAO 可删，或改走 `savePlaylist()` | 🟡 条件性。专用单行回填是合理的；MediaStore 时长可靠时可删，但“否则改 `savePlaylist()`”会更重（整表替换），不如保留 DAO。另 `updateDurationAt` 用 `submitPlaylist` 做全表 DiffUtil 确实浪费（见 F8）。 |
| C6 | 续播位置在 UI `seekToResume` 与 Service `restoreResumePosition` 各算一次，耦合脆弱 | 🟡 部分。`resumablePositionMs` 已经共用；重复的是两个动作以及 1 秒容差只在 Service。可抽 `shouldRestoreResume`，收益中等。 |
| C7 | `submitPlaylist()` 只是转发，`toastMessage` 只用一次 | 🟡 半对：`submitPlaylist` 确是转发，但有 2 处调用（[153](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L153)、[177](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L177)）；`toastMessage` 有 2 处（[572](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L572)、[761](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L761)），不是一次。包装可留，收益低。 |

---

## 五、PlayerUi

| # | 主张 | 判定与核对 |
|---|---|---|
| D1 | `MediaListAdapter` 手写 DiffUtil + 永不取消的 scope，换 `ListAdapter` | ✅ 属实。自定义 `diffScope` 在 [PlayerUi.kt:638](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L638) 从不 cancel；ListAdapter 的 `AsyncListDiffer` 自带 generation 取消。迁移后 items 用 `currentList`，测试要改；C15 的 `onDetached` 取消也就自然消失。 |
| D2 | `bindProgress` 与 `onBindViewHolder` 重复算 `isActive` | ✅ 属实，[708](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L708) 与 [739](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L739)；把 isActive 传进 `bindProgress`。 |
| D3 | `KEY_FOLLOW_SYSTEM` 是可推导状态，整段可删 | ✅ 属实。状态与 `KEY_BRIGHTNESS` 是否存在完全等价：apply 写 key+false（[594](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L594)），reset 删 key+true（[605](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L605)），setup 默认 `!contains`（[427](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L427)）。改成 `if (prefs.contains(KEY_BRIGHTNESS)) apply...` 即可。 |
| D4 | `enterPipWhenPlayingVideo` 双重否定；条件与 `refreshPipParams` 的 autoEnter 重复 | ✅ 属实。`!(!a 或 !b)` = `a && b`；与 [328](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L328) 的 `vs.hasVideo() && isPlaying` 同一条件，可抽 `canAutoEnterPip()`。 |
| D5 | `applyEdgeToEdgeSurface` 与 `applyNormalVideoSurfaceStyle` 重复 | ✅ 属实。两函数都是 setResizeMode + margin/radius；合并 `applyCardStyle(resizeMode, marginDp, radiusDp)`。toast 三合一可选，非必要。 |
| D6 | `onStopped` 手动停时钟与 `applyInfoBar(false)` else 分支重复 | ✅ 基本属实。两行 [380](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L380) 等价于 `applyInfoBar(false)` 的 else 分支，还会顺带隐藏信息条；可用。 |
| D7 | 4 个独立主线程 Handler，多数可换 View.postDelayed/协程 | ✅ 事实对：Service [36](app/src/main/java/io/github/niu0506/player/PlayerService.kt#L36)、Activity [184](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L184)、Helper [106](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L106)、Gesture [405](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L405)。Activity/UI 可改 `View.postDelayed`，Service 没有 View，保留 Handler 或换协程；不是必须。 |
| D8 | 持久化有三种机制；UpdateManager 简化后只剩两种 | 🟡 事实对：Room + `gesture_settings` + `update_download`。但“按第一节简化后只剩两种”不成立：第一节建议用 prefs 作为唯一来源，`update_download` 仍在。只有彻底去掉下载状态持久化才会变两种。 |

---

## 六、UpdateManager 小项

| # | 主张 | 判定与核对 |
|---|---|---|
| E1 | `fetchLatestReleaseJson` 的 conn/current 双变量可合并 | ✅ 风格项，收益很小；当前 `var conn` 也正确处理了 `openConnection` 抛异常。 |
| E2 | `UpdateChecker` 没有实例状态，改 `object` | ✅ 属实，可行。 |
| E3 | `toHex` + `HEX_DIGITS` 可换 `joinToString("%02x")` 或 `toHexString()` | 🟡 当前手写更高效；`joinToString` 每次分配/格式化，`toHexString()` 需 Kotlin 2.2+ 且可能实验性。除非追求短，否则不改。 |

---

## 七、表格与其他补充项

| # | 主张 | 判定与核对 |
|---|---|---|
| F1 | `verifyApkChecksum` 后调用处展开枚举分支，可只留一行日志 | 🟡 可合并，但当前分开的 missing/mismatch 诊断更有用；不是纯冗余。 |
| F2 | `isDownloadedVersionInstalled` 单调用点可内联；两处 MediaStore 删除可抽 helper | ✅ 单调用点属实（生产仅 [323](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L323)）；内联可行。`ignoreFailure` 有 3 处删除 + `unregisterReceiver`；抽 `deleteMediaStoreDownloads` 可行但 selection 不同，收益小。 |
| F3 | `abortUnverifiedDownload` 与 `failDownload` 几乎同构，合并 | ✅ 属实。abort = clear + toastLong(unverified)；fail = toast(short) + clear。可合并为 `failDownload(msgRes, long)`。 |
| F4 | MainActivity、MediaListAdapter、Repo 三套 URI 定位 helper，口径不一致 | 🟡 三套实现属实：MainActivity normalize（[206](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L206)）、Adapter raw（[666](app/src/main/java/io/github/niu0506/player/PlayerUi.kt#L666)）、`findMediaItemIndexByUri` normalize（[83](app/src/main/java/io/github/niu0506/player/PlayerRepository.kt#L83)）。统一即可，但别同时保留两套；与 A18 冲突。当前 URI 规范，实际不一致风险低。 |
| F5 | `speedsIndex` 的 `coerceAtLeast(0)` 永远不生效 | ✅ 属实，`speedLevels` 必含 1.0f。 |
| F6 | `formatSpeed` 用浮点取模判断整数，改比较 `toInt()` | 🟡 等价，风格项；不是冗余。 |
| F7 | `scanMediaInternal` 两次 `withContext(IO)` 可合并 | ✅ 属实，但只省一次线程切换；收益很小。最后的 `withContext(Main)` 可留作显式。 |
| F8 | `updateDurationAt` 用 `notifyItemChanged` 替代全表 DiffUtil | 🟡 方向对，但见第一节第 5 条：单独 notify 会重绑旧数据，必须同时更新 adapter 内部列表。 |
| F9 | `reconcilePlaylist` 的 filter 改 buildList 更直观 | ✅ 行为不变，风格项。 |
| F10 | `speedLevels` 提到 companion | ✅ 属实，但每次 Activity 创建一个 7 元素 FloatArray 的开销可忽略；收益很小。 |
| F11 | `applyVisualState` 参数遮蔽字段，改名 | ✅ 属实，风格项。 |
| F12 | `refreshInfoBarContent` 非全屏也写 TextView，可加 guard | ✅ 属实。`onMediaItemTransition` 每次都调（[221](app/src/main/java/io/github/niu0506/player/MainActivity.kt#L221)），非全屏也写 3 个 TextView 并读电池广播；加 guard 合理。 |
| F13 | `compareDigitSegments` 可用 BigInteger 简化 | 🟡 判断对（当前实现不算冗余），但 BigInteger 每次比较分配对象，比现有长度+字典序更差；不建议。 |
| F14 | `downloadBusy` 一个标志覆盖入队与完成回调两个语义 | ✅ 设计观察属实（[418](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L418) / [280](app/src/main/java/io/github/niu0506/player/UpdateManager.kt#L280)）。当前主线程顺序下快速完成竞态很难发生（enqueue 后到 finally 之间不挂起），但拆分 enqueuing/validating 更清晰。 |

---

## 八、建议实施顺序

### 第一批：低风险、净收益明确

1. **A1 / T2**：`validateDownloadedApk` 直接写最终目标 + `DigestInputStream`，删 temp、第二次拷贝和长度比对。
2. **A4 / T1**：下载状态单一来源（prefs 或 StateFlow），删三个平行内存字段。
3. **A18 + F4**：URI 键统一为 `toString()`，删 `normalizeUri` 和三套 helper（或者反过来统一 normalize，但只选一种）。
4. **B1–B4**：`clearMemoryState`、`deleteDbFiles`、`closeDb`、内联 `dispatchDbWrite`。
5. **B7–B8**：删自然排序的 Long 分支和 text sign 映射。
6. **D2–D5**：`isActive` 传参、删 `KEY_FOLLOW_SYSTEM`、PiP 条件合并、视频表面样式合并。
7. **F5、F9、F10、F11、F12**：小清理，可顺手做。

### 第二批：中等收益，需少量设计

- **D1**：`MediaListAdapter` 迁移到 `ListAdapter`，删 `items`/`diffScope`/`diffJob`/手写 `submitList`（测试同步改）。
- **C1 / C2**：`onPositionDiscontinuity` 改 `when`；加 `MediaItem?.uriString` 扩展。
- **A7 / E2 / F3**：删 context 参数、`UpdateChecker` 改 object、合并失败路径。
- **A5**：更新弹窗改用 `withResumed`，但保留去重和销毁保护。

### 第三批：单独做、单独测（不要混在清理 PR 里）

- **A11**：`pendingDrops` 重构，重点测“删除后又被扫描加回”。
- **A15 / T9**：进度跟踪下沉到 Repo，Service 只喂事件。
- **A21**：扫描任务收敛为单 Job + pendingManual + `repeatOnLifecycle(STARTED)`，保留取消静默任务的能力。
- **B10**：`confirmedDeleted` 是否可删，需要用真实 MediaStore 行为验证，不能只看代码。
- **A17**：`lastPlayed` 字段替换，需要确认 Media3 回调顺序和 `TIME_UNSET` 行为。

### 不建议做

- 删 `activeScanJobs`（第一节第 1 条）。
- 按原方案删 `PackageReplacedReceiver`（第一节第 2 条）。
- 把 `MissingInfo` 改成只存时间的 `Map<String, Long>`（第一节第 3 条）。
- 删列表点击的 `flushCurrentPosition()`（第一节第 4 条）。
- 只用 `notifyItemChanged` 不更新 adapter 内部列表（第一节第 5 条）。
- 为了“统一”同时保留 `normalizeUri` 和 `toString()` 两套键。
- 用 BigInteger 替换现有数字段比较。
