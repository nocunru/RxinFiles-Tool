# incoming-check —— 「用其他应用打开」入口的离线断言

从 QQ 等应用「用其他应用打开」进来的文件，走的是
`IncomingActivity`（Android 胶水）→ `IncomingFile`（纯 Java 判据）这条路。
这个入口**不打开任何文件，也不执行任何脚本** —— 它只做一件事：
把用户带到**那个文件所在的目录**并高亮那一行。

## 为什么单独一支

这个入口有两个特点，决定了它**必须**有一套能一条命令跑完的断言：

1. **它吃的是对方 App 可控的输入。** 入口要 `exported="true"` 才能出现在
   「用其他应用打开」里，于是它的 `data`（一个 URI）和显示名完全由对方说了算。
   文件名消毒漏一个字节 = 路径穿越；**反解出的路径名对不上却仍被采用 = 把用户
   带去另一个文件**；可见性判错 = 该亮的不亮。
2. **上面这些都不会编译报错、也不会立刻崩。** 只会在真机上被某个具体文件
   踩中才暴露，而且往往复现不了第二次。

所以判据全部抽到纯 Java 的 `IncomingFile`（零 Android API），这里是它的直接断言。

## 跑法

```sh
JAVA_HOME="C:/Program Files/Java/jdk-20" sh tools/incoming-check/run.sh
python3 tools/incoming-check/verify-incoming.py
```

- `run.sh`：用 `-sourcepath app/src/main/java` + `-cp "app/libs/*"` 把
  `IncomingFile.java`（连同它委托的纯 Java 归档层 `Archives` 等）和
  `IncomingCheck.java` 一起编出来，跑**行为断言**（文件名消毒、类型判定、
  反解路径可信度、内容嗅探、归档边界）。
  —— 归档层依赖 `app/libs` 下的解压库，所以 classpath 要带 jar；这一整片仍是
  **零 Android API**。
- `verify-incoming.py`：只读源码比对，跑**源码不变量断言**
  （纯 Java 约束、分类表是否只有一份、归档是否委托 `Archives`、manifest、
  Activity 的可信边界、**定位与多选是否彻底分离**、版本号……）。

两支都应该全绿。

## 断言覆盖了什么

`IncomingCheck.java`（行为，十九节）：

| 节 | 内容 |
|----|------|
| 1 | 文件名消毒：路径穿越（`../`、`..\`）、控制字符、NUL、纯点、空名、过长截断 |
| 2 | scheme 白名单：只认 `content` / `file` |
| 3 | 扩展名口径（点开头 / 点结尾 / 多点） |
| 4 | 分类判据（图片 / 视频 / 文本 / 归档 / 严格脚本 / 两可脚本 / 音频媒体；`.ts` 仍为文本） |
| 5 | **反解路径可信度**：`matchesDisplayName` / `lastSegment`，近名文件只有逐字符相同才被接受 |
| 6 | 内容嗅探：含 NUL 判二进制、空文件、超 4KB |
| 7 | 归档识别与 `Archives` 逐条自洽（唯一真源） |
| 8 | **归档识别边界**：点开头单扩展名（`.zip`/`.tar`/`.gz`…）都认 |
| 9 | **兜底复制不覆盖已有文件**：`uniqueName` 的 `(n)` 递增 + 扩展名留在最后 + `(999)` 占满后继续到 `(1000)` |
| 10 | **shell 转义** `quoteShell`：与 `ScriptRunner.quote` 同口径、任意敌意串可逆还原 |
| 11 | **搜索目录计划** `knownSearchDirs`：已知 QQ/下载目录拼到外部存储根 |
| 12 | **搜索命令构造** `buildSearchCommand`：内置 busybox timeout 优先 + 系统 timeout 次选 + 无工具时明确报出 + 转义 + `-name`/`-size Nc` 精确匹配 |
| 13 | **搜索输出解析** `parseSearchOutput`：只认绝对路径、去重、滤掉 shell 噪声 |
| 14 | **搜索命中判据** `matchesSearchHit`：名字逐字符 **且** 大小逐字节，缺一不可 |
| 15 | **诊断输出** `formatDiagnostics`：定位失败必须带出哪些事实（含可访问性实测小节） |
| 16 | **搜索结局** `parseSearchStatus` / `SearchOutcome`：正常 / 超时 / 无 timeout 工具 / 无输出，三种失败分得清 |
| 17 | **目标目录可访问性实测** `probeDirs` / `formatAccessReport`：Java vs shell 双通道 + 一致性结论 |
| 18 | **定向目录查找** `directedLookupDirs` / `matchesDirectedHit`：已知 QQ/微信落点按名+大小精确匹配 |

> 第 5 节是本轮（改造为「定位 + 高亮」）新补的。原先把文件按类型分派给查看器的
> `decide` / `targetFromMime` 已经删除，测它们的两节也一并删除 ——
> **不留测已删函数的死断言**。现在这条链路唯一会「猜」的就是路径反解，所以把断言
> 集中到「猜得准不准、猜不准时会不会硬用」上。
>
> 第 9 节是补覆盖盲区：`uniqueName` 原本是 `IncomingActivity` 的私有方法，
> 脱离 Android 根本测不到。**判据就该待在能离线断言的纯 Java 层**，所以把它下沉到
> `IncomingFile` 再补断言。

## 3.15.0：「反解不出来」之后怎么办

真机反馈「有显示，但**不会定位到源文件所在目录，就算拿到了 root 权限也不能**」。
判断：既然 root 也不行，问题**不在权限身份** —— 而是三个反解策略（`file://` 直取、
`_data` 列、去掉首段拼回外部存储根）**一条都没命中 QQ 的 provider 形态**，候选路径
压根没解析出来。于是这一版补两件事：

