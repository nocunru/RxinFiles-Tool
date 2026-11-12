# tools/archive-check —— 归档层回归校验

对 `Archives` / `TarArchive` / `RarArchive` / `SevenZArchive` / `ZipArchive` /
`TreeRails` 跑真实归档夹具，断言「列举 / 解压 / 单条目提取 / 路径逃逸 / 加密 /
符号链接 / 坏输入 / **列表顺序与树形骨架**」这些行为。

## 为什么能脱离 Android 测

这几个文件**一行 Android API 都没有**（只用 `java.io` + `app/libs` 里的三方 jar），
所以 `javac` + `java` 直接跑，不需要模拟器、不需要 Gradle，改完立刻能验。
UI（`ArchiveSheet` / `ArchiveRepository`）不在这一层，仍然只能真机确认 ——
**所以凡是能纯逻辑化的 UI 逻辑，都往下挪成纯 Java**：
`TreeRails`（面板画层级线的骨架算法）就是这么来的，面板与断言跑的是同一份代码。

## 跑法

```sh
tools/archive-check/run-checks.sh
```

夹具已经在仓库里，正常情况下**不需要**重新生成。

## 夹具

| 夹具 | 造它的工具 | 覆盖什么 |
| --- | --- | --- |
| `plain.zip` `gbk.zip` `evil.zip` | `genzip.py` | zip 基线与 zip-slip |
| `test.tar` `test.tar.gz` `test_gnu.tar` `test_pax.tar` | `gen.py`（**pax** 格式） | tar 基线、长名字、链结 |
| `test-longname.tar` `test-pax-long.tar` | GNU tar `--format=gnu` / `pax` | **GNU `L` 与 pax `x` 两种长名扩展头** |
| `rar4.rar` `rar5.rar` `rar4-solid.rar` `rar5-solid.rar` | rar 6.24 | RAR4 / RAR5 / solid |
| `rar5-enc.rar` `rar4-enc.rar` `rar5-enchdr.rar` | rar 6.24 `-p` / `-hp` | 内容加密 / 头部加密 |
| `rar5-link.rar` | rar 6.24 `-ol` | 符号链接条目 |
| `evil.rar` | rar 6.24 `-ap../` | **真的写着 `../` 的条目名** |
| `sevenz-{lzma2,lzma,bzip2,deflate,copy}.7z` | 7-Zip `-m0=…` | 五种压缩方法 |
| `sevenz-{solid,nonsolid}.7z` | 7-Zip `-ms=on/off` | solid 与非 solid |
| `sevenz-enc.7z` `sevenz-enchdr.7z` | 7-Zip `-p` / `-p -mhe=on` | 内容加密 / 头部加密 |
| `sevenz-link.7z` | 7-Zip `-snl` | 符号链接条目 |
| `evil.7z` | 7-Zip `-si../name` | 路径逃逸条目 |
| `zipcrypto.zip` `zipcrypto-store.zip` | 7-Zip `-tzip -p` | **传统 PKWARE 加密**，Deflate / Store 各一份 |
| `zipaes{128,192,256}.zip` `zipaes256-store.zip` | 7-Zip `-mem=AESxxx -p` | **WinZip AES**（method 99 + extra `0x9901`） |
| `zipmixed.zip`（配 `zmixed/`） | 7-Zip 分两次 `a` 到同一个包 | **明文与加密条目混装** |
| `zippw-gbk.zip` `zippw-utf8.zip` | **`genzipenc.py`（自己写的 ZipCrypto 加密器）** | 中文密码的 **GBK / UTF-8 编码差异** |
| `zenc/`（源树） | `genzenc.py` | 加密 zip 的参照内容，**含 240KB 高可压文本** |
| `test.tar.bz2` `test.tar.xz` | GNU tar `-j` / `-J` | 新格式的 tar 包装 |
| `single.txt.{gz,bz2,xz,lzma}` | `gzip` / `bzip2` / `xz` | 单文件压缩流 |

### 加密 ZIP 为什么需要两套独立的生成方式

- **`zenc` 而不是 `zsrc`**：7-Zip 写 zip 时会自己挑压缩方法，对「小文件」和
  「压不动的内容」一律落 **Store** —— 实测连 `-mm=Deflate -mx=9` 都被忽略
  （`zsrc` 里的条目全部被写成 Store）。所以加密夹具的源树里必须有个
  240 KB 的高可压文本，deflate 才会真的生效。
