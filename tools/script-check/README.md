# script-check —— 脚本执行协议的实测脚手架

这个目录回答两个问题：

1. **「把脚本内容经 stdin 喂给 sh 执行」这套协议到底成不成立。**
   答案是靠真 shell 跑出来的，不是读文档推出来的 —— 事实上最后推翻了 POSIX 文档的字面描述。
2. **「脚本跑不起来」到底是脚本的问题，还是它压根不是文本脚本。**
   后者（加密 / 编译过的 `.sh`）是 3.11.1 加的一层，判据同样全是实测来的。

## 在用的

| 文件 | 作用 |
| --- | --- |
| `verify-scriptpipe.py` | **回归断言，253 项**。四层：① 在 mksh / dash / busybox ash 三家真 shell 上跑 stdin 协议；② 对 `ScriptPipe.kt` / `ScriptRunner.kt` / `ScriptFormat.kt` / `TerminalActivity.kt` / `TerminalView.java` 的源码不变量断言；③ **跨文件比对魔数清单**（Kotlin ↔ shell 诊断脚本）；④ 第 9 节把**脚本结束标记**（`ScriptDone.java` 里那个 OSC 2）的字节在三家 shell 上真打出来验一遍。改动这几个文件后必跑。 |
| `DoneMarkCheck.java` + `run-java-check.sh` | **脚本结束标记协议的 Java 半边，34 项**（3.13.1 新增）。`ScriptDone` 是纯 Java（无 Android API），所以一条命令就能编了跑 —— shell 那半边（真出什么字节）在第 9 节，两端共同认定同一个格式串。跑法：`JAVA_HOME=… sh tools/script-check/run-java-check.sh`。 |
| `diagnose-sh.sh` | **排查工具（不是断言）**：给一个跑不起来的脚本，先判「它到底是不是文本脚本」（魔数 / NUL / shebang 指向 / 疑似密文块），再判「bash 专属语法」还是「括号/引号不配平」。二进制还会报**目标架构**与**动态解释器**，并实测所在目录能不能执行文件。纯 POSIX，可直接丢到 Android 上跑：`sh diagnose-sh.sh 1.sh`。 |
| `verify-diagnose.sh` | 上一条的回归，**66 项 × 3 轮**（GNU 工具 / toybox applet / busybox applet）。锁住「措辞指纹」「括号配平」「加密形态分流（含 **含 NUL 的脚本**）」「ELF 架构 / PT_INTERP / 目录可执行性」几块判断。**只在开发机上跑** —— 它会拒绝参数，缺夹具时一律 `[skip]` 而不报失败（见下）。 |
| `probe-direct-exec-cmd.sh` | 直接执行那条命令形状的**行为回归，45 项**：在 mksh / busybox ash / dash 三家真跑一遍，断言命令里没有 `exit`、**跑完后外层 shell 还活着**（`STILL_ALIVE rc=…`）、`126`/`127` 的解释各自出现且**两个分支互斥**、退出码原样透出。谁把「脚本结束自动关终端」加回去，这里当场失败。 |
| `probe-sniff-cmd.sh` | 「借执行身份读头部」那条探测命令的**行为回归，41 项**（3.11.5 新增）：`##r?`/`##x?` 两行在三家 shell 上的位置与形状、`od` 输出行**整行只由十六进制与空格组成**（否则 Kotlin 侧会整行丢掉、头部读不出来）、头部 4 字节解出来等于文件真实前 4 字节、**空文件不产生假头部**；末尾两条验「su 回显污染」的**前提**（回显的整行不等于 `##r1`、标记行排在十六进制行之前）。 |
| `probe-defer-rc.sh` | `deferToShell` 命令形态的**退出码语义，11 项**（3.11.5 新增）：`if [ -x ] … else sh … fi` 之后 `$?` 是否真等于**所走分支**的退出码（7 / 9 / 0 都要原样透出），以及 `sh` 兜底那一支确实会被走到。这条语义是 `fi` 之后不再显式赋值的前提，必须三家都实测过。 |
| `probe-nul-script.sh` | **含 NUL 的脚本到底能不能被 `sh` 跑，33 项**（3.11.6 新增）：三份夹具（载荷在 `exit 0` 之后 / 包在引号 heredoc / 连 shebang 都没有）× mksh / dash / busybox ash，断言**退出码 0、打印正常、载荷没被当命令**；再加 ELF / gzip 两个对照组（必须全失败）。顺带量出「三家 shell 都会悄悄丢掉载荷里的 NUL」（355 → 353 B）。 |