1. **诊断输出**（第 15 节）：反解失败时，把 URI 原文、scheme/authority/path 各段、
   `DISPLAY_NAME`/`SIZE`/`getType`、当前 shell 身份、**逐条候选与各自被拒的原因**、
   搜索记录、复制兜底结果，全render 成一段文本，`Log.i` 出去并弹**底部面板**展示，
   面板里带「复制诊断信息」按钮。目的很直接：**用户在真机上复现一次，就能把
   「QQ 到底给了什么 URI」这个开发机上无法验证的问题带回来**。
2. **按名 + 大小搜索**（第 10~14 节）：反解失败后、复制兜底前，用我们**确知的两件事**
   （显示名 + 大小）去存储里找。先搜已知 QQ/下载目录，再限时全盘搜。判据
   **只做精确匹配**：名字逐字符相同 **且** 大小逐字节相同 —— 大小是命门，防同名不同文件。
   搜到的每一条**仍要用 `FileRepository.listDir` 按真实身份复核可见性与真实大小**，
   「搜到了」不等于「就是它」。搜不到就把过程记进诊断，下一轮再判断。

判据与命令构造放在纯 Java 的 `IncomingFile`（第 10~15 节全部可离线断言），
Activity 只做执行胶水。搜索命令由 `FileRepository` 走 **shell 通道**（`find`）——
只有 ROOT / Shizuku 身份进得去 `Android/data`，这正是「root 也不能」时要回答的问题。

### 为什么身份探测是惰性的

「外部打开」最常见的结果是**反解成功**，那条路根本不需要知道 shell 身份。若在
`onCreate` 无条件探测一次，等于**每次打开都执行一次外部进程**（`su` / Shizuku），
有 root 的机器还可能弹授权框 —— 为一个多数时候用不到的字段付这个代价不合理。

所以身份只在**真正要用它的两处**补齐：① 发起**搜索**之前（搜索通道按身份分流），
② 渲染**诊断**之前（若那时还没探过就按需补一次）。目标是**反解成功的路径上，
一次权限探测都不发生**。`verify-incoming.py` 第 12 节把这条钉死：`onCreate` 与
`locateOriginal` 里都不许出现探测，`locate` 里探测必须排在反解之后，
`searchOriginal` 里探测必须排在搜索之前。

## 3.15.0 续：让诊断「自动回答问题」（第 16/17 节）

第二版真机反馈推翻了我们上一轮的假设：用户观察到 **MT 管理器这类工具，不借助
adb / root 也能进 `Android/data`**。也就是说「Android 11+ 根本读不到别的 App 的
`Android/data`」这个推断**不成立** —— 目标很可能是**可达的**，只是我们走的路不对。

### 任务 A：诊断要逐目录**实测**，而不是继续猜（第 17 节）