- **`genzipenc.py`**：命令行工具把密码按 **UTF-8** 送进算法，而 Windows 上的
  WinZip / WinRAR / 7-Zip 中文版按**本机代码页（GBK）**送。要用夹具把
  `ZipArchive.passwordBytes()` 的三路回退钉死，就必须能指定「密码用哪种字节编码」——
  这只有自己写加密器才行。它同时是**第二套参考实现**：夹具由它产出，
  Python 的 `zipfile` 读得回去、我们的 Java 也读得出来，三方交叉确认。
- **`7zz a` 是「追加或更新」，不是重建**：目标包已经存在时它**不会**清空，
  源树换了名字（`zsrc` → `zenc`）再跑一次，旧条目会原样留下，
  夹具就变成了「两棵树混装」而没人发现。`mkfixtures.sh` 因此对每个产物先 `rm -f`。

重新生成（**只在需要改夹具时**）：

```sh
MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash /mnt/c/<...>/tools/archive-check/mkfixtures.sh
```

它会先把两个命令行工具下到 `~/.cache/archeck-tools`（有缓存就跳过）：

- 7-Zip 25.01 —— <https://www.7-zip.org/a/7z2501-linux-x64.tar.xz>
- rar 6.24 —— <https://www.rarlab.com/rar/rarlinux-x64-624.tar.gz>

> **RAR 为什么用 6.24 而不是最新的 7.x**：RAR 7.0 起删掉了 `-ma4`，
> 只能用 `-ma5` 造 RAR5 包（实测 7.10 直接报 `ERROR: Unknown option: ma4`）。
> 而 RAR4 恰恰是旧手机 / 旧 Windows 上最常见的形态，必须覆盖。
>
> **7z / RAR 为什么不能让 Java 自己造**：两者都只有「能解不能写」的纯 Java 实现
> （junrar 只读；`SevenZOutputFile` 造不出 `-m0=lzma` / `-ms=on` 这些组合）。

## 断言程序

| 程序 | 项数 | 覆盖 |
| --- | --- | --- |
| `ZipCheck` | 8 + 若干旧断言 | zip / tar / tar.gz 基线、GBK 名、zip-slip、单条目提取、**GNU 长名** |
| `ZipEncCheck` | 91 | **ZipCrypto / WinZip AES 解密**、逐条目加密标记、密码错与缺的措辞、混装包、**密码字节编码回退**、截断包 |
| `RarCheck` | 61 | RAR4/5、solid、加密、逃逸、符号链接、坏输入 |
| `SevenZCheck` | 75 | 五种压缩方法、solid、加密、逃逸、符号链接、自有写出往返、坏输入 |
| `StreamCheck` | 71 | bz2 / xz / lzma / tar.bz2 / tar.xz、扩展名识别、坏输入 |
| `OrderCheck` | 81 | **列表顺序是路径序**、同目录条目连成一段、**树形骨架的精确形状**、断头与截断 |

期望值一律**从真实目录树读出来**（`Check.names()`），不手抄清单 ——
手抄的清单会在夹具变化时悄悄过期，断言照样绿。

`OrderCheck` 有点特别：它验的不是「内容对不对」而是「**列表排布**」。
它把 `TreeRails` 的输出和手算的 `tree` 形状一条条比（`骨架 · zsrc/dirA/sub → [true, false]`），
所以改了排序或骨架算法，这里会立刻变红。

### 两个必须记住的口径差异

- **tar 的目录条目名自带尾斜杠**（`src/目录B/`），zip / rar / 7z 不带。
  `Check.listingNames()` 会归一化；不归一化会把真问题淹掉。
  **写断言时自己查条目也要归一化** —— `ZipEncCheck.flagOf()` 在这里假红过一次。
- **`getLastModifiedDate()` 在没时间戳时抛异常**（不是返回 null），
  写断言时要用 `getHasLastModifiedDate()` 先问。

## 3.12.3 —— 「同一个文件夹里的东西看不出归属」

用户反馈：**「你这一打开，同一级的都堆一块，你都不知道这个文件是在哪个文件夹里的」**。

第一反应是去改样式（缩进、图标、标签），但**根因在 `Archives.sort()`**：
它按「所有目录排前面、所有文件排后面」分组，把树整个拆散了。实测一个 8 条目的 7z：

```
排序后： zsrc/  zsrc/dirA/  zsrc/dirA/sub/  zsrc/空目录/  zsrc/dirA/a.sh  zsrc/dirA/sub/deep.bin …
                 ↑ 目录全在前                                  ↑ 文件全在后

缩进一画，zsrc/dirA/a.sh 正好落在「zsrc/空目录/」底下 ——
用户看到的是「a.sh 在空目录里」，而它其实在 dirA 里。
```