跑法（在 WSL 里，需要 `mksh`、`/bin/dash`、busybox）：

```sh
python3 tools/script-check/verify-scriptpipe.py
bash    tools/script-check/verify-diagnose.sh
bash    tools/script-check/probe-direct-exec-cmd.sh
bash    tools/script-check/probe-sniff-cmd.sh
bash    tools/script-check/probe-defer-rc.sh
bash    tools/script-check/probe-nul-script.sh
# 纯 Java 那一半（开发机直接跑，不需要 WSL）
JAVA_HOME="C:/Program Files/Java/jdk-20" sh tools/script-check/run-java-check.sh
```

## ⚠ `diagnose-sh.sh` 和 `verify-diagnose.sh` 别搞混

真机上发生过一次：用户把 `verify-diagnose.sh` 拷到手机上跑，得到**一片 `[FAIL]`**
（15 项），以为自己的脚本有 15 个问题。实际上那些全是「夹具造不出来」：

| 真机上必然发生的 | 后果 |
| --- | --- |
| 没有 `/bin/dash` | ELF 夹具拷不出来 |
| `$HOME` 为空 | 路径拼成 `//shells/usr/bin/mksh` |
| 没有 `python3` | 6.6 节的 ELF 夹具一个都造不出来 |
| 没有 `/tmp` | 收尾 `cd /tmp` 报错 |

修过之后的规矩（`verify-diagnose.sh`）：

- **不接受参数**：`sh verify-diagnose.sh 驱动.sh` 现在直接退 2 并告诉你该用哪个脚本。
- **第 0 节先报环境清单**，任何 `[skip]` 都有上下文。
- **夹具造不出来 → `[skip]`，不计失败**。判据是「**夹具真的建出来了没有**」，
  不是「`command -v python3` 有没有」—— 真机上的 python3 也可能跑不通。
- **不假设 `/tmp` 存在**：`$TMPDIR` → `/tmp` → 当前目录逐级退回。
- **驱动 shell 显式解析**（dash → mksh → busybox → 系统 sh），`run()/runf()` 先确认
  解释器真的存在再建符号链接。从前链接是死链时，内核会跳过它继续搜 PATH ——
  **静默用了系统 sh，而标题还写着「sh=dash」**，等于标签在说谎。

手机上要诊断某个脚本，永远只有一个入口：

```sh
sh diagnose-sh.sh 你的脚本.sh
```

## 工具实现差异（在 toybox / busybox 下才暴露的那些）

- **`grep` 会把含 NUL 的输出文件当二进制**：`grep -F 模式 输出文件` 打印的是
  `Binary file … matches` 而**不是匹配行**。诊断脚本的输出里含 ELF 夹具的字节，
  所以断言里**不要捕获匹配行**，只用 `grep -q`（看退出码）。
- **`od` 必须带 `-v`**：不带时 GNU/busybox/toybox 三个实现都会把连续重复行压成 `*`，
  NUL 计数因此少算（实测 5567 vs 5543）。
- **`tr -c '[:print:]'` 不能用**（busybox 的 tr 不支持该字符类）。
- **`df -P` 的列布局三家不一致**，挂载点要走 `/proc/mounts` 最长前缀匹配。
- **Ubuntu 的 toybox 没有 `tr`/`awk`**，垫片只能垫它清单里真有的 applet。

依赖的两个解释器位置：

- `~/shells/usr/bin/mksh` —— 从 apt 取（`apt-get download mksh && ar x … && tar xf data.tar.*`）。
  Android 的 `/system/bin/sh` 就是 mksh，本机用同一份上游才谈得上「模拟」。