所以诊断里加了一节「**目标目录可访问性实测**」：对下列目录**逐个**做列举测试，
并**分通道**记录（Java 的 `File.listFiles()` vs shell 的 `ls -la`）——两个通道的结论
若不一致，答案往往就藏在那条差异里：

| # | 目录 | 角色 |
|---|------|------|
| 1 | `/sdcard/Android/data/` | 能不能进这道门的根问题 |
| 2 | `/sdcard/Android/data/com.tencent.mobileqq/` | QQ 的数据目录 |
| 3 | `/sdcard/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/` | QQ 收到文件的落点 |
| 4 | `/sdcard/Tencent/` | 另一个常见落点 |
| 5 | `/sdcard/Download/` | **对照组**（几乎必然可列出） |

每目录记录：**成功与否**、**列出几项**、**失败原因**（Java 侧用
`Os.open(O_RDONLY)` 问内核要 errno —— Scoped Storage 典型是 `EACCES`/`EPERM`）；
并给出一句**只陈述事实**的「结论」：一致 / 不一致。当出现「Java 列不出、shell 能列出」
时写明「访问应改走 shell 通道」—— 那恰好是最可能的一种事实。

渲染与判据都在纯 Java 层（`IncomingFile.probeDirs` / `formatAccessReport` /
`ListProbe` / `DirProbe`，第 17 节），真正去列目录的动作按身份分别在 Android 侧
（`FileRepository.probeListJava` / `probeListShell`）。**这一节只在定位失败时跑**
（它是诊断的一部分），成功路径零开销。

> `describeJavaListFailure` 里用 `O_RDONLY` 打开目录、**不加 `O_DIRECTORY`** ——
> Android 的 `OsConstants` 没导出后者（Linux 有、AOSP 的 java 层没暴露）。
> 以 `O_RDONLY` 打开目录本来就合法，errno 语义与加不加 `O_DIRECTORY` 无关。

### 任务 B：修 `timeout` 的依赖 + 搜索失败必须记进诊断（第 12/16 节）

上一版搜索命令**只**用系统 `timeout`。某 ROM 没有它时，`timeout: not found` 会被
`2>/dev/null` 吞掉，搜索**静默退化**成「0 候选」，下一轮排查仍然只能猜。本版：

1. **内置 busybox 的 `timeout` 优先**（本工程随包内置了 busybox，它的 `timeout` 一定在），
   系统 `timeout` 只作次选 —— `buildSearchCommand` 用 `[ -x <busybox> ]` 探测后再选；
2. **没有可用 timeout 就明确报出来**（`MARK_NO_TIMEOUT`），不静默；
3. **超时也报出来**（退出码 124 → `MARK_TIMEOUT`），把「只搜了一部分」和「搜完没命中」分开；
4. 结局（正常 / 超时 / 无 timeout 工具 / 无输出 / 无 shell）与候选路径**一起**由
   `SearchOutcome` 带出，`describe()` 写进诊断 —— **搜索失败不再无声无息**。

### 任务 C：本轮**不改**访问策略

`targetSdk` 仍是 34，不引 SAF、不动反解判据 —— 先把「到底能不能访问」这件事**测清楚**，
再谈怎么改。`verify-incoming.py` 第 13 节把这条钉死（`targetSdk` 必须是 34、Activity 里
不许出现 `ACTION_OPEN_DOCUMENT_TREE` / `DocumentFile` / `takePersistableUriPermission`、
`matchesDisplayName` 仍在使用）。

## 3.15.0 再续：从「猜 URI」改成「定向目录查找」（第 18 节）

用户用 MT 管理器确认了 QQ 收到文件的**确切路径**：

```
/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/
```

这就把「反解 URI 拿路径」这个难题**消解掉了** —— QQ 的下载目录是**固定、已知**的，
不用再猜。于是在**反解失败之后、全盘搜索之前**，插了一条「**定向目录查找**」：

1. 直接用 `FileRepository.listDir(dir)` 列出这些已知落点（QQ 新旧 `QQfile_recv`、微信
   `MicroMsg/Download`）—— `listDir` 会**按身份自动选通道**：普通身份走 Java
   （`Android/data` 在 `targetSdk 34` + Scoped Storage 下会被拒），**root/Shizuku 走 shell 的
   `ls`**（shell 是另一个进程，不受 App 的 `targetSdk` 限制）；
