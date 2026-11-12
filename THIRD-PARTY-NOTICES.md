# 第三方组件与许可（THIRD-PARTY NOTICES）

本仓库**原创代码**按 [Apache License 2.0](LICENSE) 发布。仓库内还包含以下第三方组件，
它们各自遵循自己的许可；再分发时请同时遵守这些许可。

---

## 1. Termux 终端模拟层（GPL-3.0）

| 项 | 内容 |
| --- | --- |
| 文件 | `app/src/main/java/com/termux/terminal/*.java`、`app/src/main/java/com/termux/view/*.java`、`app/src/main/jni/termux.c`、构建产物 `libtermux.so` |
| 来源 | [Termux](https://github.com/termux/termux-app)（终端模拟层，最初衍生自 Jack Palevich 的 Android Terminal Emulator） |
| 许可 | **GNU General Public License v3.0** |

> 说明：这些文件用于应用内的 PTY 终端与按键/渲染处理，属于 GPL-3.0 代码。若你分发
> 包含这些文件的**二进制或整体工程**，需要同时满足 GPL-3.0 的义务（提供对应源码、
> 保留版权声明、整体按 GPL-3.0 分发等）。

## 2. BusyBox 1.36.1（GPL-2.0）

| 项 | 内容 |
| --- | --- |
| 二进制 | `app/src/main/jniLibs/arm64-v8a/libbusybox.so`（以 `lib*.so` 名字随包分发的 busybox 多调用二进制） |
| 对应源码 | `tools/rxinbox/src/busybox-1.36.1.tar.bz2` |
| 构建脚本 | `tools/rxinbox/build-busybox.py`、`get-linux-ndk.py`（用 Linux 侧 NDK 交叉编译，Bionic 链接） |
| 许可 | **GNU General Public License v2.0** |

> 说明：仓库内已附带 busybox 源码压缩包，以满足 GPL-2.0 的源码提供义务；如果你替换
> 二进制，请同步更新对应源码与构建脚本。

## 3. 归档解析 jar（`app/libs/`）

| jar | 版本 | 用途 | 许可 | 来源 |
| --- | --- | --- | --- | --- |
| `junrar-8.1.1.jar` | 8.1.1 | 读 RAR 1.5 ~ 5.0（含 RAR5 容器、AES 加密） | UnRar License | <https://repo1.maven.org/maven2/com/github/junrar/junrar/8.1.1/> |
| `commons-compress-1.21.jar` | 1.21 | 7z 容器（含 AES 解密）、bzip2 / xz / lzma 流 | Apache-2.0 | <https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.21/> |
| `xz-1.10.jar` | 1.10 | 7z 里 LZMA / LZMA2 的解码器 | Public Domain | <https://repo1.maven.org/maven2/org/tukaani/xz/1.10/> |
| `slf4j-api-2.0.20.jar` | 2.0.20 | junrar 的日志门面 | MIT | <https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.20/> |
| `slf4j-nop-2.0.20.jar` | 2.0.20 | 空实现，避免「No SLF4J providers」噪音 | MIT | <https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.20/> |

> junrar 的 UnRar License 允许在应用中随附使用与再分发，但**禁止用其实现 RAR 压缩器**；
> 本工程只做解压/列举，不生成 RAR。

## 4. Gradle 依赖（Maven 坐标，未直接复制源码）

| 依赖 | 许可 |
| --- | --- |
| `androidx.core:core-ktx`、`androidx.appcompat:appcompat`、`androidx.constraintlayout`、`androidx.recyclerview`、`androidx.activity`、`androidx.lifecycle` | Apache-2.0 |
| `com.google.android.material:material` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Apache-2.0 |
| `dev.rikka.shizuku:api` / `provider` | Apache-2.0 |

## 5. 关于「整仓再分发」

本仓库原创代码为 Apache-2.0，但仓库内**同时包含 GPL-3.0（Termux 终端层）与 GPL-2.0
（BusyBox）组件**。如果你把整个工程（源码或带这些组件的 APK）再分发，需满足 GPL 的
相应义务；最稳妥的发布方式是**把仓库整体按 GPL-3.0 发布**，或在使用/再分发前咨询
法律意见。若你只使用 Apache-2.0 原创代码（不含 `com.termux.*`、`termux.c`、
`libbusybox.so`），则不受上述 GPL 条款约束。