- `~/bxbuild/host/busybox` —— `tools/rxinbox/build-busybox.py` 顺手产出的主机 x86_64 版。
- `~/shells/usr/bin/toybox` —— 同样从 apt 取（`apt-get download toybox`）。**注意 Ubuntu 的 toybox
  是精简配置，没有 `tr`/`awk`**，所以 `verify-diagnose.sh` 的垫片只垫它 applet 清单里真有的那几个
  （垫了没有的 = 把命令弄没，会得到一堆假失败）。

## 证据脚本（一次性，保留是为了让结论可复查）

这些是**当初的探测过程本身**，不是回归套件的一部分 —— 结论已经固化进断言，
留着它们是为了任何人想复核时能直接重跑。

| 文件 | 探的是什么 |
| --- | --- |
| `probe-sh0.sh` | `sh -s -- <a> <b>` 里 `<a>` 到底是 `$0` 还是 `$1` |
| `probe-argv0-fix.sh` | 三种修 `$0` 的路子（直接赋值 / eval 降级 / `-c` + `. /dev/stdin`），以及 `PWD` 语义 |
| `probe-stdin-modes.sh` | 完整行为矩阵：`$0` / 位置参数 / `read` / 引号二次解析 / `PWD`，跨四种 shell |
| `probe-enc-forms.sh` | 五种「加密 / 编译」形态在三个 shell 下的报错措辞、可打印率、`od -v` 有没有影响、两个工具箱各缺什么 applet |

跑法：`sh tools/script-check/probe-stdin-modes.sh`（会自己跳过本机没有的解释器）。

## 当初得到的关键结论

| 调用 | `$0` | `$1` |
| --- | --- | --- |
| `sh -s -- /p/x.sh a b` | **shell 自己的名字** | `/p/x.sh` |
| `sh -c '. /proc/self/fd/0' /p/x.sh a b` | **`/p/x.sh`** | `a` |

- `-s` 模式下第一个操作数是 `$1` 而**不是** `$0`，与 POSIX 文档字面描述相反（三家 shell 一致）。
- 往 stdin 开头写 `0='/p/x.sh'` 会被当**命令**执行、报 `not found`，`$0` 一点没变。
- `-s` + `eval` 包一层不报错，但**同样改不掉 `$0`**。
- `PWD` 环境变量一定会被 shell 用 `getcwd()` 覆盖 —— 只能靠真换目录。
- 脚本里的 `read` 在纯 stdin 方案下**一定拿到 EOF**。
- `toybox sh` 直接报 `Unknown command sh`（上游 0.8.9，与 Android 系统 toybox 同源）——
  上游的 `sh` 一直在 `toys/pending/` 未启用。

完整成因见 `升级说明.md` 的 3.11.0 一节。

## 二进制文件不参与「编码修复」（3.11.3 起）

ELF 前 8KB 里**偶然**出现的 `0D 0A` 曾被当成「Windows 换行」（实测 8 个 ELF 命中 0 个，
但这是随机概率问题，不是不会发生）。一旦命中，用户会看到「脚本编码需要处理」，
点「修复并执行」就等于把二进制里的 `0x0D` **全删掉** —— 那不是修复，是损坏文件。

三道防线，由 `verify-scriptpipe.py` 第 6 节逐条锁住（3.11.6 起闸门字段是 `binaryContent`）：

| 位置 | 判据 |
| --- | --- |
| `Preflight.hasEncodingIssue` | `!binaryContent && (hasBom \|\| hasCrlf)` —— **先得是纯文本** |
| `analyze()` | 先判形态；有二进制内容就跳过 BOM / CRLF 扫描（**顺序不能反**） |
| `fixEncoding()` | `isBinaryFile()` 闸门，二进制直接返回 false（防「别处直接调用」） |

`binaryContent` = **魔数命中 或 含 NUL**，**故意不看 shebang**：
「`#!/bin/sh` + 二进制载荷」的脚本能跑，但同样经不起「删掉所有 `0x0D`」——
**能跑 ≠ 能改**，这两个判据不能互相挪用（3.11.6 的教训）。

