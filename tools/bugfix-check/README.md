# tools/bugfix-check —— BUG日志-20261110 的 7 个修复的源码不变量断言

`BUG日志-20261110.md` 里的 7 个问题（BUG-001 ~ BUG-007）有一个共同点：
**它们只在真机 / ROOT / 跨进程 / Activity 生命周期的真实环境里表现，普通开发环境
既跑不出来，也不会编译报错。** 例如：

- 持久 `su` 里一条命令内部写了 `exit`，会连持久 shell 一起带走，`marker` 打不出来；
- ROOT-only 路径普通身份 `stat` 失败，被当成「文件不存在」，删除/重命名静默变成空操作；
- 一个界面销毁时顺手 `deleteRecursively` 掉全局暂存根目录，删掉别的界面/进程正在用的副本；
- 两个进程各自维护 SharedPreferences 缓存，`load → 改 → save` 整表覆盖，更新随机丢失；
- `File.exists()` 跟随符号链接，悬空链接在冲突判断里被当成「空位」。

本环境没有真机、没有 ROOT，没法靠运行用例复现。能钉住的只有**修复后的源码契约**：
只要有人把修复改回旧形态、或者「只改了一半」（入口换了判据、旁路还留着旧写法），
这里的断言就必须变红。判据与 `tools/textslice-check/verify-textslice.py` 同源：
只读源码、不编译、不跑 gradle、无第三方依赖。

## 跑法

```sh
# 直接跑（工程根目录或任意目录都行）
python3 tools/bugfix-check/verify-bugfix.py

# 或走 shell（Windows 上会自动找 python；也可用 PYTHON=... 指定解释器）
sh tools/bugfix-check/run.sh
```

输出为逐项 `ok` / `FAIL`，最后一行 `结果: N 项通过, M 项失败`；有失败时 exit 1。
只读 `app/src/main` 下的源码，不写任何工程文件。

## 基线：为什么相信「修复前会红」

一套「修复前红、修复后绿」的断言，必须**证明它真的会红**，否则永远是绿的，
等于没有。本轮写了两个配套的自检手段：

```sh
# 把判据指向「修复前的源码快照」（本轮快照是 .workbuddy/backups/bugfix-20261110-192227）：
BUGFIX_ROOT="<修复前快照目录>" python3 tools/bugfix-check/verify-bugfix.py
```

2026-11-10 本轮实测（83 条断言）：

| 源码树 | 结果 |
| --- | --- |
| 修复前快照（改动前抓的备份） | **2 项通过, 81 项失败** |
| 修复后工作区 | **83 项通过, 0 项失败** |

> **开源版说明**：本仓库不含 HTTP 模块，因此依赖 `http/HttpApi.kt` 的 2 条断言会输出
> `skip`（不计入通过/失败）。开源版实测：**79 项通过、0 项失败、2 项跳过**。

修复前就成立的那 2 条不是漏判，而是**既有正确行为的护栏**（修复不许把它们改坏）：

- `HttpApi` 里 ROOT-aware 的 `isDirectory(file)` 助手本来就存在 —— 问题只是 `transfer()`
  没用它；
- `FileOps.stageToCache` 本来就落在唯一目录下 —— 问题在调用方的全局清理。

两条的基线证据留档在 `build/baseline-prefix.log` / `build/current.log`
（`build/` 是生成目录，可随时删；重跑即可再生）。

**以后再改断言，必须用 `BUGFIX_ROOT` 对修复前快照复跑一次，确认该红的仍然红。**
（工程里已有教训：「断言要能真的失败才算数」。）

## 7 节断言覆盖

共 **83 条**（开源版实测 79 条执行 + 2 条 HTTP 断言跳过），每节标题就是对应的 Bug 编号：

| 节 | 条数 | 钉住的东西 |
| --- | --- | --- |
| BUG-001 | 11 | 持久 shell 的命令用**子 shell** 包装（里面的 `exit` 不再杀会话）；`execWithStatus` 带退出码协议；退出码由**外层** shell 在子 shell 结束后打印；`commitWithRoot` 用 `RootShell.run` 拿码、提交后再做一次内容/版本复核；`exit 4` 映射 `WRITE_CONFLICT`；`RootShell.Res.code` 协议保留 |
| BUG-002 | 7 | `deleteRecursive` 不再把「普通 `exists()` 为 false」直接当成已清理；用 ROOT `test -e`/`test -L` 复核；`delete()` 用 `deleteRecursive` 的返回值统计；`rootRemove` 在 `rm -rf` 后用同一身份确认消失；`entryExists` 统一判据存在 |
| BUG-003 | 9 | `rename` 不再 `mv -f`；执行前用统一身份拒绝已存在目标、命令层也带存在性条件（不覆盖）；`mv` 成功后用 ROOT 复核；`HttpApi.rename` 的源/目标预检同样统一身份 |
| BUG-004 | 10 | `entryExists` / `isDirectory` / `ensureDirectory` 三个统一身份入口；`copy`/`move` 的目标目录判断不再裸 `destDir.isDirectory`；`MainActivity.pasteInto` 的来源过滤、目录判断、冲突判断全部改统一身份；`HttpApi.transfer` 及其 ROOT-aware `isDirectory` 助手 |
| BUG-005 | 22 | 全工程不再调用/定义 `clearStage`，也没有任何地方递归删 `rxin_stage` 根目录；`sweepStaleStage` 冷启动兜底 + 只清遗留条目（pid/时间判定）+ `newStageDir` 目录名带进程身份；三个 Activity 的 `onDestroy` 不再清全局；`EXTRA_STAGED_DIR` 写入端（MainActivity/ArchiveSheet）与读取端（两个查看器）连通，且只在 `isFinishing` 时释放自有目录；`compress` 每次用独占目录并在 `finally` 清理；`stageToCache` 落在独占目录 |
| BUG-006 | 15 | 不再用 SharedPreferences 写书签（无 `putString`）；数据落到 `filesDir/rouxin_bookmarks.json`；跨进程文件锁（`FileChannel`/`.channel` 的 `lock()`）；`withLock` / `transaction` 事务助手；`add`/`take`/`insert`/`rename` 各自在同一事务内完成、不再各自 `load + save`；写路径 tmp + `fd.sync()` + `renameTo`；`@Synchronized`；`load` 在锁内且不再直接读 SP；旧 SP 只做一次性迁移；`save(context, list)` 公开 API 保留 |
| BUG-007 | 9 | `copyOne` / `targetsAreSafe` / `move` / `uniqueTarget` / `uniqueDir` 全部走 `entryExists`（悬空链接也算被占用）；普通文件写入前处理目标符号链接、不跟随写穿；复制链路不再出现裸 `finalTarget.exists()`；`entryExists` 自身把符号链接计入存在 |

