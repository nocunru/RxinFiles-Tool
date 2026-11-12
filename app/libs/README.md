# app/libs —— 归档格式支持用的第三方 jar

这个工程要求**完全离线构建**（`gradle --offline`），所以这些依赖以平铺 jar 的形式
放在这里，`app/build.gradle.kts` 用 `implementation(fileTree("libs"))` 收进去。
不要改成远程坐标 —— 那样构建就依赖网络了。

| jar | 版本 | 用途 | 许可证 | 来源 |
| --- | --- | --- | --- | --- |
| `junrar-8.1.1.jar` | 8.1.1 | 读 RAR 1.5 ~ 5.0（含 RAR5 容器、AES 加密） | UnRar License | <https://repo1.maven.org/maven2/com/github/junrar/junrar/8.1.1/> |
| `commons-compress-1.21.jar` | 1.21 | 7z 容器（含 AES 解密）、bzip2 / xz / lzma 流 | Apache-2.0 | 本机 Gradle 缓存（`org.apache.commons:commons-compress:1.21`） |
| `xz-1.10.jar` | 1.10 | 7z 里 LZMA / LZMA2 的解码器 | Public Domain | <https://repo1.maven.org/maven2/org/tukaani/xz/1.10/> |
| `slf4j-api-2.0.20.jar` | 2.0.20 | junrar 的日志门面（它自己的运行期依赖） | MIT | <https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.20/> |
| `slf4j-nop-2.0.20.jar` | 2.0.20 | 空实现，避免「No SLF4J providers」的噪音 | MIT | <https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.20/> |

## 为什么是这几个

- **RAR 没有别的选择**：RAR 的压缩格式没有公开规范，纯 Java 实现只有 junrar 一家；
  native 的 unrar 要把「解压器」当命令分发，那套机制（见 `RxinToolbox`）是为 busybox
  准备的，为一种压缩格式再走一遍不划算。junrar 8.x 同时支持 RAR 4.x 与 5.0 容器。
- **7z 必须带上 xz**：7z 的容器格式在 commons-compress 里，但标准 .7z 用的
  LZMA/LZMA2 解码器在 `org.tukaani:xz` —— **没有它，常见 .7z 一个都解不开**。
  所以它不是一个可选的加速件。
- **不用 commons-compress 1.2x 的新版本**：1.21 与 Android 侧的字节码/API 兼容性已经
  在这台机器上验证过（全 Java 8 字节码，依赖全是 optional）。升级时要重新验证
  `SevenZFile` 的构造签名与 `PasswordRequiredException` 行为。

## 字节码与 Android 兼容性

`junrar-8.1.1` 全部是 Java 8 字节码（class 版本 52），`commons-compress-1.21` 也是，
`xz-1.10` 同为 Java 8。minSdk 28 直接吃，不需要 desugar。

## 校验

`tools/archive-check/run-checks.sh` 会把 `app/libs/*.jar` 当 classpath，
对真实夹具（`.rar` RAR4/RAR5/solid/加密、`.7z` 五种压缩方法/solid/加密、
`.tar.bz2`、`.tar.xz`、`.bz2`/`.xz`/`.lzma`、zip-slip 恶意样本）跑
「列举 / 解压 / 单条目提取 / 路径逃逸 / 加密 / 符号链接 / 坏输入」的断言 ——
**215 项，四个程序**。

```sh
tools/archive-check/run-checks.sh
```

夹具和「3.12.0 在这里抓到的四个真 bug」都记在 `tools/archive-check/README.md`。

## 升级这三个 jar 时要注意

- 换 junrar：确认新版本仍然覆盖 RAR5（`Archive$Rar5HeaderCrypt` 之类的类还在），
  并且 `Archive(File)` / `Archive(File, ArchiveOptions)` 这两个构造签名没变。
- 换 commons-compress：确认 `SevenZFile(File, char[])` 与
  `SevenZArchiveEntry.getContentMethods()` 还在 —— 后者是判断「只加密内容」
  的唯一入口（见 `SevenZArchive.needsPassword()`）。**升级后必须重跑 run-checks.sh。**
- 换 xz：**别删**。没有它标准 `.7z` 全解不开，而构建是离线的，删了当场挂。