## 加密脚本「能不能跑」要看架构与解释器（3.11.3 起，3.11.4 修订）

`diagnose-sh.sh` 的第 2 节不再止于「这是 ELF」：

- **目标架构** `e_machine`（ELF 头偏移 18）对本机 `uname -m`；
- **动态解释器**：**从程序头表里读 `PT_INTERP`**，把那条路径原样还原出来 ——
  `/system/bin/linker64` 是 Android 目标；`/lib64/ld-linux-*.so.*` 是桌面 Linux 编的，
  那个路径在 Android 上不存在；
- **没有 `PT_INTERP` 段 = 静态链接**，不依赖任何 linker，架构一致就能直接跑。

### 为什么必须真读 PT_INTERP，而不是「搜候选路径」

3.11.3 的实现是「在文件前 8KB 里 grep 一张候选路径表」。真机上当场翻车：
一份 **arm64 静态链接**的 `shc` 产物，候选表里当然搜不到解释器，于是被归到
「解释器不是本机的 —— 直接执行大概率失败」，**把能跑的文件说成跑不了**。

**搜不到 ≠ 不是本机。** 只有读结构才能把「静态链接」（正常）与「链到别处的 linker」
（真跑不了）分开。ELF64 与 ELF32 的字段偏移完全不同（`e_phoff`/`e_phentsize`/`e_phnum`
各一份；程序头里 `p_offset` @+8 或 @+4、`p_filesz` @+32 或 @+16），
`verify-diagnose.sh` 6.6 节用**自己用 struct 拼出来的夹具**
（静态 / 外来解释器 / arm64+Android linker / ELF32）逐条锁住 —— 偏移量写错会当场失败。

### 第 1 节新增三条能定生死的行

| 行 | 意义 |
| --- | --- |
| `运行身份` | `uid` + SELinux 域。**「可执行性」只代表这个身份** —— ROOT 跑得动不等于 App 内跑得动 |
| `挂载点` | 走 `/proc/mounts` 最长前缀匹配，并带上文件系统类型 |
| `可执行性` | 真跑一次探针：**拷一份真 ELF 到目标目录**再执行它 |

「可执行性」为什么不看权限位：共享存储上的 `rwxrwxrwx` 与 `test -x` 都是假的，
只有 `execve` 说了算。探针是脚本自己建的文件、用完即删，不碰用户的脚本。

### 四个踩过的工具坑

- **`tr -c '[:print:]' '\n'` 不能用**：busybox 的 tr 不支持 `[:print:]` 字符类，
  实测输出 `||||in|||||||||t|…` 这种碎片（把 `[:print:]` 当成了字符集合 `:pritn]`
  的补集），拿去 grep 只会得到假阴性。
- **`df -P 路径` 的列布局三家不一致**：真机上曾把 `/data/misc/profiles/ref/<包名>`
  当成 `/data/1` 的挂载点打印出来。改用 `/proc/mounts` 的最长前缀匹配。
- **探针不能用 `toybox` / `busybox`**：它们是「多合一」二进制，按 `argv[0]` 的 basename
  分派 applet。探针被改名成 `.rxin_exec_probe.NNNN` 后，它打印 `Unknown command`
  并**自己返回 127** —— 与「内核拒绝执行」的 127 撞车，把能执行的目录判成不能执行
  （实测踩过）。改用 `sh`：不带参数 + stdin 给 `/dev/null` 只读到 EOF，退 0
  （dash / mksh / busybox ash 三家一致；去掉执行位是 126、文件不存在是 127，正好当判据）。
- **`$((0x…))` 要先校验格式**：空操作数时多数 shell 会**直接退出整个脚本**，
  后面几节就都不打印了。另外 **mksh 的算术是 32 位** —— 解析 64 位字段时只取低 4 字节，
  高位不为 0 直接放弃，否则会溢出成负数。