修法一句话：**目录名补上尾斜杠之后，普通字符串比较就是路径序**。
因为 `/`（0x2F）比数字、字母、绝大多数可打印字符都小，所以
`a/` < `a/b` < `a/b/` < `a/b/c` < `a/z` —— 父目录恰好落在自己子树的前一位。
四家容器统一走这条（RAR / 7z 写出来多半已经是路径序，但**不能指望**容器替我们守这个承诺）。

### 骨架算法里踩的两个坑（都被 `OrderCheck` 钉住了）

1. **「本行是不是同级最后一个」不能用深度判。**
   最初写的是「右边第一个不比它更深的行」（单调栈）—— 在「目录条目齐全」的包上完全正确，
   可碰上不写目录条目的 zip（`plain.zip` 只有 `dirA/a.sh`，没有 `dirA/`）就翻车：
   `a.sh` 被判成「dirA 下的最后一个」，画成 `└`，而它下面还跟着更深的
   `dirA/sub/deep.bin` —— 看着就像 a.sh 包含它。
   改成**按名字前缀判**：「后面还有没有同属本行父目录、又不是本行后代的条目」。
2. **树线第 k 列说的是「深度 k+1 的那个祖先还有没有兄弟」**，不是「深度 k」。
   差这一位，`tree` 那种 `├─ │ ├─` 的形状就整个错了 ——
   照着 `tree` 命令的真实输出对一遍才看清。

### 这一层能管到哪儿

层级是**画**出来的，所以「好不好看」只能真机看；但「画的对不对」全是纯逻辑，
一律压给 `TreeRails` + `OrderCheck`：

- 列表顺序（路径序、同目录条目连成一段）；
- 每行的骨架（`[true, false]` 这种数组，一条条和手算的 `tree` 形状比）；
- 边角：上级目录行缺失（断头）、层级超出上限（截断）、空表、根级只有一条目却带子树。

## 3.12.1 在这一层抓到的两个真 bug

3.12.0 为了解加密 ZIP 把读取从 `java.util.zip` 换成了 commons-compress，
**换库带出两个只有这一层才看得见的问题**：

1. **GBK 文件名的判据静默失效了。**
   原来 `hasMojibake()` 找的是「解出来的名字里有没有 `U+FFFD`」——
   那是 `java.util.zip` 的行为（严格解码器把坏字节换成替换字符）。
   commons-compress 1.21 对非法 UTF-8 走**宽松回退**：GBK 的
   `ce c4 bc fe c3 fb`（「文件名」）被解成 `?ļ???`，**一个 `U+FFFD` 都没有**，
   于是 GBK 包被当成 UTF-8 读，整个包的文件名全变乱码，而所有断言照样是绿的。
   现在改成只看 `getRawName()` 的**原始字节**、做**严格** UTF-8 解码。
   （`ZipCheck` 里那条 GBK 断言是唯一的哨兵，换库时它立刻变红，才没漏过去。）
2. **`ZipArchive` 的 ZipCrypto 路径没跳过 12 字节随机头。**
   实测 7-Zip 写的 ZipCrypto 条目 `compressedSize` 正好 = **12 + 明文长度**，
   那 12 字节是加密层自己的头、不是压缩数据。忘了跳过 → Store 条目多出 12 字节、
   Deflate 条目直接报格式错。（`zipcrypto-store.zip` 的长度关系最干净，
   是为这条断言专门留的。）

顺带把 commons-compress 那条 `Error on ZipFile <绝对路径>` 翻成了人话
（「文件不完整或已损坏（可能没下载完），也可能不是 ZIP」）——
只有路径、没有原因是没法给用户看的。

### 量长度口径为什么必须实测

`ZipArchive.openAes()` 里 `payload = compressed - saltLength - 2 - 10` 这一行，
靠猜是猜不出来的：WinZip AES 的 `compressedSize` 到底含不含 salt / 2 字节校验值 /
10 字节认证码，各工具的说法并不一致。`DumpZip.java` 把 `getRawInputStream()`
抽干数一遍再和字段值比，答案立刻明确（7-Zip 25.01：**含**，且明文不补零）：

```
AES-128: salt 8  + 2 + 24 + 10 = 44    （实测 compressed=44）
AES-256: salt 16 + 2 + 24 + 10 = 52    （实测 compressed=52）
ZipCrypto: 12 头 + 24 明文      = 36    （实测 compressed=36）
```

