# RxinFiles Tool · 开源版源码

> Android 上的一款「ROOT / Shizuku 感知」的文件管理器与轻量编辑器。
>
> 版本 **4.02.00**（versionCode 40200） · minSdk 28（Android 8.0） · 仅 arm64-v8a

---

## 目录

- [项目简介](#项目简介)
- [功能一览](#功能一览)
- [开源版说明（不含局域网 HTTP 文件服务）](#开源版说明不含局域网-http-文件服务)
- [构建](#构建)
- [项目结构](#项目结构)
- [核心设计（源码导读）](#核心设计源码导读)
- [自检套件](#自检套件)
- [权限与安全](#权限与安全)
- [第三方组件与许可](#第三方组件与许可)
- [许可证](#许可证)
- [已知限制](#已知限制)

---

## 项目简介

RxinFiles Tool 是一个**单 Activity 为主、无第三方 UI 框架**的 Android 文件管理器。它面向
两类用户：普通用户（完全走 Java/Scoped Storage），以及需要读取 `/data` 等受保护目录的
ROOT / Shizuku 用户。

与常见文件管理器相比，本项目的取舍是：

- **权限按「能力」分级而不是按「开关」**：启动时自动探测 ROOT（`su`）→ ADB（Shizuku）
  → 普通，三级降级；用户看到的永远只是一个探测结果，不需要自己判断该选哪个。
- **所有危险操作都可离线断言**：文件操作、文本切片、归档解析、脚本解析、HTTP（本仓库
  不含）都拆成了不依赖 Android API 的纯 Java 层，配合 `tools/` 下的离线套件验证。
- **UI 不是重点，数据安全是**：覆盖同名文件永远「先备份再替换」，写文件永远
  「同目录暂存 → fsync → 原子 rename」，取消/崩溃不会留下半截文件。

## 功能一览

| 功能 | 说明 |
| --- | --- |
| 文件浏览 | 列表/网格自适应布局；排序维度（名称/大小/时间/类型）× 升降序 × 目录优先；书签；路径直达；返回上级 |
| 文件操作 | 复制、移动、重命名、删除、新建文件/文件夹；同名冲突三种策略（覆盖/自动重命名/跳过）；所有长任务可取消、有进度 |
| ROOT / Shizuku | 自动探测；ROOT-only 目录的读取、复制、移动、删除、重命名都走同一身份校验；Shizuku 提供 adb 级权限 |
| 文本查看/编辑 | 按字符切片（默认 10 万字符/片）展示大文件；编码自动探测与手动切换；按行跳转、全文查找；分片编辑后原样拼回，未修改的片按字节复制 |
| 图片查看 | 左右翻页、缩放/旋转/适应；GIF/动态 WebP 保留动画；按位图真实字节数限流，超出系统绘制上限时拒绝显示而不是闪退 |
| 视频播放 | 系统 `VideoView` 播放；ROOT-only 视频先暂存到私有缓存再播放 |
| 压缩包 | ZIP / 7z / RAR（RAR 1.5~5.0）/ tar / tar.gz / tar.bz2 / tar.xz / gz / bz2 / xz / lzma 的**列举与解压**；创建 ZIP / 7z；加密压缩包支持密码；路径逃逸、zip-slip、符号链接环等恶意样本有离线断言 |
| 终端 | 内置 PTY 终端（基于 Termux 终端模拟层）；固定以应用自身身份运行，可执行脚本、查看命令输出 |
| 脚本 | 脚本识别、编码预检与修复、参数输入、前台/后台（管道）运行；内置 busybox 补齐系统命令（见下） |
| 归档/临时文件 | 所有 ROOT 暂存与归档预览统一放在 `cacheDir/rxin_stage/<pid>_<nano>_<label>/`，由创建者释放，冷启动兜底清理 |
| 外部打开 | 出现在所有文件的「用其他应用打开」列表；只定位、不执行、不信任对方提供的任何额外字段 |
| 主题 | 跟随系统/浅色/深色；界面为玻璃拟态风格（纯 XML drawable，无第三方 UI 库） |

## 开源版说明（不含局域网 HTTP 文件服务）

作者发布的**开源版**不包含「局域网 HTTP 文件服务」模块。从本仓库移除的内容：

| 位置 | 内容 |
| --- | --- |
| `app/src/main/java/com/rxinfiles/tool/http/` | HTTP API 与配置、前台服务、网页终端管理、设置页（5 个文件） |
| `app/src/main/java/com/rxinfiles/tool/web/` | HTTP 服务器、请求/响应、鉴权、Range、路径策略、票据（8 个文件） |
| `app/src/main/assets/http/` | 网页端 `index.html` / `app.js` / `terminal.js` / `style.css` |
| `app/src/main/res/layout/activity_http_settings.xml` | HTTP 设置页布局 |
| `AndroidManifest.xml` | `HttpSettingsActivity`、`:http` 进程的 `HttpFileService`，以及 `INTERNET` / `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE*` 权限 |
| `MainActivity` | 顶栏「文件服务」快捷按钮、更多菜单里的「局域网文件服务」入口 |
| `magisk/`、`tools/http-check/`、`tools/magisk-check/`、`tools/ui-check/` | HTTP 模块的 Magisk 开机启动配套与对应校验套件 |

因此本仓库构建出的 App：

- **不申请网络权限**，不会监听任何端口；
- 主界面少一个「文件服务」入口，其余功能与完整版完全一致；
- `tools/bugfix-check` 中依赖 `HttpApi.kt` 的 2 条断言会显式跳过（输出 `skip`，不计入失败）。

## 构建

### 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 17（AGP 8.2 要求） |
| Android Gradle Plugin | 8.2.0 |
| Gradle | 8.4（仓库自带 wrapper） |
| compileSdk / targetSdk | 34 |
| minSdk | 28 |
| NDK | 26.1.10909125（构建 `libtermux.so` 需要） |

### 命令行构建

```bash
# 1) 指定 Android SDK：二选一
#    a. 在工程根目录建 local.properties（不要提交到 git）：
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
#    b. 或设置环境变量 ANDROID_HOME / ANDROID_SDK_ROOT

# 2) 构建
./gradlew assembleDebug        # 调试包：app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # 发布包（未签名，见下）
```

> **Windows 注意**：AGP 与 AIDL 工具链对**非 ASCII 路径**支持不佳，请把仓库放在纯英文
> 路径下构建（例如 `C:\code\RxinFiles-Tool`）。从 GitHub clone 下来通常是英文路径，直接
> 构建即可；如果把工程放在中文目录下，AGP 会明确报错并提示路径问题，按提示移到英文路径
> 即可，这不是代码问题。
>
> 另外，`tools/archive-check/` 里保留了「超长文件名」测试夹具，Windows 上 clone 前建议
> 先执行 `git config --global core.longpaths true`，否则会因路径过长而检出失败。

### Release 签名

仓库**不包含**签名文件。`app/build.gradle.kts` 会在根目录存在 `keystore.properties` 时启用
release 签名，否则产出**未签名**的 release 包：

```properties
# keystore.properties（不要提交到 git）
storeFile=your.jks
storePassword=******
keyAlias=******
keyPassword=******
```

### 离线构建

工程设计为可完全离线构建：`app/libs/` 下平铺了归档功能所需的第三方 jar（见
[`app/libs/README.md`](app/libs/README.md)），Gradle 依赖在首次解析后可加 `--offline`。

## 项目结构

```
.
├── app/
│   ├── build.gradle.kts            # 应用模块配置（版本号、签名、NDK、打包）
│   ├── proguard-rules.pro
│   ├── libs/                       # 归档解析所需第三方 jar（附许可说明）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── aidl/                   # Shizuku UserService 的 AIDL 接口
│       ├── assets/                 # （开源版为空；完整版此处是网页端资源）
│       ├── java/com/rxinfiles/tool/    # 应用主体（见下方包结构）
│       ├── java/com/termux/            # 终端模拟层（第三方，见许可）
│       ├── jni/                    # termux.c → libtermux.so（NDK 构建）
│       ├── jniLibs/arm64-v8a/      # 预编译 libbusybox.so（内置命令工具箱）
│       └── res/                    # 布局、drawable、颜色、主题
├── gradle/wrapper/                 # Gradle 8.4 wrapper
├── tools/                          # 离线校验套件（见「自检套件」）
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── LICENSE                         # Apache-2.0
└── THIRD-PARTY-NOTICES.md          # 第三方组件与许可
```

`app/src/main/java/com/rxinfiles/tool/` 主要文件（按职责分组）：

| 分组 | 文件 | 说明 |
| --- | --- | --- |
| 入口与全局 | `RxinApp`、`MainActivity`、`RxinEnv`、`RxinSandbox`、`ThemeStore`、`Glass`、`SheetDialog` | Application/主界面/环境与沙箱/主题/玻璃 UI 基建 |
| Shell | `ShellExecutor`、`ShizukuBridge`、`ShizukuShellService`、`TerminalActivity`、`TerminalSession`（在 `com.termux` 下） | 三级 shell、持久 `su`、Shizuku 用户服务、PTY 终端 |
| 文件操作 | `FileOps` + `RootShell`、`FileRepository`、`FileSafety`、`FileSort`、`FileItem`、`FileListAdapter`、`FileClipboard` | 统一身份判定、复制/移动/删除/重命名、暂存生命周期、目录读取与排序 |
| 文本 | `TextFileRepository`、`TextSlicer`、`TextCodec`、`TextViewerActivity`、`HighlightTextView` | 分片读取、编码探测、原子写回、编辑与查找 |
| 图片/视频 | `ImageViewerActivity`、`ZoomImageView`、`ImageSampling`、`DrawableSize`、`VideoViewerActivity` | 图片解码限流、缩放/翻页、视频播放 |
| 归档 | `Archives`、`ZipArchive`、`RarArchive`、`SevenZArchive`、`TarArchive`、`ArchiveRepository`、`ArchiveSheet`、`TreeRails` | 纯 Java 归档层 + 归档面板 |
| 脚本 | `ScriptRunner`、`ScriptPipe`、`ScriptFormat`、`ScriptLogDialog`、`ScriptDone`、`RxinToolbox` | 脚本识别/编码修复/执行/日志；内置 busybox 工具箱 |
| 书签/数据 | `BookmarkStore`、`IncomingActivity`、`IncomingFile`、`Intents` | 书签持久化（跨进程文件锁事务）、外部打开入口 |
| 其它 | `SheetDialog`、`OperationProgressDialog`、`TreeRailView`、`DisclaimerActivity` | 面板、进度、免责声明 |

## 核心设计（源码导读）

1. **三级 shell（`ShellExecutor`）**
   - `ROOT`：一个持久 `su` 进程 + marker 定界，避免每条命令 fork 一次；
   - `ADB`：Shizuku 用户服务（uid 2000）；
   - `NORMAL`：无 shell，调用方降级 Java API。
   - 需要退出码的命令走 `execWithStatus()`：命令进**子 shell**执行，退出码由**外层**
     shell 打印——命令内部的 `exit` 不会带走持久 `su`，也不会丢结果标记。
2. **统一的「身份」文件判定（`FileOps.entryExists` / `isDirectory` / `ensureDirectory`）**
   - `File.exists()` 以应用自身 uid `stat`：ROOT-only 路径与悬空符号链接都会得到 `false`。
   - 所以「目标是否已被占用」必须按**真正去操作它的身份**判定：Java 看得见就用 Java，
     看不见且 ROOT 可用再 `test -e` / `test -L` / `test -d` 复核。复制冲突策略、重命名、
     删除成功判定全部走这一层。
3. **写文件 = 暂存 + fsync + 原子 rename**（`TextFileRepository.commitDirect/commitWithRoot`）
   - 覆盖前先同目录备份，失败可回滚；ROOT 路径同样在目标目录内暂存后 `mv` 提交。
4. **暂存目录的所有者生命周期（`FileOps.newStageDir` / `sweepStaleStage`）**
   - 每个操作一个 `<pid>_<nano>_<label>` 独占目录，谁创建谁释放；
   - 冷启动只清理「确认无进程使用」的遗留条目，绝不清空共享根目录。
5. **纯 Java 核心 + 离线断言**
   - `Archives`/`TextSlicer`/`IncomingFile`/`FileSafety`/`FileSort`/`ImageSampling` 等类
     不依赖 Android API，因此可以在 PC 上用 `javac` 直接编译并跑断言（见 `tools/`）。

## 自检套件

`tools/` 下是工程的离线校验套件，全部为「纯源码/纯 Java」断言，不需要真机：

| 套件 | 内容 | 运行方式 |
| --- | --- | --- |
| `archive-check` | 归档列举/解压/加密/恶意样本 215 项 | `sh tools/archive-check/run-checks.sh` |
| `textslice-check` | 文本切片/合并 147 项 + 源码不变量 97 项 | `sh tools/textslice-check/run.sh`；`python3 tools/textslice-check/verify-textslice.py` |
| `incoming-check` | 外部打开入口 323 项 + 源码不变量 268 项 | `sh tools/incoming-check/run.sh`；`python3 tools/incoming-check/verify-incoming.py` |
| `fileops-check` | 文件安全判定 10 项 | `sh tools/fileops-check/run.sh` |
| `script-check` | 脚本退出码/管道 34 项 | `sh tools/script-check/run-java-check.sh` |
| `find-check` / `image-check` / `sort-check` | 搜索/采样/排序 | 各自目录下 `run.sh` |
| `bugfix-check` | 一轮源码审计的 79 条回归断言（HTTP 相关 2 条在开源版跳过） | `python3 tools/bugfix-check/verify-bugfix.py` |
| `code-check` | 工程级静态检查（悬空资源/共享格式化器/孤儿断言） | `python3 tools/code-check/check-code.py` |
| `perf-check` | 文本切片/解码基准 | `sh tools/perf-check/run.sh` |
| `rxinbox` | 内置 busybox 的构建与校验脚本（需要 Linux/NDK） | 见 `tools/rxinbox/README.md` |

> 上述套件在 Push 前建议全部跑一遍；它们都只读源码/纯 Java 逻辑，不会改工程。

## 权限与安全

- `READ/WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`：文件管理所需（Android 11+
  需要用户在系统设置里授予「所有文件访问权限」）。
- Shizuku：通过 `ShizukuProvider`（`INTERACT_ACROSS_USERS_FULL` 权限声明）提供 adb 级权限，
  需要用户手动安装并授权 Shizuku。
- **不申请网络权限**：开源版没有任何联网代码；完整版的 HTTP 局域网文件服务已被移除。
- ROOT 操作通过 `su` 执行；应用不会自行提权，也不会静默执行任何脚本——脚本运行前一定有
  确认面板。
- 外部打开入口（`IncomingActivity`）只做「定位文件」，不执行脚本、不信任对方提供的任何
  extra 字段。

## 第三方组件与许可

本仓库的**原创代码**采用 Apache-2.0（见 [`LICENSE`](LICENSE)）。仓库内还包含下列第三方
组件（详细许可见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)）：

| 组件 | 位置 | 许可 |
| --- | --- | --- |
| Termux 终端模拟层（`com.termux.*`、`termux.c`、`libtermux.so`） | `app/src/main/java/com/termux/`、`app/src/main/jni/` | GPL-3.0（源自 Termux 项目） |
| BusyBox 1.36.1（`libbusybox.so` + 源码压缩包） | `app/src/main/jniLibs/`、`tools/rxinbox/src/` | GPL-2.0 |
| junrar | `app/libs/junrar-8.1.1.jar` | UnRar License |
| Apache Commons Compress | `app/libs/commons-compress-1.21.jar` | Apache-2.0 |
| XZ for Java | `app/libs/xz-1.10.jar` | Public Domain |
| SLF4J | `app/libs/slf4j-*.jar` | MIT |
| AndroidX / Material / Shizuku | Gradle 依赖 | Apache-2.0 |

> ⚠️ **许可提醒**：仓库整体包含 GPL-3.0（Termux 终端层）与 GPL-2.0（BusyBox）组件。
> 如果你要把**整个仓库**再分发（而不只是其中的 Apache-2.0 原创代码），需要同时满足 GPL
> 的相应义务；最稳妥的做法是把仓库整体按 GPL-3.0 发布，或在使用前咨询法律意见。详见
> `THIRD-PARTY-NOTICES.md`。

## 许可证

本仓库原创代码：**Apache License 2.0**（见 [`LICENSE`](LICENSE)）。
第三方组件：遵循各自许可（见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)）。

## 已知限制

- 仅构建 `arm64-v8a`（内置 busybox 与该 ABI 绑定）。
- 无真机/模拟器自动化测试；ROOT 与 Shizuku 行为通过源码断言 + shell 语义验证。
- 完整版与开源版功能差异：仅缺少「局域网 HTTP 文件服务」及其配套 Magisk 模块。
- `tools/` 里的断言依赖 JDK 17（或更新的 JDK，如 JDK 20）；纯 Java 套件不需要 Android SDK。