## 脚本结束：命令尾不 `exit`，改由 App 收尾（3.11.2 立底线 → 3.13.1 定形态 → 3.13.2 修出口）

这条交互改过四次，值得按顺序记住：

| 版本 | 脚本跑完那一刻 |
| --- | --- |
| 3.9.2 | 命令尾 `exit` + 3 秒后自动关界面（期间点一下可撤销） |
| 3.11.2 | 撤销 ——「输出还没复制走，终端自己就关了」：去掉 `exit` 与定时器，停在交互提示符上 |
| 3.13.1 | **结束态**：输出留在屏幕上、脚本会话被收掉，按任意键（或点提示条）退回文件管理 |
| 3.13.2 | 修出口 ——3.13.1 顺手把软键盘关了、屏幕按键行也收起来，「按任意键」就没有键可按了 |

**三条底线至今有效**，`verify-scriptpipe.py` 第 5 节逐条钉着：

- `ScriptRunner.buildCommand` 的函数体里**不许出现 `exit`**
  （判据先剥掉注释再比 —— 在注释里解释「为什么不能用 exit」不该把断言弄红，踩过一次）；
- 不许有 `scheduleScriptExit` / `cancelScriptExit` / `SCRIPT_EXIT_DELAY_MS` / `exitRunnable`；
- （3.13.2 加）`TerminalActivity` 里**不许再出现「收起软键盘」这个动作**
  —— 「脚本跑完挡住输入」只许由会话层保证，不许靠藏输入入口（藏掉用户就出不去了）。

**为什么命令尾不能 `exit`**（这条是 3.13.1 才彻底想明白的）：PTY 从端一关，
Termux 的等待线程立刻投出「进程已退出」，`cleanupResources()` 顺手把两个 IO 队列关掉；
输入读取线程若还没把 PTY 里剩下的字节送进队列，`ByteQueue.write` 返回 false、
线程直接 `return` —— **输出的最后几 KB 静默丢失**。所以顺序必须是
「脚本自然结束 → App 收到它的最后一行 → App 再收会话」。

**为什么结束态必须留键盘**（3.13.2 才想明白的）：用户反馈就一句
「键盘都被关了，你让我怎么测试任意键退出」。3.13.1 把**输入入口**当成了**终端可用性**，
一起收掉了 —— 而这一屏的规则恰恰是「按任意键返回」，于是变成死页。
实际上「不该再被执行」已经由 `finishIfRunning()` 独立保证（`mShellPid <= 0` 时
`TerminalSession.write` 丢弃一切写入），键盘留着也敲不出任何命令，只会触发退出。
**留着键盘是安全的，前提是**：输入法的字符确实会经过 `onCodePoint` ——
`TerminalView.commitText → sendTextToTerminal → inputCodePoint →
if (mClient.onCodePoint(...)) return;`，这条路径已经钉进断言（读的是 TerminalView.java 本体）。

### 改行为时断言的两条纪律（3.13.2 补）

1. **先剥注释再判「不许出现某某」**。判据看的是真正编译进产物的东西，
   注释里解释「为什么不能用 X」不该把断言弄红 —— 为此 `verify-scriptpipe.py` 有个
   `strip_comments()`，`exit` / `setCursorBlinkingEnabled` / `hideSoftKeyboard` 三处都用它。
2. **改完顺手做一次变异测试**：把刚改掉的行为临时改回去，确认断言**真的会红**，
   并且**只红对应的那一条**。3.13.2 就是这么做了一遍（按键行改回 `GONE`、
   `hideSoftKeyboard()` 加回 `markScriptFinished`），两种都当场红。
   不这么做的话，很容易写出「永远绿」的判据 —— 比如取行时没退到行首，
   截出来是半行，比对永远差一截（3.13.2 真踩了一次，靠它红了才发现）。

**为什么命令尾不能 `exit`**（这条是 3.13.1 才彻底想明白的）：PTY 从端一关，
Termux 的等待线程立刻投出「进程已退出」，`cleanupResources()` 顺手把两个 IO 队列关掉；
输入读取线程若还没把 PTY 里剩下的字节送进队列，`ByteQueue.write` 返回 false、
线程直接 `return` —— **输出的最后几 KB 静默丢失**。所以顺序必须是
「脚本自然结束 → App 收到它的最后一行 → App 再收会话」。

