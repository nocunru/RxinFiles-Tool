package com.rxinfiles.tool;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「用其他应用打开」（QQ / 微信 / 文件管理器…）进来的文件该怎么处理：**判据全在这里**。
 *
 * <h2>这条链路只做一件事：把用户带到那个文件面前</h2>
 *
 * 外部应用递过来的是一个 {@code content://} URI 加一个显示名。现在这里不再按类型把文件
 * 分派给内置查看器，而是**「定位文件所在目录 + 高亮它」**：先尽力把 URI 反解成**原文件的
 * 真实路径**（零拷贝），反解不出来再把内容复制到 Download 兜底。所以本类不再判断
 * 「该用哪个查看器」，只保留两类判据 —— **反解出的路径可不可信**，以及既有的扩展名分类
 * （后者仍被文件列表的图标/描述复用）。
 *
 * <h2>为什么判据要单独成一个纯 Java 类</h2>
 *
 * 反解路径这件事「错一点就很难查」：名字对上了、路径却是猜的，会把用户带去**另一个文件**，
 * 比不定位更糟；文件名消毒漏一个字节，复制兜底那一步就是**路径穿越**。这些都不会编译报错、
 * 也不会立刻崩，只会以「用户某一次打开某个文件时不对劲」的形式出现。
 *
 * 所以把它们钉成"纯 Java、零 Android API"，交给 {@code tools/incoming-check} 用
 * {@code javac}/{@code java} 直接断言 —— 和 {@link TextSlicer}、{@link ScriptDone} 同一路数。
 * （真正「去读」的动作 —— 查 ContentResolver、按身份列目录 —— 是 Android 侧的，
 * 留在 {@code IncomingActivity}。）
 *
 * <h2>为什么是「一份表」而不是「抄一份」</h2>
 *
 * 扩展名判据本来在 {@code FileRepository}（Kotlin）里。那个类是 Android 侧的，
 * 没法脱离 Android 编译，本类也就不能去调它。与其把同一张表抄成两份等着慢慢跑偏，
 * 反过来让 {@code FileRepository} 的判定方法**转发到本类**：全工程只剩这一份表。
 *
 * 归档（压缩包）那一项不做表，**直接委托 {@code Archives}** —— 它是解压能力的
 * 权威来源，且同样是纯 Java。委托前自己抄过一张表，结果因为两处 {@code extOf}
 * 口径不同，`.zip` 这类名字两边给出相反答案（详见 {@link #isArchiveName}）。
 *
 * 本类因此依赖归档层（{@code Archives} 及其同包的四个归档类 + app/libs 下的
 * 解压库），但**依然零 Android API**：{@code tools/incoming-check/run.sh} 会用
 * {@code -sourcepath} + {@code app/libs/*} 把这一小片纯 Java 编出来跑断言。
 *
 * <h2>这个入口不做的事</h2>
 *
 * **不执行任何脚本，也不打开那个文件本身**。content 来源不可信（名字、路径都是对方说了算），
 * 这个入口的全部动作就是把用户带到文件所在目录并高亮那一行；实在定位不到原位置时，
 * 才把内容复制到 Download 兜底 —— 那一步用的「不覆盖已有文件」的落地命名也放在本类
 * （{@link #uniqueName}），免得它埋在 Activity 私有方法里脱离 Android 就测不到。
 *
 * <h2>「反解路径」反解不出来怎么办（3.15.0 修复）</h2>
 *
 * 反解本质是猜 URI 结构，而 QQ 的 provider 未必给 {@code _data}。所以反解失败后，
 * 不再直接复制兜底，而是**用我们确知的两件事去存储里找**：显示名 + 大小。判据与命令
 * 构造在本类（{@link #quoteShell}、{@link #buildSearchCommand}、{@link #parseSearchOutput}、
 * {@link #matchesSearchHit}），真正执行 find / 复核可见性留在 Android 侧。搜索
 * **只做精确匹配**（名字逐字符 + 大小逐字节），且**必须限时**（见 {@link #buildSearchCommand}）。
 * 搜不到也不放宽任何校验：宁可判失败，也不猜一个像的路径。定位失败时把整条推理过程
 * 交给 {@link #formatDiagnostics} 成文，供用户一次复现就能把答案带回来。
 */
public final class IncomingFile {

    private IncomingFile() {
    }

    /** 谈不出名字时用的兜底文件名 */
    public static final String DEFAULT_NAME = "incoming_file";

    /**
     * 复制兜底的目标子目录名（挂在外部存储的 `Download/` 之下）。
     *
     * <h2>为什么不直接丢在 `Download/` 根（3.15.2 改）</h2>
     *
     * 外部打开的兜底是「把内容复制一份到用户看得见的地方」，而 `Download/` 根是用户
     * **自己也在用的**目录 —— 每次点开一个 QQ 收到的文件就往里堆一份，很快就把用户
     * 自己的下载物淹了。给这一批「本应用转存出来的副本」一个专属子目录，用户一眼能认出
     * 它们从哪来，也方便整批清理。
     *
     * 名字固定为 `RxinDownload`，与应用的 `RXin` 前缀一致。
     */
    public static final String COPY_DIR_NAME = "RxinDownload";

    /**
     * 定位失败、且**连 shell 通道都没有**时，提示用户去装 Shizuku 的下载页。
     *
     * <h2>为什么需要这个提示（3.15.2 补）</h2>
     *
     * 这是本项目最容易让用户困惑的一处：`Android/data` 下那些目录（QQ / 微信收到的文件几乎
     * 全在那里）在**普通身份下根本进不去** —— `targetSdk 34` + Scoped Storage，
     * App 自己（Java 通道）的 `File.listFiles()` 只会拿到 `errno=13 (EACCES)`。
     * 能进去的只有两种身份：**root** 或 **Shizuku（adb 级，uid 2000）**。
     *
     * 用户没 root 时，「定位不到」这件事**看起来像我们的 bug**，实际是缺一个前置条件。
     * 与其让他反复试，不如在失败面板上直接给一条可点的出路。
     *
     * <h2>只在真的「没有 shell 通道」时才提示</h2>
     *
     * 判据是 {@link #needsShellHint}，**不是**「定位失败了就提示」——
     * 若用户已有 ROOT / ADB，失败原因就在别处（路径拼不出、名字不符…），
     * 这时再劝他装 Shizuku 是**误导**。所以提示必须**按身份分流**，与本项目其它判据同一条原则。
     */
    public static final String SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/";

    /** 提示正文（面板按钮旁的说明；文案收在判据层，好被断言钉住）。 */
    public static final String SHIZUKU_HINT_TEXT =
            "当前是普通权限，无法读取 Android/data 下的目录（QQ / 微信收到的文件多在那里）。"
                    + "安装并激活 Shizuku 后即可定位。";

    /** 主外部存储的标准前缀 —— {@link #normalizeStoragePath} 的归一化落点。 */
    public static final String EMULATED_ROOT = "/storage/emulated/0";

    /**
     * 「浏览器进了 Android/data 但读不出来」这张提醒面板的标题与正文。
     *
     * <p>正文与 {@link #SHIZUKU_HINT_TEXT} 是**两条不同的文案**，不能合并：
     * 那一条说的是「**没能定位文件**」（外部打开那条路），这一条说的是「**这个目录你打不开**」
     * （用户自己点进来的）。两处的用户预期完全不同，混用会让其中一处答非所问。
     */
    public static final String NO_ACCESS_TITLE = "这个目录需要额外权限";

    public static final String NO_ACCESS_BODY =
            "这里是 Android/data，Android 11 起普通应用不被允许读取它 —— "
                    + "这不是本应用的问题，任何没有特权的文件管理器都会被挡住。\n\n"
                    + "装上 Shizuku 并激活后，本应用就能以 shell 身份读它"
                    + "（QQ / 微信收到的文件大多在这里面）。";

    /**
     * 浏览到 `Android/data` 且**真的读不出来**时，提醒用户「这里需要 Shizuku（或 root）」。
     *
     * <h2>为什么要单独一条（3.15.3 补）</h2>
     *
     * 3.15.2 已经把「定位失败」那张面板接了 Shizuku 提示，但那只在**外部打开**那条路上。
     * 用户自己**从文件管理器点进 `Android/data`** 时，看到的是另一样东西：列表里只有一行 `..`，
     * 或者干脆空白 —— 界面不会告诉他为什么。而 `Android/data` 恰恰是 QQ / 微信收到的文件
     * 最常落的地方，用户点进去很自然。**没有提示时，这一屏看起来就是我们坏了。**
     *
     * <h2>判据为什么不能是「列表是空的」</h2>
     *
     * {@code FileRepository.listWithJava} **先塞 `..` 再列**，而且 `listFiles()` 返回 null 时
     * 直接 {@code ?: return result} —— 于是「读不出来」的返回值是**只含 `..` 的非空列表**，
     * `items.isEmpty()` 恒假。所以判「真的访问不了」**必须显式再探一次**
     * （{@code FileRepository.probeListJava}），复用诊断里那套已经验证过的探针。
     *
     * <h2>三个条件同时成立才算「真的访问不了」</h2>
     *
     * <ol>
     *   <li>路径确实位于 `Android/data` 之下 —— 别的地方读不出来原因各异，提示会误导；</li>
     *   <li>当前身份是**普通**（没有 shell 通道）—— 有 root / Shizuku 的用户读不出来是别的原因，
     *       再劝他装 Shizuku 是误导（同 {@link #needsShellHint} 的身份分流原则）；</li>
     *   <li>{@code probeListJava} 也列不出 —— **这一条把「空目录」和「无权限」分开了**：
     *       空目录 `listFiles()` 返回长度 0 的数组（探针 ok），无权限返回 null（探针 fail）。</li>
     * </ol>
     *
     * @param path           被浏览的目录绝对路径
     * @param shellLevelLabel 当前身份标签（见 {@link #NORMAL_LEVEL_LABEL}）
     * @param javaProbeOk      {@code probeListJava} 的结论：能列出为 true
     */
    public static boolean shouldWarnNoAccess(String path, String shellLevelLabel, boolean javaProbeOk) {
        if (!isUnderAndroidData(path)) {
            return false;
        }
        if (!needsShellHint(shellLevelLabel)) {
            return false;
        }
        return !javaProbeOk;
    }

    /**
     * 路径是否位于**任一存储卷**的 `Android/data`（或 `Android/obb`）之下。
     *
     * <p>只做字符串判据（不改动路径），且**按路径段比对**而不是 `contains("Android/data")` ——
     * 后者会把 `/storage/emulated/0/我的Android/data备份` 这种名字也算进来。
     *
     * <p>要同时对两种前缀成立：`/sdcard/…`（`/sdcard` 是指向 `/storage/self/primary` 的软链）
     * 与 `/storage/emulated/0/…`（应用真正拿到的路径）。**比较前先归一化** ——
     * `/sdcard` 展开成 `/storage/emulated/0`，并把重复斜杠压成一个、去掉尾斜杠。
     *
     * <p>`Android/obb` 一并纳入：它与 `Android/data` 同属「本应用无权限、需要特权身份」的那一类，
     * 用户在这两处看到的现象一模一样。
     */
    public static boolean isUnderAndroidData(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String normalized = normalizeStoragePath(path);
        String[] parts = normalized.split("/");
        // parts[0] 恒为空（路径以 / 开头），所以从 1 开始找 "Android" 段
        for (int i = 1; i + 1 < parts.length; i++) {
            if (!"Android".equals(parts[i])) {
                continue;
            }
            String next = parts[i + 1];
            if ("data".equals(next) || "obb".equals(next)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把常见的外部存储前缀归一化，好让 `/sdcard/x` 与 `/storage/emulated/0/x` 判成同一处。
     *
     * <p>只处理**前缀等价**与**重复斜杠 / 尾斜杠**，不做真实路径解析（那要碰文件系统）。
     * `/storage/self/primary` 是 `/storage/emulated/0` 的同义词（AOSP：`self` 指向当前用户），
     * 一并展开 —— 用户从不同入口点进来时前缀确实会不一样。
     */
    public static String normalizeStoragePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String out = path.replaceAll("/{2,}", "/");
        String[] aliases = {"/sdcard", "/storage/self/primary"};
        for (String alias : aliases) {
            if (out.equals(alias)) {
                out = EMULATED_ROOT;
                break;
            }
            if (out.startsWith(alias + "/")) {
                out = EMULATED_ROOT + out.substring(alias.length());
                break;
            }
        }
        while (out.length() > 1 && out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /**
     * 是否该给「去装 Shizuku」的提示。
     *
     * <p>**成立条件 = 两个同时满足**：① 这一轮定位失败（调用方保证，只在失败面板里问）；
     * ② 当前身份是**普通**（没有 shell 通道）。
     *
     * <p>身份判据按 [ShellLevel] 的标签来（「普通」= NORMAL）。这里刻意**不用
     * 「Shell身份 == null」**当判据：那会把「身份还没探出来」也当成「普通」，
     * 而两者对用户的意义完全不同 —— 前者该提示，后者该先探一次。
     */
    public static boolean needsShellHint(String shellLevelLabel) {
        if (shellLevelLabel == null || shellLevelLabel.isEmpty()) {
            return false;
        }
        return NORMAL_LEVEL_LABEL.equals(shellLevelLabel);
    }

    /** [ShellLevel.NORMAL] 的展示标签 —— 与 `ShellExecutor.ShellLevel.NORMAL.label` 一致。 */
    public static final String NORMAL_LEVEL_LABEL = "普通";

    /**
     * 显示名的长度上限。
     *
     * 多数文件系统的单个文件名上限是 255 字节，而 UTF-8 下一个汉字占 3 字节 ——
     * 200 个字符最坏也就 600 字节，会超。这里取 120 更稳妥：
     * 真实世界里没有哪个分享出来的文件名靠前 120 个字符还分不出来。
     */
    public static final int MAX_NAME_CHARS = 120;

    // ------------------------------------------------------------ 扩展名表

    private static Set<String> set(String... items) {
        Set<String> s = new HashSet<String>();
        for (String item : items) {
            s.add(item);
        }
        return Collections.unmodifiableSet(s);
    }

    /** 图片扩展名（与内置图片查看器的口径一致） */
    public static final Set<String> IMAGE_EXT = set(
            "jpg", "jpeg", "jfif", "png", "gif", "bmp", "webp", "wbmp", "ico",
            "heic", "heif", "avif", "tif", "tiff");

    /** 文本扩展名 */
    public static final Set<String> TEXT_EXT = set(
            "txt", "text", "log", "md", "markdown", "rst", "csv", "tsv",
            "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "m", "mm",
            "java", "kt", "kts", "scala", "go", "rs", "swift",
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "svelte",
            "py", "pl", "rb", "lua", "php", "sql", "smali",
            "sh", "bash", "zsh", "ash", "ksh", "fish", "bat", "cmd", "ps1",
            "json", "xml", "html", "htm", "xhtml", "css", "scss", "less", "svg",
            "yml", "yaml", "toml", "ini", "conf", "cfg", "config", "properties",
            "prop", "rc", "env", "gradle", "pro", "mk", "cmake", "patch", "diff",
            "srt", "ass", "vtt", "pem", "crt", "key", "desktop", "service", "shinit");

    /** 无扩展名但一眼就知道是文本的文件 */
    public static final Set<String> TEXT_NAMES = set(
            "readme", "license", "licence", "changelog", "install", "notice",
            "makefile", "dockerfile", "procfile", "authors", "copying", "todo");

    /** 视频扩展名（内置 VideoView 播放器）；.ts 仍是 TypeScript 文本，不在此列。 */
    public static final Set<String> VIDEO_EXT = set(
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "m4v");

    /** 音频扩展名（交给其它应用打开） */
    public static final Set<String> MEDIA_EXT = set(
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "oga");

    /**
     * 「复合后缀」归档：`tar.gz` 这类光看最后一段扩展名（gz）会被当成单文件压缩，
     * 结构就丢了。必须整段比。
     *
     * 只用于 [clamp]（超长名字截断时别把复合后缀截掉）。**归档识别本身已经全部
     * 委托给 [Archives]**，见 {@link #isArchiveName}，这里不再维护一份扩展名表。
     */
    public static final Set<String> ARCHIVE_SUFFIX = set(
            ".tar.gz", ".tar.gzip", ".tgz", ".tar.bz2", ".tar.xz", ".tar.lzma");

    /** 「一定要执行」的脚本：shell 家族 */
    public static final Set<String> STRICT_SCRIPT_EXT = set(
            "sh", "bash", "zsh", "ash", "ksh", "mksh", "fish");

    /** 「两可」的脚本：既能当程序跑，也能当文本看 */
    public static final Set<String> AMBIGUOUS_SCRIPT_EXT = set(
            "py", "js", "mjs", "pl", "rb", "lua");

    // ------------------------------------------------------------ 扩展名工具

    /**
     * 取扩展名（不含点，已小写）。
     *
     * 与 {@code FileRepository.extOf} 同一口径：点开头（`.bashrc`）或点结尾
     * （`foo.`）都不算扩展名 —— 前者是隐藏文件、后者是没写完的名字。
     */
    public static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    // ------------------------------------------------------------ 分类判据

    public static boolean isImageName(String name) {
        return !extOf(name).isEmpty() && IMAGE_EXT.contains(extOf(name));
    }

    public static boolean isTextName(String name) {
        if (name == null) {
            return false;
        }
        String ext = extOf(name);
        if (!ext.isEmpty() && TEXT_EXT.contains(ext)) {
            return true;
        }
        if (ext.isEmpty() && TEXT_NAMES.contains(name.toLowerCase(Locale.US))) {
            return true;
        }
        // 点开头的隐藏文件（.bashrc）没有扩展名，但基本都是文本
        return name.startsWith(".") && !name.startsWith("..") && ext.isEmpty();
    }

    public static boolean isVideoName(String name) {
        return !extOf(name).isEmpty() && VIDEO_EXT.contains(extOf(name));
    }

    public static boolean isMediaName(String name) {
        return !extOf(name).isEmpty() && MEDIA_EXT.contains(extOf(name));
    }

    /**
     * 是否是「可解压 / 可预览」的归档（按扩展名）。
     *
     * **直接委托 [Archives]（纯 Java，零 Android）** —— 它是解压能力的权威来源，
     * 归档识别的口径必须与它逐条一致。
     *
     * <h2>为什么不自己维护一张表（这里踩过一次真实的坑）</h2>
     *
     * {@link #extOf} 与 `Archives` 内部的 extOf 口径**不同**：
     * 本类的 `extOf` 用 `dot &lt;= 0`（点开头 = 隐藏文件 = 无扩展名），
     * 而 `Archives` 用 `dot &lt; 0 || dot == len-1` —— 对 `.zip` 这种名字，
     * `dot == 0` 两条都不落，于是 `ext = "zip"`、判为归档。
     *
     * 结果是同一个「名为 `.zip` 的文件」在两边得到相反答案：既有链路进归档面板，
     * 新入口却当成隐藏文本。**这个历史不一致本身是否合理不在本次范围内，不要改**
     * （改了会改变既有分类语义）；本次只保证「从外部打开」这条新链路与既有行为
     * 逐条一致 —— 委托过去是唯一稳妥、不可能漂移的做法。
     */
    public static boolean isArchiveName(String name) {
        return Archives.isArchiveName(name);
    }

    public static boolean isStrictScriptName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.equals(".shinit") || lower.endsWith(".shinit")) {
            return true;
        }
        String ext = extOf(name);
        return !ext.isEmpty() && STRICT_SCRIPT_EXT.contains(ext);
    }

    public static boolean isAmbiguousScriptName(String name) {
        return !extOf(name).isEmpty() && AMBIGUOUS_SCRIPT_EXT.contains(extOf(name));
    }

    /**
     * 是否为可执行脚本（旧 {@code FileRepository.isScriptName} 的口径）。
     *
     * 这一条被文件列表用来打「脚本」标记，历史口径是**按结尾字符串**比，
     * 而不是按扩展名 —— 例如名字正好是 `.sh` 时按结尾算脚本。这里照旧，
     * 免得动了列表上的标记。
     */
    public static boolean isScriptName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh")
                || lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".pl")
                || lower.endsWith(".rb") || lower.endsWith(".lua") || lower.equals(".shinit");
    }

    // ------------------------------------------------------------ 文件名消毒

    /**
     * 把外来的显示名洗成一个**只能是普通文件名**的字符串。
     *
     * 为什么必须洗：显示名是**对方应用说了算的**，而我们马上会用它去拼
     * {@code File(cacheDir, name)}。哪怕只有一个字节没处理到，就是一条路径穿越 ——
     * 对方塞一个 `../../../../data/data/com.rxinfiles.tool/...` 就能决定我们往哪写。
     *
     * 处理顺序：
     * <ol>
     *   <li>先取最后一段（`/` 与 `\` 都算分隔符）：`a/b/evil.sh` → `evil.sh`；</li>
     *   <li>丢掉控制字符（含 NUL）与残留的分隔符；</li>
     *   <li>首尾空白抹掉；</li>
     *   <li>「纯点」的名字（`.`/`..`/`...`）一律换默认名；</li>
     *   <li>过长就截断，且**保留扩展名**。</li>
     * </ol>
     */
    public static String safeName(String rawName) {
        return safeName(rawName, DEFAULT_NAME);
    }

    /** 同 {@link #safeName(String)}，但可以指定兜底名 */
    public static String safeName(String rawName, String fallback) {
        String base = rawName == null ? "" : rawName;

        // 1) 只取最后一段：显示名有时是整个路径
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }

        // 2) 丢掉控制字符与残留分隔符
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            if (c == '/' || c == '\\') {
                continue;
            }
            sb.append(c);
        }
        String name = sb.toString().trim();

        // 3) 纯点：`.` 是当前目录、`..` 是上一层，都不能当文件名
        if (name.isEmpty() || isAllDots(name)) {
            name = (fallback == null || fallback.isEmpty()) ? DEFAULT_NAME : fallback;
        }

        // 4) 截断长度，但保留扩展名（扩展名正是后面判类型要用的）
        return clamp(name, MAX_NAME_CHARS);
    }

    private static boolean isAllDots(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) != '.') {
                return false;
            }
        }
        return !name.isEmpty();
    }

    private static String clamp(String name, int maxChars) {
        if (name.length() <= maxChars) {
            return name;
        }
        String lower = name.toLowerCase(Locale.US);
        // 复合后缀（`.tar.gz`）必须整段保留：按最后一个点截会只剩 `.gz`，
        // 把「tar 归档」这一层丢掉，类型判定就跟着错。
        for (String suffix : ARCHIVE_SUFFIX) {
            if (lower.endsWith(suffix)) {
                int keep = maxChars - suffix.length();
                if (keep > 0) {
                    return name.substring(0, keep) + name.substring(name.length() - suffix.length());
                }
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 16) {
            String ext = name.substring(dot);
            int keep = maxChars - ext.length();
            if (keep > 0) {
                return name.substring(0, keep) + ext;
            }
        }
        return name.substring(0, maxChars);
    }

    // ------------------------------------------------------------ 落地命名

    /**
     * 在 {@code dir} 里给 {@code name} 找一个**不覆盖已有文件**的落地名（只算名字，不建文件）。
     *
     * <h2>为什么必须去重，而不是直接写</h2>
     *
     * 「反解不出原位置、复制到 Download 兜底」那一步的落地点是
     * {@code /sdcard/Download/RxinDownload/}（见 {@link #COPY_DIR_NAME}），
     * 用户（或别的应用）很可能已经放了一个同名文件。直接写上去
     * 就是把用户自己原有的东西冲掉 —— 一次静默的数据损失。所以同名时追加 {@code " (n)"}：
     * {@code report.pdf} → {@code report (1).pdf} → {@code report (2).pdf} …
     *
     * <h2>切口沿用既有口径（不要"顺手"改）</h2>
     *
     * 以**最后一个点**切开扩展名，且要求 {@code dot > 0}：
     * <ul>
     *   <li>{@code report.pdf} → stem {@code report} + ext {@code .pdf}；</li>
     *   <li>{@code report.tar.gz} → stem {@code report.tar} + ext {@code .gz}（复合后缀只保最后一段）；</li>
     *   <li>{@code .bashrc}（{@code dot == 0}）→ 整名当 stem、无扩展名 → {@code .bashrc (1)}；</li>
     *   <li>{@code noext} → 整名当 stem、无扩展名 → {@code noext (1)}。</li>
     * </ul>
     * 这不是唯一的合理口径，但它是**原有行为**；本次只把它从 Activity 私有方法搬下来
     * 并补断言，不改变语义。
     *
     * <h2>为什么下沉成纯 Java</h2>
     *
     * 原先它是 {@code IncomingActivity} 的私有方法，脱离 Android 就测不到 —— 等于没测。
     * 判据应当待在能离线 {@code javac}/{@code java} 断言的层（{@code tools/incoming-check}）。
     *
     * @param dir  目标目录（调用方保证存在；本方法不创建它）
     * @param name 已消毒的落地名（一般来自 {@link #safeName}）
     * @return 可用的文件名（**不含目录部分**）。{@code name} 为空时退回 {@link #DEFAULT_NAME}
     */
    public static String uniqueName(File dir, String name) {
        if (dir == null || name == null || name.isEmpty()) {
            return DEFAULT_NAME;
        }
        if (!new File(dir, name).exists()) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0) ? name.substring(0, dot) : name;
        String ext = (dot > 0) ? name.substring(dot) : "";
        for (int n = 1; n < Integer.MAX_VALUE; n++) {
            String candidate = stem + " (" + n + ")" + ext;
            if (!new File(dir, candidate).exists()) {
                return candidate;
            }
        }
        throw new IllegalStateException("No available file name for " + name);
    }

    // ------------------------------------------------------------ URI 方案

    /**
     * 只接受这两种 scheme。
     *
     * 这个 Activity 是 exported 的，等于对设备上任何 App 开放；URI 的 scheme
     * 是对方唯一没法伪造的字段（`data` 必须能被系统解析成 URI 才会派发过来）。
     * 其它 scheme（http、content-provider 私有 scheme…）一律不认。
     */
    public static boolean isHandledScheme(String scheme) {
        if (scheme == null) {
            return false;
        }
        String s = scheme.toLowerCase(Locale.US);
        return s.equals("content") || s.equals("file");
    }

    // ------------------------------------------------------------ 路径可信度

    /**
     * 反解出来的候选路径是否**可信**：文件名必须与对方给的显示名相符。
     *
     * <h2>为什么这是最要紧的一条判据</h2>
     *
     * 把 {@code content://} 反解成真实路径，本质是**猜** —— provider 的 authority、
     * path 段、段数都不统一。猜错的后果不是「打开失败」，而是**把用户带去了另一个文件**：
     * 他以为看到的是刚点的那个，其实是同目录下的另一个同名/近名文件。这比「定位不了、
     * 退回复制」糟糕得多。
     *
     * 所以这里的口径是**宁严勿松**：显示名拿不到（null / 空）就直接判不可信 ——
     * 无从核对就等于不可信；路径最后一段与显示名必须**逐字符相同**（大小写敏感，
     * 因为类 Unix 文件系统区分大小写）。任何一条不满足都返回 {@code false}，
     * 让调用方走复制兜底。
     *
     * 显示名偶尔会带上一整条路径（有些 provider 这么干），所以显示名也先取最后一段。
     *
     * @param displayName   provider 给的显示名（可为 null / 空）
     * @param candidatePath 反解出来的候选绝对路径
     */
    public static boolean matchesDisplayName(String displayName, String candidatePath) {
        if (displayName == null || displayName.isEmpty()) {
            return false;
        }
        if (candidatePath == null || candidatePath.isEmpty()) {
            return false;
        }
        String want = lastSegment(displayName);
        String got = lastSegment(candidatePath);
        if (want.isEmpty() || got.isEmpty()) {
            return false;
        }
        return want.equals(got);
    }

    /**
     * 取路径/名字的最后一段（`/` 与 `\` 都算分隔符）。
     *
     * 结尾带分隔符时返回空串 —— 这种候选路径本就不可信，交由调用方判失败。
     */
    public static String lastSegment(String value) {
        if (value == null) {
            return "";
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    // ------------------------------------------------------------ 内容嗅探

    /**
     * 内容嗅探：前 4KB 像不像文本。
     *
     * 判据保守 —— 出现 NUL 就直接判二进制（文本文件几乎不含 NUL），
     * 否则要求可打印字符占比 ≥95%。宁可漏判也不误判：把二进制按文本打开，
     * 用户看到的是满屏乱码。
     *
     * 纯 Java：只用到 {@link File} 与 {@link RandomAccessFile}，可以离线断言。
     */
    public static boolean looksLikeText(File file) {
        try {
            if (file == null || !file.isFile() || file.length() == 0L) {
                return false;
            }
            byte[] head = new byte[(int) Math.min(file.length(), 4096L)];
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                raf.readFully(head);
            } finally {
                raf.close();
            }
            int printable = 0;
            boolean binary = false;
            for (byte b : head) {
                int v = b & 0xFF;
                if (v == 0) {
                    binary = true;
                    break;
                }
                if (v == 9 || v == 10 || v == 13 || (v >= 0x20 && v <= 0x7E) || v >= 0x80) {
                    printable++;
                }
            }
            return !binary && ((double) printable / head.length) >= 0.95;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ shell 转义

    /**
     * 把一个字符串构造成 shell 命令行里的**单个字面量**（POSIX 单引号转义）。
     *
     * <h2>为什么这是安全关键</h2>
     *
     * 「按名 + 大小搜文件」那条策略会把**对方应用给的显示名**拼进 `find -name`。
     * 显示名里只要出现一个单引号或分号，对方就能把我们的命令截断并在后面接任意命令 ——
     * 而这个入口是 {@code exported="true"} 的，等于对外开了一个命令注入点。
     *
     * <h2>为什么自己实现一份（而不是调 ScriptRunner.quote）</h2>
     *
     * 口径与 {@code ScriptRunner.quote} **逐字符一致**：整串用单引号包起来，内部的
     * 单引号写成 {@code '\''}（闭合当前引号 → 反斜杠转义 → 重开引号），其余字符原样。
     * {@code ScriptRunner} 是 Kotlin（Android 侧），本类必须保持「纯 Java、零 Android API」
     * 才能被 {@code tools/incoming-check} 用 {@code javac} 直接断言 —— 纯 Java 类调不了它。
     * 与其让依赖倒过来（那会毁掉离线断言），不如把这条**极短、语义固定**的转义在此复刻，
     * 并在断言里钉住它与 {@code ScriptRunner.quote} 的输出完全相同（见 IncomingCheck §10）。
     */
    public static String quoteShell(String raw) {
        String value = (raw == null) ? "" : raw;
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------ 按名 + 大小搜索

    /**
     * 「优先搜索」的已知目录（相对外部存储根）。
     *
     * 外部应用（QQ / 微信 / 文件管理器…）收到/落盘的文件绝大多数集中在这些位置，
     * 先搜它们是为了让常见情况**快**，避免一上来就全盘遍历。真正的兜底才是
     * 限时全盘搜（见 {@link #buildSearchCommand} 的超时参数）。
     */
    public static final List<String> KNOWN_SEARCH_SUBDIRS =
            Collections.unmodifiableList(Arrays.asList(
                    "Android/data/com.tencent.mobileqq",
                    "Tencent",
                    "Download",
                    "Documents"));

    /** 搜已知目录的超时（秒）。命中面窄，给短一点，别让用户等。 */
    public static final int SEARCH_TIMEOUT_KNOWN_SECONDS = 3;

    /** 全盘搜的超时（秒）。**必须限时**：/sdcard 可能几十万文件，不限时会卡住调用方。 */
    public static final int SEARCH_TIMEOUT_FULL_SECONDS = 5;

    /**
     * 搜索命令里用来自报状态的标记行。
     *
     * 为什么要标记：搜索有三种**必须能区分**的失败 —— ①没有可用的超时程序（命令根本没跑）、
     * ②跑超时了（只搜了一部分）、③正常跑完但没命中。三者若不区分，用户看到的就是一句
     * 「命中 0 个候选」，而下一轮排查仍然只能猜。标记以 `##` 开头、**不以 `/` 开头**，
     * 所以会被 {@link #parseSearchOutput} 当作噪声滤掉，不会污染候选路径。
     */
    public static final String MARK_NO_TIMEOUT = "##RXIN_NO_TIMEOUT";

    /** 见 {@link #MARK_NO_TIMEOUT}：搜索被 timeout 终止（只搜了一部分）。 */
    public static final String MARK_TIMEOUT = "##RXIN_TIMEOUT";

    /**
     * 搜索的结局。三种失败要能在诊断里分清（见 {@link #MARK_NO_TIMEOUT}）。
     */
    public enum SearchStatus {
        /** 命令正常跑完（可能 0 命中） */
        OK("正常"),
        /** 被 timeout 终止，只搜了一部分 */
        TIMEOUT("超时（被 timeout 终止，只搜了一部分）"),
        /** 内置 busybox 与系统 timeout 都没有 —— 命令**根本没跑** */
        NO_TIMEOUT_TOOL("无可用 timeout（内置 busybox 与系统 timeout 都没有，命令未执行）"),
        /** 有 shell 但命令没吐出任何东西 */
        NO_OUTPUT("shell 无输出"),
        /** 没有 shell 通道，退回 Java 遍历 */
        NO_SHELL("无 shell 通道，退回 Java 遍历");

        private final String label;

        SearchStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 把 {@link #KNOWN_SEARCH_SUBDIRS} 拼到外部存储根下，得到**绝对**目录列表。
     *
     * 根目录结尾的斜杠先剥掉（{@code Environment.getExternalStorageDirectory()} 有的
     * 实现会带上），否则会拼出 {@code /storage/emulated/0//Tencent} 这种双斜杠路径。
     * 根为空时返回空表 —— 宁可什么都不搜，也不搜一个错的地方。
     */
    public static List<String> knownSearchDirs(String externalRoot) {
        List<String> out = new ArrayList<String>();
        if (externalRoot == null || externalRoot.isEmpty()) {
            return out;
        }
        String root = externalRoot;
        while (root.length() > 1 && root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        for (String sub : KNOWN_SEARCH_SUBDIRS) {
            out.add(root + "/" + sub);
        }
        return out;
    }

    /**
     * 「能不能按名字 + 大小去搜」——两个输入缺一不可。
     *
     * 大小是这条策略的**关键判据**（防同名不同文件），拿不到大小就没法确认命中，
     * 所以大小未知（{@code < 0}）直接不放行；显示名（末段）为空也一样。
     * 这里**宁可不搜，也不搜一个无法确认的东西**。
     */
    public static boolean canSearchByNameAndSize(String displayName, long size) {
        if (size < 0L) {
            return false;
        }
        return !lastSegment(displayName).isEmpty();
    }

    /**
     * 构造「在若干目录下找指定名字 + 大小的文件」的 shell 命令（**带限时与状态自报**）。
     *
     * <pre>
     * __rxin_to=''; [ -x '/data/.../bin/busybox' ] &amp;&amp; __rxin_to='/data/.../bin/busybox timeout'; \
     * [ -n "$__rxin_to" ] || { command -v timeout &gt;/dev/null 2&gt;&amp;1 &amp;&amp; __rxin_to=timeout; }; \
     * if [ -n "$__rxin_to" ]; then $__rxin_to 5 find '/sdcard/Tencent' -type f -name 'x.pdf' -size 1234c 2&gt;/dev/null; \
     * __rxin_rc=$?; [ "$__rxin_rc" = 124 ] &amp;&amp; printf '\n##RXIN_TIMEOUT\n'; \
     * else printf '##RXIN_NO_TIMEOUT\n'; fi
     * </pre>
     *
     * <h2>为什么要这么长</h2>
     * <ul>
     *   <li><b>内置 busybox 的 timeout 优先</b>：本工程随包内置了 busybox（[RxinToolbox]），
     *       它的 `timeout` 一定在；系统 toybox 的 `timeout` 只是次选。上一版**只**用系统
     *       `timeout`，一旦某 ROM 没有它，`timeout: not found` 会被 `2&gt;/dev/null` 吞掉、
     *       搜索静默退化成「0 候选」—— 这正是这一版要消掉的风险。</li>
     *   <li><b>没有可用 timeout 就明确报出来</b>（{@link #MARK_NO_TIMEOUT}），不静默。</li>
     *   <li><b>超时也报出来</b>（{@link #MARK_TIMEOUT}）：`timeout` 超时退出码是 124，
     *       据此把「只搜了一部分」和「搜完了没命中」分开。</li>
     *   <li>目录与文件名都过 {@link #quoteShell}：显示名来自不可信来源，不转义就是命令注入。</li>
     *   <li>{@code -size Nc}（`c` = 字节）逐字节精确匹配；{@code 2&gt;/dev/null} 丢弃 find 的噪声。</li>
     * </ul>
     *
     * <p>注意：这只是**候选**来源。采用某条命中前，调用方还要按「真正去读它的那个身份」
     * 用 {@code FileRepository.listDir} 复核名字与大小 —— 「搜到了」不等于「就是它」。
     *
     * @param dirs           要搜的目录（绝对路径；null / 空串项自动跳过）
     * @param name           目标文件名（调用方应传 {@link #lastSegment} 的结果）
     * @param size           目标大小（字节）；{@code < 0} 表示不加大小条件
     * @param busyboxPath    内置 busybox 的绝对路径（可执行），用作首选 timeout；
     *                       null / 空表示没有内置 busybox，退回系统 timeout
     *                       （**假定路径不含空格** —— 它是应用沙箱内的固定路径）
     * @param timeoutSeconds shell 侧超时秒数；{@code <= 0} 时按 1 秒处理（绝不能不限时）
     */
    public static String buildSearchCommand(List<String> dirs, String name, long size,
                                            String busyboxPath, int timeoutSeconds) {
        StringBuilder find = new StringBuilder("find");
        if (dirs != null) {
            for (String dir : dirs) {
                if (dir != null && !dir.isEmpty()) {
                    find.append(' ').append(quoteShell(dir));
                }
            }
        }
        find.append(" -type f -name ").append(quoteShell(name == null ? "" : name));
        if (size >= 0L) {
            find.append(" -size ").append(size).append('c');
        }
        find.append(" 2>/dev/null");

        int secs = timeoutSeconds > 0 ? timeoutSeconds : 1;

        StringBuilder sb = new StringBuilder();
        // 1) 选一个可用的超时程序：内置 busybox 优先，系统 timeout 次选
        sb.append("__rxin_to=''; ");
        if (busyboxPath != null && !busyboxPath.isEmpty()) {
            sb.append("[ -x ").append(quoteShell(busyboxPath)).append(" ] && __rxin_to=")
                    .append(quoteShell(busyboxPath + " timeout")).append("; ");
        }
        sb.append("[ -n \"$__rxin_to\" ] || { command -v timeout >/dev/null 2>&1 && ")
                .append("__rxin_to=timeout; }; ");
        // 2) 有超时程序就跑（限时）；否则明确报「无可用 timeout」而不是静默 0 结果
        sb.append("if [ -n \"$__rxin_to\" ]; then $__rxin_to ").append(secs).append(' ')
                .append(find)
                .append("; __rxin_rc=$?; [ \"$__rxin_rc\" = 124 ] && printf '\\n")
                .append(MARK_TIMEOUT).append("\\n'")
                .append("; else printf '").append(MARK_NO_TIMEOUT).append("\\n'; fi");
        return sb.toString();
    }

    /**
     * 解析 find 的输出为候选路径列表。
     *
     * 只认**绝对路径**行：shell 里可能混进 `find: '/x': Permission denied` 或
     * `timeout: not found` 之类的提示，它们都**不以 `/` 开头**，据此一并滤掉 ——
     * 把一句报错当成文件路径，下一步的可见性核对就会拿到一个不存在的东西。
     * 同时去重、保序。
     */
    public static List<String> parseSearchOutput(String output) {
        List<String> out = new ArrayList<String>();
        if (output == null) {
            return out;
        }
        Set<String> seen = new HashSet<String>();
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || !line.startsWith("/")) {
                continue;
            }
            if (seen.add(line)) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * 从搜索命令的输出里判出**结局**（正常 / 超时 / 无 timeout 工具 / 无输出）。
     *
     * 依据是 {@link #buildSearchCommand} 埋下的两种标记行。三种失败要能区分，
     * 否则诊断里只剩一句「命中 0 个候选」，等于又回到了靠猜。
     */
    public static SearchStatus parseSearchStatus(String output) {
        if (output == null || output.isEmpty()) {
            return SearchStatus.NO_OUTPUT;
        }
        if (output.contains(MARK_NO_TIMEOUT)) {
            return SearchStatus.NO_TIMEOUT_TOOL;
        }
        if (output.contains(MARK_TIMEOUT)) {
            return SearchStatus.TIMEOUT;
        }
        return SearchStatus.OK;
    }

    /**
     * 一次搜索的完整结果：候选路径 + 结局 + 一句细节。
     *
     * <p>把「结局」和「路径」一起带出来，是为了让上层**既能用结果、又不会把失败吞掉** ——
     * 上一版只返回路径列表，于是「命令根本没跑」和「跑完没命中」在上层看来一模一样。
     */
    public static final class SearchOutcome {

        private final List<String> paths;
        private final SearchStatus status;
        private final String detail;

        public SearchOutcome(List<String> paths, SearchStatus status, String detail) {
            this.paths = (paths == null) ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(paths));
            this.status = (status == null) ? SearchStatus.NO_OUTPUT : status;
            this.detail = detail;
        }

        public List<String> getPaths() {
            return paths;
        }

        public SearchStatus getStatus() {
            return status;
        }

        public String getDetail() {
            return detail;
        }

        /** 供诊断展示的一行摘要（不静默：状态与细节都写出来）。 */
        public String describe(String prefix) {
            StringBuilder sb = new StringBuilder();
            sb.append(prefix).append("：状态=").append(status.label())
                    .append("，命中 ").append(paths.size()).append(" 个候选");
            if (detail != null && !detail.isEmpty()) {
                sb.append("（").append(detail).append("）");
            }
            return sb.toString();
        }
    }

    /**
     * 搜索命中是否**可信**：文件名逐字符相符 **且** 大小逐字节相同。
     *
     * <p>大小是这条策略的命门：同名文件太常见（`1.jpg`、`report.pdf`…），只按名字匹配
     * 几乎一定会把用户带错文件 —— 而「带错文件」比「不定位」更糟。所以：
     * <ul>
     *   <li>目标大小未知（{@code < 0}）→ 不可信（无从确认）；</li>
     *   <li>实际大小对不上 → 不可信；</li>
     *   <li>文件名（末段）与显示名逐字符相同 → 才可信（大小写敏感，见
     *       {@link #matchesDisplayName}）。</li>
     * </ul>
     * 这条判据只在调用方已用**真实身份**（{@code FileRepository.listDir}）核过可见性、
     * 并给出该文件的真实大小时才有意义 —— 「搜到了」本身不算数。
     */
    public static boolean matchesSearchHit(String displayName, String candidatePath,
                                           long expectedSize, long actualSize) {
        if (expectedSize < 0L) {
            return false;
        }
        if (actualSize != expectedSize) {
            return false;
        }
        return matchesDisplayName(displayName, candidatePath);
    }

    // ------------------------------------------------------------ 定向目录查找

    /**
     * 「定向目录查找」要直接检查的目录（相对外部存储根，按可能性排序）。
     *
     * <p>用户真机确认了 QQ 的下载目录是**固定、已知**的（MT 管理器直接给出了
     * `Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/`）。既然不用再「猜 URI」，
     * 就把这些**已知落点直接列出来**按名字 + 大小找 —— 这比全盘 `find` **更快**（只扫
     * 一两个目录）、**更可靠**（不依赖 `timeout` / `find` 是否存在，用的是与可见性核对
     * 同一条 `FileRepository.listDir` 通道）。
     *
     * <p>前两个是 QQ 的接收目录（新路径在前），第三个是微信的下载目录 —— 都是
     * 「外部应用收到文件的固定落点」。目录不存在时 `listDir` 自然给出空列表，
     * 只会在诊断里记一笔，不会报错。匹配仍是**精确**的（名字逐字符 + 大小逐字节），
     * 目录多列几个不等于放宽判据。
     */
    public static final List<String> DIRECTED_LOOKUP_SUBDIRS =
            Collections.unmodifiableList(Arrays.asList(
                    "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv",
                    "Tencent/QQfile_recv",
                    "Tencent/MicroMsg/Download"));

    /**
     * 把 {@link #DIRECTED_LOOKUP_SUBDIRS} 拼到外部存储根下，得到定向查找的绝对目录（按顺序）。
     *
     * <p>根目录结尾的斜杠先剥掉（与 {@link #knownSearchDirs} / {@link #probeDirs} 同一口径），
     * 根为空时返回空表 —— 宁可什么都不查，也不查一个错的地方。
     */
    public static List<String> directedLookupDirs(String externalRoot) {
        List<String> out = new ArrayList<String>();
        if (externalRoot == null || externalRoot.isEmpty()) {
            return out;
        }
        String root = externalRoot;
        while (root.length() > 1 && root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        for (String sub : DIRECTED_LOOKUP_SUBDIRS) {
            out.add(root + "/" + sub);
        }
        return out;
    }

    /**
     * 定向目录里列出的某个条目是否**命中**：名字逐字符相同 **且** 大小逐字节相同。
     *
     * <p>与 {@link #matchesSearchHit} 同一套「宁严勿松」，只是输入换成「目录项的名字与大小」
     * —— 它已经来自 `FileRepository.listDir` 按**真实身份**列出的结果，不再是一条待复核的路径。
     * 大小仍是命门：目录里同名不同文件很常见，目标大小未知（{@code < 0}）或对不上都判不中。
     *
     * @param displayName  provider 给的显示名（取末段比对）
     * @param entryName    目录项的文件名（来自 listDir）
     * @param expectedSize 目标大小（字节）；负值 = 未知 → 不命中
     * @param entrySize    目录项的真实大小（来自 listDir）
     */
    public static boolean matchesDirectedHit(String displayName, String entryName,
                                             long expectedSize, long entrySize) {
        if (expectedSize < 0L) {
            return false;
        }
        if (entrySize != expectedSize) {
            return false;
        }
        String want = lastSegment(displayName);
        if (want.isEmpty() || entryName == null || entryName.isEmpty()) {
            return false;
        }
        return want.equals(entryName);
    }

    // ------------------------------------------------------------ 目标目录可访问性实测

    /**
     * 「目标目录可访问性实测」要逐个试的目录（相对外部存储根，按顺序）。
     *
     * <p>用户真机反馈给出了一条关键信息：**MT 管理器这类工具不借助 adb / root 也能进
     * `Android/data`**。这推翻了「Android 11+ 根本读不到别的 App 的 `Android/data`」这个
     * 假设 —— 目标很可能是可达的，只是我们走的路不对。所以诊断不能只记「候选路径是什么」，
     * 还要**实测我们对这些目录到底能不能列出**，并**分通道**记录（Java 的 `File.listFiles`
     * vs shell 的 `ls`）：两者结论若不一致，答案往往就藏在那条差异里。
     *
     * <p>末尾放一个几乎必然可列出的 `Download` 作**对照组** —— 若它都失败，说明是通道本身
     * 或身份的问题，而不是 `Android/data` 的特例。
     */
    public static final List<String> PROBE_SUBDIRS =
            Collections.unmodifiableList(Arrays.asList(
                    "Android/data",
                    "Android/data/com.tencent.mobileqq",
                    "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv",
                    "Tencent",
                    "Download"));

    /** 把 {@link #PROBE_SUBDIRS} 拼到外部存储根下，得到实测要用的绝对路径（按顺序）。 */
    public static List<String> probeDirs(String externalRoot) {
        List<String> out = new ArrayList<String>();
        if (externalRoot == null || externalRoot.isEmpty()) {
            return out;
        }
        String root = externalRoot;
        while (root.length() > 1 && root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        for (String sub : PROBE_SUBDIRS) {
            out.add(root + "/" + sub);
        }
        return out;
    }

    /**
     * 一次「列目录」测试的结果：成不成、能列出几项、失败原因。
     *
     * <p>判据/文本都在纯 Java 层（本类），真正去列目录的动作按身份分别在 Android 侧
     * （Java 的 `File.listFiles` 与 shell 的 `ls`）—— 这样这一节的渲染能离线断言。
     */
    public static final class ListProbe {

        private final boolean ok;
        private final int count;
        private final String error;

        public ListProbe(boolean ok, int count, String error) {
            this.ok = ok;
            this.count = count;
            this.error = error;
        }

        public boolean isOk() {
            return ok;
        }

        public int getCount() {
            return count;
        }

        public String getError() {
            return error;
        }

        /** 一行可读描述：成功报条数，失败报原因（原因缺失也明说，不留白）。 */
        public String describe() {
            if (ok) {
                return "可列出 " + count + " 项";
            }
            return "失败（" + ((error == null || error.isEmpty()) ? "原因未知" : error) + "）";
        }
    }

    /**
     * 一个目录的「双通道实测」结果：Java 通道 + shell 通道。
     *
     * <p>两者是否一致本身就是最有价值的信号之一：**Java 列不出但 shell 能列出**说明
     * 该目录在 shell 身份下可达 —— 那么修法就是「改走 shell 通道」，而不是去动访问策略。
     */
    public static final class DirProbe {

        private final String path;
        private final ListProbe javaProbe;
        private final ListProbe shellProbe;

        public DirProbe(String path, ListProbe javaProbe, ListProbe shellProbe) {
            this.path = path;
            this.javaProbe = javaProbe;
            this.shellProbe = shellProbe;
        }

        public String getPath() {
            return path;
        }

        public ListProbe getJava() {
            return javaProbe;
        }

        public ListProbe getShell() {
            return shellProbe;
        }
    }

    /**
     * 渲染「目标目录可访问性实测」一节（多行，每条目录含 Java / shell / 结论三行）。
     *
     * <p>结论只陈述**事实**：一致 / 不一致，以及不一致时是谁能谁不能。这里不替用户下结论，
     * 但会把「Java 列不出、shell 能列出 → 访问应改走 shell」这句**明确的指向**写出来 ——
     * 因为那恰好是最可能的一种事实。
     */
    public static String formatAccessReport(List<DirProbe> probes) {
        if (probes == null || probes.isEmpty()) {
            return " · （未测）\n";
        }
        StringBuilder sb = new StringBuilder();
        for (DirProbe p : probes) {
            sb.append(" · ").append(p.getPath()).append('\n');
            sb.append("     Java : ").append(p.getJava().describe()).append('\n');
            sb.append("     shell: ").append(p.getShell().describe()).append('\n');
            sb.append("     结论 : ").append(agreementText(p)).append('\n');
        }
        return sb.toString();
    }

    private static String agreementText(DirProbe p) {
        boolean j = p.getJava().isOk();
        boolean s = p.getShell().isOk();
        if (j && s) {
            return "一致（两通道都能列出）";
        }
        if (!j && !s) {
            return "一致（两通道都列不出）";
        }
        if (!j && s) {
            return "**不一致**：Java 列不出、shell 能列出（访问应改走 shell 通道）";
        }
        return "**不一致**：Java 能列出、shell 列不出";
    }

    // ------------------------------------------------------------ 诊断输出

    /**
     * 定位失败时要交代给用户的全部事实（纯数据，渲染见 {@link #formatDiagnostics}）。
     *
     * <p>这里之所以把「本可以现拼的字符串」也收拢成一个类，是因为**「定位不了」这条
     * 路最怕信息缺一块**：缺了 `_data` 列、缺了 authority、缺了当前身份，下一轮就还是
     * 靠猜。字段固定下来，既方便渲染，也方便断言「这些字段一定都被带出来了」。
     */
    public static final class Diagnostics {

        private final String uriText;
        private final String scheme;
        private final String authority;
        private final String path;
        private final List<String> segments;
        private final String displayName;
        private final long size;
        private final String mimeType;
        private final String shellLevel;
        private final List<String> attempts;
        private final List<String> searchNotes;
        private final String accessReport;
        private final String copyResult;

        /**
         * @param uriText    {@code intent.data} 的 URI 原文（不解码、不裁剪）
         * @param scheme     URI scheme（content / file …）
         * @param authority  URI authority（provider 名）
         * @param path       URI path 原文
         * @param segments   URI path 各段（已由 Uri 解码）
         * @param displayName provider 给的显示名；拿不到时传 null / 空
         * @param size       provider 给的大小（字节）；拿不到时传负值
         * @param mimeType   {@code contentResolver.getType(uri)}；拿不到时传 null / 空
         * @param shellLevel 当前 shell 身份的展示标签（ROOT / ADB / 普通）
         * @param attempts   反解候选逐条 + 各自被拒的原因
         * @param searchNotes 搜索策略的过程记录（搜了哪些目录、命中几个候选、结局如何）
         * @param accessReport 目标目录可访问性实测（见 {@link #formatAccessReport}）
         * @param copyResult 复制兜底的结果（成功落哪里 / 也失败）
         */
        public Diagnostics(String uriText, String scheme, String authority, String path,
                           List<String> segments, String displayName, long size,
                           String mimeType, String shellLevel,
                           List<String> attempts, List<String> searchNotes,
                           String accessReport, String copyResult) {
            this.uriText = uriText;
            this.scheme = scheme;
            this.authority = authority;
            this.path = path;
            this.segments = freeze(segments);
            this.displayName = displayName;
            this.size = size;
            this.mimeType = mimeType;
            this.shellLevel = shellLevel;
            this.attempts = freeze(attempts);
            this.searchNotes = freeze(searchNotes);
            this.accessReport = accessReport;
            this.copyResult = copyResult;
        }

        public String getUriText() {
            return uriText;
        }

        public String getScheme() {
            return scheme;
        }

        public String getAuthority() {
            return authority;
        }

        public String getPath() {
            return path;
        }

        public List<String> getSegments() {
            return segments;
        }

        public String getDisplayName() {
            return displayName;
        }

        public long getSize() {
            return size;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getShellLevel() {
            return shellLevel;
        }

        public List<String> getAttempts() {
            return attempts;
        }

        public List<String> getSearchNotes() {
            return searchNotes;
        }

        public String getAccessReport() {
            return accessReport;
        }

        public String getCopyResult() {
            return copyResult;
        }

        private static List<String> freeze(List<String> src) {
            return src == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(src));
        }
    }

    /**
     * 把 {@link Diagnostics} 渲染成一段可直接贴进日志 / 剪贴板的诊断文本。
     *
     * <p>多行、字段带标签，**信息完整地落在文本里** —— 用户复现一次、把这段拷回来，
     * 就能回答「QQ 到底给了什么 URI」这个在开发机上无法验证的问题。
     * 未知项统一写成「（未知）」而不是留空，免得复制回来以后分不清「没有」和「漏了」。
     */
    public static String formatDiagnostics(Diagnostics d) {
        if (d == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("URI原文: ").append(orUnknown(d.getUriText())).append('\n');
        sb.append("scheme: ").append(orUnknown(d.getScheme())).append('\n');
        sb.append("authority: ").append(orUnknown(d.getAuthority())).append('\n');
        sb.append("path: ").append(orUnknown(d.getPath())).append('\n');
        sb.append("path分段: ").append(joinOrNone(d.getSegments(), " | ")).append('\n');
        sb.append("DISPLAY_NAME: ").append(orUnknown(d.getDisplayName())).append('\n');
        sb.append("SIZE: ").append(d.getSize() < 0L ? "未知" : Long.toString(d.getSize())).append('\n');
        sb.append("getType: ").append(orUnknown(d.getMimeType())).append('\n');
        sb.append("Shell身份: ").append(orUnknown(d.getShellLevel())).append('\n');
        sb.append("— 反解尝试 —\n");
        appendBullets(sb, d.getAttempts());
        sb.append("— 定向查找 / 搜索 —\n");
        appendBullets(sb, d.getSearchNotes());
        sb.append("— 目标目录可访问性实测（Java vs shell 双通道）—\n");
        sb.append((d.getAccessReport() == null || d.getAccessReport().isEmpty())
                ? " · （未测）\n" : d.getAccessReport());
        sb.append("— 兜底 —\n");
        sb.append(orUnknown(d.getCopyResult())).append('\n');
        return sb.toString();
    }

    private static void appendBullets(StringBuilder sb, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            sb.append(" · （无）\n");
            return;
        }
        for (String line : lines) {
            sb.append(" · ").append(line).append('\n');
        }
    }

    private static String joinOrNone(List<String> items, String sep) {
        if (items == null || items.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String orUnknown(String value) {
        return (value == null || value.isEmpty()) ? "（未知）" : value;
    }
}