2. 在列出的条目里按**显示名相同 + 大小相同**精确匹配（`matchesDirectedHit`）；
3. 命中 → `File(dir, name)` 作候选路径，走既有的「可见性校验 → 定位」链路。

它比全盘 `find` **更快**（只扫一两个目录）、**更可靠**（**不依赖 `timeout` / `find`
是否存在**，用的是与可见性核对同一条通道）。整条链变成：

**反解（快路径）→ 定向查找（新增，优先）→ 全盘搜索（补充）→ 兜底复制。**

诊断面板里把定向查找的过程也显示出来：每个目录**列出了几个条目**、名字相符但
**大小不符**的近失条目、以及命中的那条。

### 为什么之前那版搜索没命中这个目录（排查结论）

上一轮「先搜已知目录」里**已经包含** `Android/data/com.tencent.mobileqq/`（`find`
递归会覆盖 `QQfile_recv`），但真机仍失败。排查后锁定**两条互相叠加**的原因，且都指向
「**搜索没走 shell 通道**」：

1. **身份探测没被触发 / 超时**（主因，和用户那句「拿到 root 也不行」完全吻合）。
   搜索靠 `ShellExecutor.hasShell` 分流，而 `level` 要 `detect()` 才有值。
   `detect()` 里的 `canUseSu()` 是 `su -c "echo ok"`、**只等 4 秒** —— 冷启动时若这是
   本次会话**第一次**要 root，授权对话框一弹就是好几秒，4 秒一到 `canUseSu` 判 false →
   **NORMAL**。于是搜索退回 `searchWithJava`，而 `File.listFiles()` 对 `Android/data`
   在 `targetSdk 34` 下返回 null → **0 候选**。root 明明授了，代码却压根没用到它。
   （对「已经授过 root、Magisk 记着授权」的用户，这一步是即时的，所以定向查找能成。）
2. **`timeout` 缺失被静默吞掉**（次因，即上一节任务 B 修的那个）：旧搜索只用系统
   `timeout`，某 ROM 没有它时 `timeout: not found` 被 `2>/dev/null` 吞掉 → 静默 0 候选。

**定向查找把这两条都绕开了**：它不靠 `find`、不靠 `timeout`，只用 `listDir`
（与可见性核对同一条通道选择），root 身份下 `ls` 能列出 `QQfile_recv`，就能定位。
所以结论是「**换定向查找**」，同时保留全盘搜索作补充。`verify-incoming.py` 第 14 节把
「定向排在全盘搜索之前」「先探身份再列目录」「走 listDir」逐条钉死。

> MT 管理器**不用 root** 也能进 `Android/data`，是因为它的 `targetSdk` 较低（资料指向 ≤29），
> 走的是 Scoped Storage 的「旧版应用兼容」豁免。我们 `targetSdk = 34` 没这个豁免，
> 本轮**不改 targetSdk**（Play 审核 + Android 14 限制的代价太大，那是另一条大手术）——
> 用户有 root，**先让 shell 通道方案跑起来**。

### 为什么诊断面板是底部面板、且内容画在面板自己身上

失败信息（URI、各段、逐条候选…）远超一句 toast 的量，所以改成 `BottomSheetDialog`。
**绝不在面板之上再开 `AlertDialog` 之类的第二个窗口** —— 那种叠加在不同 ROM 上
层级顺序不一致，真机表现是「点了没反应、什么都看不到」（见 `SheetDialog.kt` 的教训）。
面板正文放在可滚动区、按钮固定在下方，长文本也不会把按钮顶出屏幕。

### 为什么转义自己实现一份

`quoteShell`（第 10 节）的口径与 `ScriptRunner.quote` **逐字符一致**（单引号包裹 +
内部单引号写成 `'\''`）。但 `ScriptRunner` 是 Kotlin（Android 侧），`IncomingFile` 必须
保持「纯 Java、零 Android API」才能被 `javac` 直接断言 —— 纯 Java 类调不了它。
这是一条**极短、语义固定**的转义，因此在此复刻，并靠断言钉住它与 `ScriptRunner.quote`
同口径（`verify-incoming.py` 第 12 节同时盯着 `ScriptRunner.quote` 仍在）。

`verify-incoming.py`（源码不变量）：