**结束信号**借终端标题（OSC 2）传，命令尾最后挂一条
`printf '\033]2;RXIN-DONE:%s:%s\007' "$$" "$__rxin_rc"`：屏幕上不留字符，
模拟器只回调一次 `onTitleChanged`，退出码夹在里面。协议、备选方案、为什么必须
**整条匹配**都写在 `app/src/main/java/com/rxinfiles/tool/ScriptDone.java` 的类注释里。

两端各钉一半，共同认定同一个格式串：

| 半边 | 由谁验 |
| --- | --- |
| 命令文本 → 真字节（printf 有没有把 `\033`/`\007` 解释对、退出码对不对、标记在不在末尾） | `verify-scriptpipe.py` 第 9 节，在 **mksh / dash / busybox ash** 上各跑 0 / 7 / 126 三种退出码 |
| 标题 → 退出码（整条匹配、哪些必须判成「不是标记」） | `DoneMarkCheck.java`，34 项 |

`probe-direct-exec-cmd.sh` 另外守住「标记之后外层 shell 仍然活着」这条底线
（同一条命令行里紧跟 `STILL_ALIVE rc=…`，带 `exit` 的话那行就消失）。

## App 读不到文件时，要**借执行身份真读一次**（3.11.4 打标 → 3.11.5 换成真读）

`preflight()` 在 `canRead()` 为假时跳过全部嗅探，于是 `binaryContent` 必然是 `false` ——
`prepare()` 就把它当成文本脚本，又回到 `sh <路径>`，**把 3.11.1 那条修正整个绕过去了**
（一屏 `unexpected ')'` 原样回来）。根子是**「没嗅到」和「嗅出来是文本」被当成了同一件事**。

3.11.4 的兜底判据选错了：它用 Java 的 `File(path).canExecute()`，而那个**判的是 App 自己**。
真机案例 `/data/1/驱动.sh`（root 建的 `shc` 产物）：root 下 `./驱动.sh` 正常，
App 里却只报 `unexpected ')'` —— 因为 App 判「没有执行位」，又走回了 `sh`。

3.11.5 改成**借执行身份把那 8KB 读出来**：

```
[ -r F ] && printf '##r1\n' || printf '##r0\n';
[ -x F ] && printf '##x1\n' || printf '##x0\n';
head -c 8192 F 2>/dev/null | od -An -v -tx1 2>/dev/null
```

| 要素 | 为什么 |
| --- | --- |
| 通道按身份分流 | `asRoot` → `ShellExecutor.exec`（持久 su），否则 `execLocal`。**探测身份必须与执行身份一致**，与解释器探测同一条道理 |
| 读到了走 `analyze()` | 与「App 自己读得到」**完全相同的判定**（`analyze` 就是为此从 `preflight` 里抽出来的），不再有两套结论 |
| `r`/`x` 位 | 只用来写提示文案，**不参与决策** —— 真正的判断仍放在 `buildCommand` 的 `[ -x ]` 里现做 |
| `parseProbe` 只认十六进制行 | `command not found`、SELinux 拒绝话术……一律丢弃。宁可不认，也不能把一句错误提示的字节当文件头 |
| 十六进制行必须**后于**标记行 | su 持久会话可能把命令本身回显出来，而命令里就写着 `##r1`。只靠「整行像十六进制」挡不住回显，所以再加一道顺序门槛 |
| 真读不到 → `deferToShell` | 第三种命令形态 `if [ -x F ]; then F; else sh F; fi`，连「用什么跑」都交给 shell 现判 |
| `sniffedViaShell` | 借身份读来的头部**不参与编码修复** —— App 连读都读不到，必然也写不进去，弹「修复并执行」只会换来「修复失败」 |