## 判据写法（为什么不是「复制一句实现」）

1. **判「不许出现某标识符」之前先剥注释。** 源码里解释「为什么**不**这么写」的
   注释会让断言永远红（`strip_comments`，含 Kotlin 的 `"""` 原始字符串）。
2. **用函数体 / 窗口片段定位，不在整份文件里裸找。** 这里踩过两次：
   - BUG-001 的 `exit 4` 是**命令字符串**里的退出码，整份文件裸找 `4` 会把它当成
     「退出码映射」的证据 → 先 `strip_strings` 抹掉字符串内容，再判代码；
   - BUG-007 的 `isSymbolicLink(target)` 在「源是符号链接」的分支里也存在，整份
     `copyEntry` 里裸找会误绿 → 只取**普通文件写入那段**（最后一个
     `onEntry(source.absolutePath)` 之后）再判先后顺序。
3. **认行为/结构，不认某一句实现的写法。** 例如 `test -e PATH` 与 `[ -e PATH ]`
   是同一判据的两种写法；`FileChannel.lock()` 也可以写成
   `FileOutputStream(...).channel.lock()`；`commitWithRoot` 可以返回 `Int`、`Res`
   或直接比较 `== ROOT_EXIT_CONFLICT`。判据用 **OR 覆盖这些等价形态**，但覆盖
   之后仍必须用 `BUGFIX_ROOT` 在旧实现上确认**仍然红**。
4. **断言与实现的耦合边界是「契约」而不是「细节」。** 函数名/公开 API
   （`entryExists`、`isDirectory`、`ensureDirectory`、`sweepStaleStage`、
   `newStageDir`、`deleteRecursive`、`uniqueTarget`、`commitWithRoot`、
   `EXTRA_STAGED_DIR`、`WRITE_CONFLICT` …）是本次修复的接口约定，改名要同步改
   断言；但如果只是为了变绿而放宽判据，就等于把 Bug 放回去。

## 不覆盖什么（验证边界）

- **不是行为测试**：不启动 shell、不模拟 ROOT、不跑 Android runtime。它只保证
  「源码契约在」，运行时行为仍要真机 / 构建后验证；
- **不编译、不跑 gradle**：构建由 Lead 统一做（避免并发锁冲突），语法/编译错误
  由构建暴露，不在这里兜；
- **静态断言管不到运行时竞态**：例如「检查目标不存在」与「执行 mv」之间的窗口、
  跨进程锁的实际争用。这些由实现里的同一身份判定 + 锁 + 原子替换去收敛，
  断言只确认这些机制存在且没被绕过。

## 改这些文件之后必跑

`app/src/main/java/com/rxinfiles/tool/` 下的：

`FileOps.kt`、`ShellExecutor.kt`、`TextFileRepository.kt`、`BookmarkStore.kt`、
`MainActivity.kt`、`ImageViewerActivity.kt`、`TextViewerActivity.kt`、
`ArchiveRepository.kt`、`ArchiveSheet.kt`、`RxinApp.kt`
（完整版还有 `http/HttpApi.kt`；开源版不含该文件，对应 2 条断言跳过）

## 维护约定

- 断言总数与分节数只增不减；新增 Bug 时**新增一节**（标题写 Bug 编号）；
- 合法重构被判红（误报）时：先确认修复没有退化，再调整判据，并在本节记一行原因；
  放宽后的判据必须用 `BUGFIX_ROOT` 对修复前快照复跑，**确认该红的仍然红**；
- 本套件与 `tools/textslice-check/verify-textslice.py` 同风格（`strip_comments` /
  `body_of` / `section` / `check`），新增判据照着写；本目录不放 Java，
  不涉及 `tools/code-check/check-code.py` 的 `*Check.java` 执行要求。