纯 Java 约束、`FileRepository` 是否已全部转发到 `IncomingFile`（分类表只有一份）、
归档表是否覆盖 `Archives`、Activity 是否拿 `uri` 当路径（禁）、是否整份读进内存（禁）、
是否信任 extra（禁）、是否打开查看器（禁）、是否执行脚本（禁）、是否用 `File.canRead()`
判可见性（禁）、去重命名是否已下沉（`uniqueTarget` 不许再出现在 Activity）、Manifest 的
intent-filter、`MainActivity` 是否在 `onCreate` **与** `onNewIntent` 都处理定位、
**冷启动时序**（`revealFile` 同步落定 `currentPath`、`applyPendingReveal` 未命中不消费、
`loadDir` 取消旧加载）、`FileListAdapter` 的定位态是否与多选态分离、版本号；以及
**3.15.0 新增**：诊断是否落日志、是否用底部面板（禁止 `AlertDialog` 叠窗口）、是否有
复制到剪贴板、搜索是否走 `FileRepository.searchByNameAndSize` 且命中仍复核、判据与
命令构造是否在纯 Java 层、搜索是否限时、是否**未**放宽 scheme 白名单 / 名字核对 /
可见性判据；**3.15.0 续（第 13 节）新增**：诊断是否逐个目录实测 Java/shell 双通道
（`probeDirs` / `probeListJava` / `probeListShell` / `formatAccessReport` 是否接线）、
实测是否排在身份探测之后、内置 busybox timeout 是否作首选（`busyboxTimeoutPath` +
`$__rxin_to`）、搜索结局是否返回 `SearchOutcome` 并写进诊断、`Diagnostics` 是否带
`accessReport`；以及**访问策略未动**（`targetSdk == 34`、无 SAF、反解判据照旧）；
**3.15.0 再续（第 14 节）新增**：定向目录查找是否接线（`directedLookup` /
`directedLookupDirs` / `matchesDirectedHit` / `FileRepository.listDir`）、定向是否
**排在全盘搜索之前**、是否**先探身份再列目录**、过程是否进诊断。

## 冷启动的时序陷阱（别随手删那几行）

「从 QQ 点文件、App 没开着」是最主流的用例，也是唯一会踩到这个坑的用例。
`lifecycleScope` 用 `Dispatchers.Main.immediate`，于是 `onCreate → onStart → onResume`
跑在**同一条主线程消息**里，而 `loadDir` 里 `currentPath = path` 要到 IO 续体回来
（那条消息之后）才赋值。冷启动时同一个消息里有三处加载：

```
onCreate:  detectShellLevel()   → [协程C]
           revealFile(parent)   → [协程A]
onResume:  loadDir(currentPath) → [协程B]
```

- 若不**同步**把 `currentPath` 落成 parent，B 捕获到的是**默认目录**：

  - A 先完成 → B 随后把列表切回默认目录，**盖掉**定位结果；
  - B 先完成 → B 用默认目录消费待高亮，找不到 → 若此时**无条件**清掉待高亮，
    A 之后到了正确目录也没得高亮了，还多一句误报。

三条修法互相独立、都要在（`verify-incoming.py` 第 8 节逐条钉住）：

1. `revealFile` **同步**写 `currentPath = parent`（让 A/B/C 捕获到同一个目录）；
2. `applyPendingReveal` **只在命中时**清 `pendingReveal`；未命中默认不提示，
   只有「当前目录正是目标父目录」才说一句，且只说一次（`revealMissNotified`）；
3. `loadDir` 串行化：新加载先 `loadJob?.cancel()` 掉旧的。

> 这类时序问题**断言跑不出来**（没有真机就没有真实调度）。所以除了断言，
> 还在 `revealFile` / `loadDir` / `applyPendingReveal` 的注释里把「谁先谁后、
> 为什么同步赋值能消除交错」写清楚 —— 后人想删那行之前得先推翻那段推理。


## 一条要记住的取舍

判据本体放在**纯 Java 的 `IncomingFile`** 里，而扩展名表原来是 `FileRepository`
（Kotlin / Android）里的。纯 Java 类没法反过来调 Kotlin object，所以为了「只有一份表」，
是让 `FileRepository` 的判定方法**转发**到 `IncomingFile`，而不是各写一份。