`verify-scriptpipe.py` 第 7 节锁住这十三条；命令形状本身由
`probe-sniff-cmd.sh`（读得到吗）与 `probe-defer-rc.sh`（退出码透出）在真 shell 上验证。

## 退出码 126 / 127 要有解释（3.11.4 起）

`126` = 文件在但内核/安全策略拒绝执行；`127` = 找不到命令或它的解释器。
在 Android 上这**几乎总是环境问题**（最常见：SELinux 不允许 App 的普通身份执行
`/data` 下 root 建的文件），而用户看到的只有内核甩出来的一句话。

命令尾部因此多一个 `case`，两个分支互斥、退出码仍原样透出。
**判据有意放在 shell 里**：能不能执行取决于**真正执行它的那个身份**，
Java 侧 `File.canExecute()` 判的是 App 自己的身份，两者在本项目里可以不一致。

`probe-direct-exec-cmd.sh` 用 `exit 126` / `exit 127` 两个夹具在真 shell 上验证
（包括「127 不许把 126 的文案也打出来」）。

## 加密 / 编译过的脚本：`sh x` 报错但 `./x` 正常（3.11.1 起）

### 1. 「两边表现不一致」本身就是判据

`sh 文件` 和 `./文件` 用的是**同一个解释器、同一份文本**，区别只是前者把 shebang 那行
当注释忽略掉。所以一份**文本**脚本里真多一个右括号时，两条路会报**同一个错**。
只有 `sh 文件` 报错 —— 说明文件根本不是文本，sh 拿到的是二进制或密文。

实测（`probe-enc-forms.sh`）最能对上用户症状的是 **ELF 二进制改名成 `.sh`**：
mksh / dash / busybox ash **三家都报 `unexpected ")"`，且都在第 1 行**。

同时被排除的形态：密文老老实实包在引号里的自解密 stub（三家都不报错）、
明文头 + 裸 base64 密文块（不报语法错，只是运行时一串 `not found`）。

### 2. 判断据分两档：魔数是铁证，NUL 只是「不是纯文本」（3.11.6 修正）

- **魔数**（头几个字节）：ELF / 压缩包 / 归档 / 图片 / PDF… 见 `ScriptFormat.kt` 的 `MAGICS`，
  **与 `diagnose-sh.sh` 里的 `case` 分支逐条一致**（由 `verify-scriptpipe.py` 跨文件比对）。
  命中 = 里面**没有一行**是给 shell 看的 → **只能直接执行**。
- **NUL 字节**：只说明**不是纯文本**，**不等于 `sh` 读不了** —— 见下一节。
  **不要用「可打印率」当判据** —— GBK 汉字的字节全都 ≥ 0x80，会把正常中文脚本判成二进制。
- UTF-16（`FF FE`/`FE FF`）不算二进制，交给预处理的转码分支。

### 2.5 「含 NUL」≠「`sh` 读不了」——3.11.1 合并这两件事时踩了坑

用户反馈：**「有些本来就可以直接 sh 执行的非纯文本脚本，sh 又执行不了了」**。
脚本里嵌一段二进制载荷（自解压脚本、把数据塞进引号 heredoc 的脚本，
载荷也可能干脆写在 `exit 0` 之后）时，前 8KB 必然出现 NUL ——
而这类文件的**首行就写着 `#!/bin/sh`**，`sh 文件` 跑得好好的。

`probe-nul-script.sh` 实测（三份夹具 × 三家 shell）：

| 夹具 | mksh | dash | busybox ash |
| --- | --- | --- | --- |
| 载荷在 `exit 0` 之后 | rc=0 | rc=0 | rc=0 |
| 载荷在引号 heredoc 里 | rc=0 | rc=0 | rc=0 |
| 没有 shebang 的同款 | rc=0 | rc=0 | rc=0 |
| ELF / gzip 改名 `.sh`（对照） | 失败 | 失败 | 失败 |

顺带量出一个必须记住的事实：**三家 shell 都会悄悄丢掉载荷里的 NUL 字节**
（355 B → 353 B）。「能跑」是真的，「载荷字节百分百保真」不是。