**不补零只是 7-Zip 的做法**：WinZip 会把压缩数据补齐到 AES 块边界，
而那部分填充同样计入 HMAC —— 所以 `VerifiedStream` 在核对认证码之前会把
密文流**抽干**，对「本来就抽干」的流是空操作。

## 3.12.0 在这一层抓到的四个真 bug

夹具换成本机工具（GNU tar / 官方 rar / 官方 7-Zip）之后，暴露出四个原来测不出来的问题：

1. **`TarArchive` 的 GNU `L`（LongLink）载荷没切末尾 NUL。**
   原有 tar 夹具全是 **pax** 格式，`L` 分支虽然写着「支持」却从没被执行过。
   NUL 不切掉 → 目录条目名成了 `.../dir04/\0` → `endsWith("/")` 判假 → 目录被当普通文件 →
   路径含 NUL 写不出去 → **那条连同它下面所有条目一起静默消失，解压还报成功**。
   GNU tar 是 Linux 默认，`foo.tar.bz2` / `foo.tar.xz` 几乎都是这种。
2. **`SevenZArchive.humanize()` 是死代码**，一次都没被调用；而且 `open()` 写在 `try` 外面，
   7z 的条目表在文件头里、**构造函数就会读它**，「文件截断」是从那里抛的。
   结果用户看到的是 `nextHeaderOffset is out of bounds` 这种英文原文。
3. **`SevenZArchive.timeOf()` 不判 `getHasLastModifiedDate()`。**
   自己用 `SevenZOutputFile` 写出的包，目录条目没盖时间 → 读回来直接抛
   `UnsupportedOperationException` —— 崩在「把刚压好的包重新打开」这一步。
4. **`Archives.extractEntry()` 的 tar 分支用「onEntry 里置取消」实现「命中即停」。**
   `onEntry` 是在**打开输出流、写数据之前**回调的，在那里置取消，
   `transfer` 会在写第一个字节前就抛 `Cancelled` —— 落下一个 **0 字节文件**
   还被当成「解出来了」交给预览。原来 ZipCheck 只断言「文件存在」，所以一直是绿的。

另外顺手对齐的两处：

- `RarArchive`：加密包不带密码时，原来 `extract` 会把密码错误当成「这个条目坏了」吞掉，
  **解压「成功」返回、一个文件都没落地**。现在「需要密码」是整包级错误，提前抛。
- `SevenZArchive.needsPassword()`：`-p`（只加密内容）原来判成「不需要密码」
  （因为不带密码也打得开头部），界面就不问密码了。现在按条目的内容方法查 AES。

## 一次性探测脚本（不参与 run-checks）

| 文件 | 用途 |
| --- | --- |
| `_env.sh` | 看 WSL 里有哪些归档工具、能不能下载 |
| `_probe-rarex.sh` | 找 rarlab 的二进制包名（`rarlinux-x64-624` 这种短名） |
| `_probe-raropt.sh` | 看某个 rar 版本支持哪些开关 |
| `_probe-links.sh` | 看「存成链接」还是「跟随链接」到底由哪个开关决定 |
| `_probe-zipenc.sh` | **用 7-Zip 交叉验证 `genzipenc.py` 造的中文密码夹具是不是标准 ZIP**（7zz 在 Linux 上按 UTF-8 处理密码，正好能验 `/utf8` 那份） |
| `_probe-zipdeflate.sh` | **证明 `zenc/` 为什么必须存在**：7-Zip 对小文件 / 压不动的内容一律落 Store，连 `-mm=Deflate -mx=9` 都被忽略（同一组开关，`zsrc` 全 Store、`zenc` 才出现 Deflate） |
| `ProbeList.java` | 把 `Archives.list` 的**条目顺序**原样打出来（带缩进和深度跳变提示）—— 「树还连不连得起来」就是靠它一眼看出来的 |
| `Dump7z.java` | 打印 7z 条目的属性 / 内容方法 / 是否 AES（**发现路径长度之外的那两条线索全靠它**） |
| `DumpTar.java` | 把同一个 tar 解到不同名字的目标目录，用来排除「路径太长」这种环境因素 |
| `DumpZip.java` | 打印 zip 条目的 `compressedSize` / bit 标志 / extra field / **`getRawInputStream()` 真实可读字节数**（「AES 的长度口径含不含 salt 与认证码」就是靠它量出来的，见上一节） |