**归档（压缩包）识别不做表，直接委托 `Archives`** —— 它是解压能力的权威来源。
这里踩过一次真实的坑：本来自己抄了一张归档扩展名表，但本类的 `extOf`
（`dot <= 0`，点开头算隐藏文件）与 `Archives` 的 `extOf`
（`dot < 0 || dot == len-1`）口径不同，于是 `.zip` 这种「纯点开头 + 单扩展名」
的名字两边给出相反答案 —— 既有链路进归档面板，新入口却当成隐藏文本。
§7/§8 就是为这个盲区补的断言：既钉「本类认这些名字」，也钉「与 `Archives` 逐条一致」。

**「定位」与「多选」是两条独立的视觉通道。** 定位高亮只认 `FileListAdapter.revealPath`，
绝不写进多选集合 `selected` —— 否则用户一进多选就会看到莫名其妙的「已选中」。
两者同时命中时以多选为准（用户正在进行的操作优先）。`verify-incoming.py` 把这条
分离钉死：一旦有人图省事把定位塞进 `selected`，立刻变红。

## 变异测试

对关键判据做过变异，确认对应断言会变红（详见交付报告）：

- 去掉 `safeName` 里的分隔符过滤 → 第 1 节「只取最后一段 / 敌意输入批量消毒」变红；
- 把 `isHandledScheme` 改成永远 `true` → 第 2 节「http 拒绝 / 私有 scheme 拒绝」变红；
- 把 `matchesDisplayName` 放宽成「只要非空就算相符」→ 第 5 节「名字不符 / 近名只有
  完全相同才接受」共多条变红；
- 把流式复制改成 `readBytes()` → `verify-incoming.py`「没有把整份内容读进内存」变红；
- 把可见性判定从 `FileRepository.listDir` 改回 `File.canRead()` → `verify` 变红；
- 把定位路径写进多选集合 `selected` → `verify` 的「定位与多选分离」变红；
- 把归档识别改回「用自己的 `extOf` 判」的旧口径 → 第 7/8 节与 `verify` 的
  「委托 `Archives`」变红。
- 把 `uniqueName` 的 `(n)` 递增写成恒 `0` → 第 9 节共 **7 条**变红（含「连续 5 次不撞车」）；
- 把 `uniqueName` 的扩展名处理写坏（丢掉后缀）→ 第 9 节共 **5 条**变红；
- 去掉 `revealFile` 里同步的 `currentPath = parent` → `verify` 的「同步写 currentPath」变红；
- 把 `applyPendingReveal` 改回「无条件消费 pendingReveal」→ `verify` 的
  「只在命中后才清」变红。
- 把 `revealFile` 的换目录判据写成恒假（`switchingDir = false`）→ `verify` 的
  「换目录判据是 `parent != currentPath`」变红。
- 去掉 `quoteShell` 里的单引号转义（`replace("'", ...)` 改成恒等）→ 第 10 节
  「单个单引号被转义 / 转义可逆」，以及第 12 节「敌意名按 quoteShell 拼装 / 原始名字不出现」
  共 **4 条**变红；
- 去掉 `buildSearchCommand` 的 `timeout ` 前缀 → 第 12 节「命令带 timeout N」与
  `verify` 第 12 节「搜索命令**限时**」变红；
- 把 `matchesSearchHit` 放宽成「只比名字、不比大小」→ 第 14 节「同名但大小不同 →
  不可信 / 目标大小未知 → 不可信 / 同名文件里只有大小也对得上的那个被接受」共 **3 条**变红；
- 去掉 `parseSearchOutput` 的「只认绝对路径」判断 → 第 13 节「噪声被滤掉」变红。
- 把 `ensureShellLevel()` 挪回 `onCreate`（无条件探测）→ `verify` 第 12 节
  「onCreate **不**探测身份 / locate 里探测排在反解之后」变红。
- 把访问报告的「不一致 → 改走 shell」结论换成「一致」→ 第 17 节
  「Java 列不出 / shell 能列出 → 结论写明「不一致」」与「诊断带出「不一致」结论」共 **2 条**变红；
- 把 `parseSearchStatus` 的两个标记判断**调换顺序**（先判超时）→ 第 16 节
  「两种标记都在 → 优先 NO_TIMEOUT_TOOL」变红；
- 把实测的 shell 通道写成 Java 通道（`probeListShell` → `probeListJava`）→ `verify` 第 13 节
  「实测走 shell 通道」变红；