分流因此是三档（`prepare()`）：

| 形态 | 判据 | 怎么跑 |
| --- | --- | --- |
| 魔数命中 | `magicKind != null` | 直接执行（内核装载） |
| 含 NUL + **有「读者声明」** | 首行 `#!` 或 `.sh`/`.py` 等扩展名 | **照旧交给 `sh`** |
| 含 NUL + 什么都没声明 | `ambiguousPayload` | 不拍板：`if [ -x F ]; then F; else sh F; fi`，并明说「判不出」 |

### 3. 踩过的工具差异

- **`od` 必须带 `-v`**：不带时 GNU/busybox/toybox **三个实现都**会把连续重复行压成 `*`,
  NUL 计数因此少算（实测 5567 vs 5543）。诊断脚本里的 NUL 判据固定用 `od -An -tx1 -v`。
- `od` 的调用形态：多合一二进制是 `busybox od …`，独立 `od` 不能多写那一个词 ——
  写成 `/usr/bin/od od …` 时 GNU od 会把 `od` 当文件名，而**在所有输入都不可读时会转去读 stdin**，
  探针就永远挂在那里（踩过，所以探针里加了 `timeout`）。

## 括号类报错的两条实测结论（`diagnose-sh.sh` 依据）

### 1. 报错措辞能反推是哪个 shell —— 路径会骗人，措辞不会

同一条「多了一个右括号」的错误，四个 shell 的写法完全不同（实测，非查文档）：

| shell | 措辞 |
| --- | --- |
| dash | `1.sh: 5: Syntax error: ")" unexpected` |
| busybox ash | `1.sh: line 5: syntax error: unexpected ")"` |
| mksh | `E: 1.sh[5]: syntax error: unexpected ')'` |
| bash | `1.sh: line 5: syntax error near unexpected token `)'` |

**只有 mksh 用单引号。** 所以看到 `unexpected ')'` 就能确定是 mksh（Android 的 `/system/bin/sh`）。

### 2. `unexpected '('` 和 `unexpected ')'` 是两类问题

- **`unexpected '('`** —— 可能是 bash 专属语法，也可能只是真的多了一个左括号。
  实测（`-n` 语法检查）：`arr=(1 2)` → mksh 不报错、ash 报 `unexpected "("`、dash 报 `"(" unexpected`；
  `for i in (1 2)` 与进程替换 `<( )` 同理。

- **`unexpected ')'`** —— **与 bash 语法无关**。mksh 支持数组、`[[ ]]`、`${v//a/b}`、`${v^^}`、
  `${v:0:3}`、`${!v}`、`(( ))`、`local -n`，**一个都不报这个错**；它只会因为
  *源码里真的多了一个右括号*（配平已到 0 却又见到 `)`）而报。
  mksh 语法上不认的只有**进程替换 `<( )`**（报 `unexpected '('`）。

  所以 `')'` 请直接按括号配平找，不要去翻 bash 语法表。

  **但有个前提：文件得真是文本脚本。** 如果 `./文件` 能跑而 `sh 文件` 报这一句，
  那它不是文本（见上面「加密 / 编译过的脚本」一节）—— 二进制字节凑出 `)` 这个 token
  是家常便饭，这时候去源码里找右括号纯属白费功夫。`diagnose-sh.sh` 的第 2 节会先把这一步判掉。

### 3. CRLF 的报错形态不是 `')'`

实测 CRLF 换行的表现是 `unexpected ''`（`\r` 被当成了一个词）、`unmatched 'if'`、
`unexpected 'in'`、`unexpected end of file` —— **不会**长成 `unexpected ')'`。
看到 `')'` 可以先把 CRLF 这条排除。

### 4. 未闭合的引号最容易被误判

一个没配对的 `"` 或 `'` 会把后面所有内容吞成一个词，括号计数与命令边界全部错位，
**报出来的行号往往离真正的问题很远**（实测：`sh -n` 报的是文件末尾，问题在第 2 行）。
`diagnose-sh.sh` 的第 5 节会显式指出「单/双引号没闭合」。