- 去掉 Activity 里取 busybox 路径那句（`busyboxTimeoutPath` → `null`）→ `verify` 第 13 节
  「Activity 搜索时取 busybox 路径传给搜索」变红。
- 去掉 `matchesDirectedHit` 的大小判据（只比名字）→ 第 18 节「同名但大小不同 → 不命中」与
  「目录条目里只有名字+大小都对上的那个命中」共 **2 条**变红；
- 把 `locate` 里的定向查找挪到全盘搜索**之后** → `verify` 第 14 节
  「反解 < 定向查找 < 全盘搜索」变红。
- 把 `shouldWarnNoAccess` 弱化成「只看路径」（漏掉身份与探针两个条件）→ 第 19 节
  「空目录不算 / ROOT 身份不提醒 / ADB 身份不提醒 / 身份未知不提醒 / 全枚举只有一种为真」
  共 **5 条**变红（其余 318 条全绿）；
- 把 `isUnderAndroidData` 的段比对改成 `contains("Android/data")` → 第 19 节
  「名字里含 Android/data 的**同级**目录不算」与「名字像但不是 → 不提醒」共 **2 条**变红。

## 3.15.3：浏览到 `Android/data` 的「无权限」提醒（第 19 节 / `verify` 第 15 节）

判据在 `IncomingFile.shouldWarnNoAccess(path, shellLevelLabel, javaProbeOk)`，
**三个条件同时成立**才为真：路径真在 `Android/data`（或 `Android/obb`）之下 +
当前身份是「普通」（无 shell 通道）+ `probeListJava` 也列不出。

### 为什么不能拿「列表是空的」当判据（本次最关键的一条）

`FileRepository.listWithJava` **先塞 `..` 再列**，`listFiles()` 返回 null 时直接
`?: return result` —— 于是「读不出来」的返回值是**只含 `..` 的非空列表**，
`items.isEmpty()` **恒假**。所以判「真的访问不了」必须**显式再探一次**
（`FileRepository.probeListJava`）。探针的 `ok` 字段把两者分得很干净：
**空目录返回长度 0 的数组（探针 ok），无权限返回 null（探针 fail）**。

第 19 节把这条钉成「全枚举 8 种组合里只有一种为真」，任何把条件放松/收紧的改动都会变红。

### `isUnderAndroidData` 的两个坑

1. **按路径段比对**，不是 `contains("Android/data")` —— 否则
   `/storage/emulated/0/我的Android/data备份` 会被算进来（有断言）；
2. **先归一化**：`/sdcard` 与 `/storage/self/primary` 都展开成 `/storage/emulated/0`
   （软链 / 同义词），压掉重复斜杠、去掉尾斜杠。用户从不同入口进来前缀真的不一样。

`Android/obb` 一并纳入 —— 与 `Android/data` 同属「本应用无权限、需要特权身份」那一类。

### 执行侧的一次 IO

`FileRepository.listDirChecked(path)` 一次调用同时返回列表与判定：分开两次调用会在
两个线程时刻之间给目录状态留出变化窗口，结论可能自相矛盾。探测**只在
「路径在 Android/data 之下 且 没有 shell 通道」时才做**，其余目录零额外开销。

### 提醒的「说过了」开关

`MainActivity.noAccessWarnedFor` **按路径记**：`onResume` / 刷新键 / 书签跳转都会重新
`loadDir` 同一个目录，没有它面板会反复自己弹（那不是提醒，是骚扰）；读得出来或换目录时复位。

### 面板铁律照旧

`sheet_no_access.xml` 直接 `setContentView`，**绝不在 `BottomSheetDialog` 之上再开第二个
窗口**。文案与下载地址一份真源（`IncomingFile.NO_ACCESS_TITLE` / `NO_ACCESS_BODY` /
`SHIZUKU_DOWNLOAD_URL`），与「定位失败」那张面板共用同一个地址、但**正文是两份不同的文案**
（一处说「没能定位文件」，一处说「这个目录你打不开」）—— 混用会让其中一处答非所问。

### 顺便：XML 注释也要剥

`verify-incoming.py` 新增 `strip_xml_comments()`。布局头部注释里解释「文案为什么不写死在
XML 里」，那句话本身就含 `Android/data` —— 不剥注释去判「布局里没写死文案」，
红的是注释、不是问题（与 `strip_comments()` 同一个道理，只是换成了 XML）。
