package com.rxinfiles.tool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@link IncomingFile}（外部「用其他应用打开」的判据）的断言。
 *
 * 为什么这个模块必须有离线断言：它处理的是**对方应用可控的输入** —— 一个显示名
 * 加一个 URI。这里出错不会抛异常：文件名消毒漏一个字节就是**路径穿越**（对方决定
 * 我们往哪写）；反解出的路径名对不上就是把用户**带去另一个文件**；类型判错就是
 * 图标/描述错位。三件事在真机上都极难复现，所以判据必须能一条命令跑完。
 *
 * <p>本入口已从「按类型分派查看器」改为「定位 + 高亮」，所以原先测
 * {@code decide}/{@code targetFromMime} 的两节（MIME → 目标、分派决策）随函数一并删除
 * —— 不留测已删函数的死断言；腾出的位置补上本轮最要紧的一节：**反解路径的可信度**。
 *
 * 覆盖十九节：
 *   1. 文件名消毒（路径穿越 / 控制字符 / 空名 / 过长）
 *   2. scheme 白名单
 *   3. 扩展名口径
 *   4. 分类判据
 *   5. 反解路径可信度（{@code matchesDisplayName} / {@code lastSegment}）—— 宁严勿松
 *   6. 内容嗅探
 *   7. 归档识别与 {@link Archives} 逐条自洽（只此一份真源）
 *   8. 归档识别边界：点开头的单扩展名（{@code .zip} / {@code .tar} …）
 *   9. 兜底复制不覆盖已有文件（{@code uniqueName}：{@code (n)} 递增 + 扩展名留在最后）
 *  10. shell 转义（{@code quoteShell}）—— 搜索命令的防注入底线
 *  11. 搜索目录计划（{@code knownSearchDirs}）
 *  12. 搜索命令构造（{@code buildSearchCommand}）—— 限时 + 转义 + 精确匹配 + 超时工具自选
 *  13. 搜索输出解析（{@code parseSearchOutput}）—— 只认绝对路径、去重、滤噪声
 *  14. 搜索命中判据（{@code matchesSearchHit}）—— 名字逐字符 + 大小逐字节
 *  15. 诊断输出（{@code formatDiagnostics}）—— 定位失败时必须把哪些事实带出来
 *  16. 搜索结局（{@code parseSearchStatus} / {@code SearchOutcome}）—— 三种失败必须分得清
 *  17. 目标目录可访问性实测（{@code probeDirs} / {@code formatAccessReport}）—— Java vs shell 双通道
 *  18. 定向目录查找（{@code directedLookupDirs} / {@code matchesDirectedHit}）—— 已知落点按名+大小
 *  19. 浏览 Android/data 的「无权限」提醒（{@code isUnderAndroidData} /
 *      {@code shouldWarnNoAccess}）—— 三条件同时成立，**空目录不许误报**
 */
public final class IncomingCheck {

    private static int total = 0;
    private static int failures = 0;

    private IncomingCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("rxin-incoming-");
        try {
            section("1. 文件名消毒：路径穿越 / 控制字符 / 空名 / 过长");
            sanitizeChecks();

            section("2. scheme 白名单：只认 content 与 file");
            schemeChecks();

            section("3. 扩展名口径");
            extChecks();

            section("4. 分类判据");
            classifyChecks();

            section("5. 反解路径可信度：名字必须与显示名逐字符相符（宁严勿松）");
            pathTrustChecks();

            section("6. 内容嗅探");
            sniffChecks(tmp);

            section("7. 归档识别与 Archives 逐条自洽（唯一真源）");
            archiveDelegationChecks();

            section("8. 归档识别边界：点开头的单扩展名（.zip / .tar / .gz …）");
            archiveDotNameChecks();

            section("9. 兜底复制不覆盖已有文件（uniqueName：(n) 递增 + 扩展名留在最后）");
            uniqueNameChecks(tmp);

            section("10. shell 转义：单引号包裹 + 内部单引号写成 '\\''（防命令注入）");
            quoteShellChecks();

            section("11. 搜索目录计划：已知 QQ/下载目录拼到外部存储根");
            searchDirsChecks();

            section("12. 搜索命令构造：限时 + 转义 + -name/-size 精确匹配");
            searchCommandChecks();

            section("13. 搜索输出解析：只认绝对路径、去重、滤掉 shell 噪声");
            parseSearchOutputChecks();

            section("14. 搜索命中判据：名字逐字符 + 大小逐字节（缺一不可）");
            searchHitChecks();

            section("15. 诊断输出：URI 原文 / 各段 / 元信息 / 身份 / 逐条候选 / 可访问性实测");
            diagnosticsChecks();

            section("16. 搜索结局：正常 / 超时 / 无 timeout 工具 / 无输出（三种失败必须分得清）");
            searchStatusChecks();

            section("17. 目标目录可访问性实测：Java vs shell 双通道 + 一致性结论");
            accessReportChecks();

            section("18. 定向目录查找：已知 QQ/微信落点按名+大小精确匹配");
            directedLookupChecks();

            section("19. 浏览 Android/data 的「无权限」提醒：三条件同时成立 + 空目录不许误报");
            browserNoAccessChecks();
        } finally {
            deleteTree(tmp.toFile());
        }

        System.out.println();
        System.out.printf("结果: %d 项通过, %d 项失败%n", total - failures, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 1

    private static void sanitizeChecks() {
        String d = IncomingFile.DEFAULT_NAME;

        // 复制兜底的专属子目录名（3.15.2）。名字本身是给用户看的，测试锁住它别被顺手改。
        checkEq("复制兜底子目录名固定为 RxinDownload",
                "RxinDownload", IncomingFile.COPY_DIR_NAME);
        check("复制兜底子目录名不含路径分隔符（否则会脱离 Download/）",
                !IncomingFile.COPY_DIR_NAME.contains("/")
                        && !IncomingFile.COPY_DIR_NAME.contains("\\"));
        check("复制兜底子目录名不是 . 或 ..",
                !IncomingFile.COPY_DIR_NAME.equals(".")
                        && !IncomingFile.COPY_DIR_NAME.equals(".."));

        // 「去装 Shizuku」提示（3.15.2）：只在**没有 shell 通道**时才该出现。
        // 这是按身份分流的一处判据 —— 已有 ROOT/ADB 的用户失败原因在别处，
        // 再劝他装 Shizuku 是误导。
        check("普通身份 → 该提示装 Shizuku",
                IncomingFile.needsShellHint(IncomingFile.NORMAL_LEVEL_LABEL));
        check("普通身份标签就是「普通」", "普通".equals(IncomingFile.NORMAL_LEVEL_LABEL));
        check("ROOT 身份 → 不提示（失败原因在别处，提示会误导）",
                !IncomingFile.needsShellHint("ROOT"));
        check("ADB 身份 → 不提示（同上）",
                !IncomingFile.needsShellHint("ADB"));
        check("身份未知（null）→ 不提示（先探身份，而不是先劝装东西）",
                !IncomingFile.needsShellHint(null));
        check("身份为空串 → 不提示", !IncomingFile.needsShellHint(""));
        check("下载页是 https 地址",
                IncomingFile.SHIZUKU_DOWNLOAD_URL.startsWith("https://"));
        check("下载页指向 shizuku.rikka.app",
                IncomingFile.SHIZUKU_DOWNLOAD_URL.contains("shizuku.rikka.app"));
        check("提示正文非空", !IncomingFile.SHIZUKU_HINT_TEXT.isEmpty());

        // 分隔符：只取最后一段
        checkEq("a/b/evil.sh → evil.sh", "evil.sh", IncomingFile.safeName("a/b/evil.sh"));
        checkEq("反斜杠也当分隔符", "evil.sh", IncomingFile.safeName("a\\b\\evil.sh"));
        checkEq("../../etc/passwd → passwd", "passwd", IncomingFile.safeName("../../etc/passwd"));
        checkEq("..\\..\\x → x", "x", IncomingFile.safeName("..\\..\\x"));
        checkEq("/absolute/x.png → x.png", "x.png", IncomingFile.safeName("/absolute/x.png"));

        // 纯点：当前目录 / 上一层，都不能当文件名
        checkEq(".. → 默认名", d, IncomingFile.safeName(".."));
        checkEq(". → 默认名", d, IncomingFile.safeName("."));
        checkEq("... → 默认名", d, IncomingFile.safeName("..."));
        checkEq("空串 → 默认名", d, IncomingFile.safeName(""));
        checkEq("null → 默认名", d, IncomingFile.safeName(null));
        checkEq("纯空白 → 默认名", d, IncomingFile.safeName("   "));
        checkEq("纯斜杠 → 默认名", d, IncomingFile.safeName("////"));
        checkEq("单个反斜杠 → 默认名", d, IncomingFile.safeName("\\"));

        // 控制字符与 NUL
        checkEq("NUL / 控制字符被丢掉", "abc.txt", IncomingFile.safeName("a\u0000b\u0001c.txt"));
        checkEq("换行 / 制表也被丢掉", "abc", IncomingFile.safeName("a\nb\tc"));
        checkEq("DEL(0x7F) 被丢掉", "ab", IncomingFile.safeName("a\u007Fb"));

        // 保留正常名字（含点开头、点结尾、扩展名）
        checkEq("普通名原样", "notes.txt", IncomingFile.safeName("notes.txt"));
        checkEq("隐藏文件保留点开头", ".bashrc", IncomingFile.safeName(".bashrc"));
        checkEq("内部点不动", "archive.tar.gz", IncomingFile.safeName("archive.tar.gz"));
        checkEq("末尾点不误删（它不是纯点名）", "foo.", IncomingFile.safeName("foo."));

        // 自定义兜底名
        checkEq("空串用自定义兜底名", "mine", IncomingFile.safeName("", "mine"));

        // 过长截断且保留扩展名
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longName.append('z');
        }
        longName.append(".tar.gz");
        String clamped = IncomingFile.safeName(longName.toString());
        check("超长名被截断到上限内", clamped.length() <= IncomingFile.MAX_NAME_CHARS);
        check("超长名截断后仍保留扩展名 .tar.gz", clamped.endsWith(".tar.gz"));

        // 归纳：任何敌意输入消毒后都不含分隔符与 NUL，且不是纯点
        String[] hostile = {
                "../../../etc/passwd", "..\\..\\windows", "a/b", "\u0000", "..", ".",
                "/", "\\", "....", "a//b//c", "\u0001\u0002\u0003", "con\u0000.txt"
        };
        boolean clean = true;
        String firstBad = "";
        for (String h : hostile) {
            String out = IncomingFile.safeName(h);
            if (out.isEmpty()
                    || out.indexOf('/') >= 0
                    || out.indexOf('\\') >= 0
                    || out.indexOf('\u0000') >= 0
                    || allDots(out)) {
                clean = false;
                firstBad = h + " -> " + out;
                break;
            }
        }
        check("敌意输入批量消毒后都不含分隔符/NUL、也不是纯点"
                + (clean ? "" : "  第一个不合格: " + firstBad), clean);
    }

    private static boolean allDots(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '.') {
                return false;
            }
        }
        return !s.isEmpty();
    }

    // ================================================================ 2

    private static void schemeChecks() {
        check("content 接受", IncomingFile.isHandledScheme("content"));
        check("CONTENT 大小写不敏感", IncomingFile.isHandledScheme("CONTENT"));
        check("file 接受", IncomingFile.isHandledScheme("file"));
        check("File 大小写不敏感", IncomingFile.isHandledScheme("File"));
        check("http 拒绝", !IncomingFile.isHandledScheme("http"));
        check("https 拒绝", !IncomingFile.isHandledScheme("https"));
        check("files 拒绝（不是 file）", !IncomingFile.isHandledScheme("files"));
        check("空串拒绝", !IncomingFile.isHandledScheme(""));
        check("null 拒绝", !IncomingFile.isHandledScheme(null));
        check("私有 scheme 拒绝", !IncomingFile.isHandledScheme("myapp"));
    }

    // ================================================================ 3

    private static void extChecks() {
        checkEq("a.txt → txt", "txt", IncomingFile.extOf("a.txt"));
        checkEq("A.TXT → txt（小写）", "txt", IncomingFile.extOf("A.TXT"));
        checkEq("a.b.c → c", "c", IncomingFile.extOf("a.b.c"));
        checkEq("无点 → 空", "", IncomingFile.extOf("noext"));
        checkEq("点开头 → 空", "", IncomingFile.extOf(".bashrc"));
        checkEq("点结尾 → 空", "", IncomingFile.extOf("foo."));
        checkEq("null → 空", "", IncomingFile.extOf(null));
    }

    // ================================================================ 4

    private static void classifyChecks() {
        check("isImageName 大小写不敏感", IncomingFile.isImageName("x.PNG"));
        check("isImageName 不认中间扩展名", !IncomingFile.isImageName("x.png.tmp"));
        check("isImageName 非图片为假", !IncomingFile.isImageName("x.txt"));

        check("isTextName 认扩展名", IncomingFile.isTextName("a.txt"));
        check("isTextName 认无扩展名的 README", IncomingFile.isTextName("README"));
        check("isTextName 认隐藏文件", IncomingFile.isTextName(".bashrc"));
        check("isTextName 不认 png", !IncomingFile.isTextName("a.png"));
        check("isTextName 不把 .. 当文本", !IncomingFile.isTextName("..."));

        check("isArchiveName 认 zip", IncomingFile.isArchiveName("a.zip"));
        check("isArchiveName 认 tar.gz（复合后缀）", IncomingFile.isArchiveName("a.tar.gz"));
        check("isArchiveName 认 tgz", IncomingFile.isArchiveName("a.tgz"));
        check("isArchiveName 认 gz", IncomingFile.isArchiveName("a.gz"));
        check("isArchiveName 认 7z", IncomingFile.isArchiveName("a.7z"));
        check("isArchiveName 认 rar", IncomingFile.isArchiveName("a.rar"));
        check("isArchiveName 认 apk", IncomingFile.isArchiveName("a.apk"));
        check("isArchiveName 大小写不敏感", IncomingFile.isArchiveName("A.ZIP"));
        check("isArchiveName 不认 txt", !IncomingFile.isArchiveName("a.txt"));

        check("isStrictScriptName 认 sh", IncomingFile.isStrictScriptName("a.sh"));
        check("isStrictScriptName 大小写不敏感", IncomingFile.isStrictScriptName("A.SH"));
        check("isStrictScriptName 认 .shinit", IncomingFile.isStrictScriptName(".shinit"));
        check("isStrictScriptName 认 x.shinit", IncomingFile.isStrictScriptName("x.shinit"));
        check("isStrictScriptName 不认 py", !IncomingFile.isStrictScriptName("a.py"));

        check("isAmbiguousScriptName 认 py", IncomingFile.isAmbiguousScriptName("a.py"));
        check("isAmbiguousScriptName 认 mjs", IncomingFile.isAmbiguousScriptName("a.mjs"));
        check("isAmbiguousScriptName 大小写不敏感", IncomingFile.isAmbiguousScriptName("a.JS"));
        check("isAmbiguousScriptName 不认 sh", !IncomingFile.isAmbiguousScriptName("a.sh"));

        check("isScriptName 认 sh", IncomingFile.isScriptName("a.sh"));
        check("isScriptName 认 py", IncomingFile.isScriptName("a.py"));
        check("isScriptName 认 .shinit", IncomingFile.isScriptName(".shinit"));
        check("isScriptName 旧口径：名字正好是 .sh 也算", IncomingFile.isScriptName(".sh"));
        check("isScriptName 不认 txt", !IncomingFile.isScriptName("a.txt"));

        String[] videos = {"mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "m4v"};
        for (String ext : videos) {
            check("isVideoName 认 " + ext, IncomingFile.isVideoName("a." + ext));
            check("isVideoName 大小写不敏感：" + ext,
                    IncomingFile.isVideoName("a." + ext.toUpperCase(Locale.US)));
        }
        check("VIDEO_EXT 恰好是 8 种支持的视频", IncomingFile.VIDEO_EXT.size() == videos.length);
        check(".ts 仍是 TypeScript 文本", IncomingFile.isTextName("a.ts"));
        check(".ts 不算视频", !IncomingFile.isVideoName("a.ts"));
        check(".ts 不算媒体", !IncomingFile.isMediaName("a.ts"));
        check("视频不再混入媒体分类", !IncomingFile.isMediaName("a.mp4"));
        check("isVideoName 不认 txt", !IncomingFile.isVideoName("a.txt"));
        check("isMediaName 认 mp3", IncomingFile.isMediaName("a.mp3"));
        check("isMediaName 不认 txt", !IncomingFile.isMediaName("a.txt"));
    }

    // ================================================================ 5

    /**
     * 反解路径可信度 —— 本轮改造后**最要紧**的一节。
     *
     * <p>把 {@code content://} 反解成真实路径，本质是**猜**。猜错的后果不是「打开失败」，
     * 而是**把用户带去了另一个文件**：他以为看到的是刚点的那一个，其实是同目录下的
     * 另一个近名文件 —— 比不定位更糟。判据的口径因此是**宁严勿松**：显示名拿不到、
     * 末段对不上、候选路径为空/结尾是分隔符，任何一条不满足都判**不可信**，
     * 交给调用方走复制兜底。
     *
     * <p>这一节同时钉住 {@code matchesDisplayName} 与它依赖的 {@code lastSegment}。
     */
    private static void pathTrustChecks() {
        // lastSegment：`/` 与 `\` 都算分隔符；结尾带分隔符时末段为空
        checkEq("lastSegment：取 / 后一段", "b.txt", IncomingFile.lastSegment("a/b.txt"));
        checkEq("lastSegment：取 \\ 后一段", "b.txt", IncomingFile.lastSegment("a\\b.txt"));
        checkEq("lastSegment：无分隔符原样", "b.txt", IncomingFile.lastSegment("b.txt"));
        checkEq("lastSegment：结尾分隔符 → 空", "", IncomingFile.lastSegment("a/b/"));
        checkEq("lastSegment：null → 空", "", IncomingFile.lastSegment(null));

        // 正例：末段逐字符相同 → 可信
        check("名字相符 → 可信",
                IncomingFile.matchesDisplayName("photo.png", "/sdcard/Pictures/photo.png"));
        check("显示名带整条路径时也取末段比对",
                IncomingFile.matchesDisplayName("/qq/tmp/photo.png", "/sdcard/Pictures/photo.png"));
        check("候选路径带反斜杠也认末段",
                IncomingFile.matchesDisplayName("photo.png", "C:\\x\\photo.png"));

        // 负例：名字不符 —— 这条是「宁可判失败，不猜一个像的路径」的落点
        check("名字不符（photo_1）→ 不可信",
                !IncomingFile.matchesDisplayName("photo.png", "/sdcard/Pictures/photo_1.png"));
        check("名字不符（不同扩展名）→ 不可信",
                !IncomingFile.matchesDisplayName("photo.png", "/sdcard/Pictures/photo.jpg"));
        check("大小写不同 → 不可信（类 Unix 区分大小写）",
                !IncomingFile.matchesDisplayName("Photo.PNG", "/sdcard/Pictures/photo.png"));

        // 负例：拿不到显示名就等于无从核对 → 不可信（不能默认放行）
        check("显示名空串 → 不可信", !IncomingFile.matchesDisplayName("", "/sdcard/photo.png"));
        check("显示名 null → 不可信", !IncomingFile.matchesDisplayName(null, "/sdcard/photo.png"));
        check("显示名是纯路径分隔符 → 不可信",
                !IncomingFile.matchesDisplayName("/", "/sdcard/Pictures/photo.png"));

        // 负例：候选路径本身不合格 → 不可信
        check("候选路径空串 → 不可信", !IncomingFile.matchesDisplayName("photo.png", ""));
        check("候选路径 null → 不可信", !IncomingFile.matchesDisplayName("photo.png", null));
        check("候选路径结尾是分隔符（末段为空）→ 不可信",
                !IncomingFile.matchesDisplayName("photo.png", "/sdcard/Pictures/"));

        // 归纳：同目录下的「近名文件」没有一个能被错认（改回宽松口径必红）
        String want = "report.pdf";
        String[] nearMisses = {
                "report (1).pdf", "report_1.pdf", "report.pdf.bak", "Report.pdf",
                "report.pdf ", " report.pdf", "reportp.pdf", "report.pdf"
        };
        int accepted = 0;
        boolean onlyExactAccepted = true;
        for (int i = 0; i < nearMisses.length; i++) {
            boolean got = IncomingFile.matchesDisplayName(want, "/sdcard/Download/" + nearMisses[i]);
            boolean shouldAccept = nearMisses[i].equals(want);
            if (got) {
                accepted++;
            }
            if (got != shouldAccept) {
                onlyExactAccepted = false;
            }
        }
        check("近名文件里只有「逐字符完全相同」的那个被接受（接受 " + accepted + " 个）",
                onlyExactAccepted && accepted == 1);
    }

    // ================================================================ 7

    private static void sniffChecks(Path tmp) throws Exception {
        File text = write(tmp, "t.txt", "hello world\nsecond line\n".getBytes(StandardCharsets.UTF_8));
        check("纯文本 → 像文本", IncomingFile.looksLikeText(text));

        byte[] withNul = new byte[]{'a', 'b', 0, 'c', 'd'};
        File binary = write(tmp, "b.bin", withNul);
        check("含 NUL → 不像文本", !IncomingFile.looksLikeText(binary));

        File empty = write(tmp, "empty", new byte[0]);
        check("空文件 → 不像文本", !IncomingFile.looksLikeText(empty));

        File missing = new File(tmp.toFile(), "does-not-exist");
        check("文件不存在 → 不像文本", !IncomingFile.looksLikeText(missing));
        check("null → 不像文本", !IncomingFile.looksLikeText(null));

        byte[] controls = new byte[64];
        for (int i = 0; i < controls.length; i++) {
            controls[i] = 0x01; // 可打印占比为 0
        }
        File ctlFile = write(tmp, "ctl.bin", controls);
        check("满是控制字符 → 不像文本", !IncomingFile.looksLikeText(ctlFile));

        // 长文本（> 4KB）仍然判为文本
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            big.append("line ").append(i).append('\n');
        }
        File bigText = write(tmp, "big.txt", big.toString().getBytes(StandardCharsets.UTF_8));
        check("超过 4KB 的文本 → 像文本", IncomingFile.looksLikeText(bigText));
    }

    // ================================================================ 8

    /**
     * 归档识别只此一份真源：{@code IncomingFile.isArchiveName} 必须与 {@link Archives}
     * 逐条一致。
     *
     * <p>本类一度自己抄了一张归档扩展名表，结果因为两处 {@code extOf} 口径不同
     * （本类 {@code dot&lt;=0}、{@code Archives} {@code dot&lt;0||dot==len-1}），
     * {@code .zip} 这类名字两边给出相反答案。所以这里既钉「覆盖 Archives 认识的
     * 全部扩展名」，也钉「逐条一致」—— 谁再把口径改回自己的 {@code extOf}，立刻变红。
     *
     * <p>（本入口已不再按类型分派查看器，所以这里**只验证归档识别本身**，
     * 不再去比对已删除的 {@code decide}。）
     */
    private static void archiveDelegationChecks() {
        // 归档识别覆盖 Archives 认得的全部单/复合扩展名
        String[] archives = {"a.zip", "a.jar", "a.apk", "a.tar", "a.rar", "a.7z",
                "a.bz2", "a.xz", "a.txz", "a.lzma", "a.gz", "a.tgz", "a.tbz2",
                "a.tar.bz2", "a.tar.xz", "a.tar.lzma"};
        boolean covers = true;
        String miss = "";
        for (String a : archives) {
            if (!IncomingFile.isArchiveName(a)) {
                covers = false;
                miss = a;
                break;
            }
        }
        check("归档识别覆盖 Archives 的全部扩展名样本" + (covers ? "" : "  漏了 " + miss), covers);

        // 逐条与 Archives 对齐（防漂移：谁改回自己的 extOf，这里必红）
        String[] samples = {
                ".zip", ".tar", ".gz", ".rar", ".7z", ".bz2", ".xz", ".lzma",
                ".jar", ".apk", ".gzip", ".tbz2", ".tgz", ".tar.gz", ".tar.bz2",
                ".tar.xz", ".tar.lzma", ".txt", ".png", ".bashrc", "README",
                "a.zip", "a.tar.gz", "a.txt", "A.ZIP", "archive.zip", "noext", ""
        };
        boolean same = true;
        String diff = "";
        for (String n : samples) {
            if (IncomingFile.isArchiveName(n) != Archives.isArchiveName(n)) {
                same = false;
                diff = n;
                break;
            }
        }
        check("isArchiveName 与 Archives 逐条一致（共 " + samples.length + " 个样本）"
                + (same ? "" : "  首个不一致 " + diff), same);
    }

    // ================================================================ 9

    /**
     * 点开头 + 单扩展名的归档名（`.zip` / `.tar` / `.gz` …）。
     *
     * 这是上一轮真实回归的发生地：归档识别一度在本类自己的 {@code extOf} 上判，而
     * {@code extOf} 把点开头当隐藏文件、判为无扩展名 → `.zip` 掉进文本分支；
     * 既有链路走 {@link Archives}（`dot &lt; 0 || dot == len-1`，`.zip` 的 `dot==0`
     * 两条都不落）→ 判为归档。同一个文件两处结论相反。
     *
     * 所以这里从「点开头」这个角度再钉一遍：这些名字都必须被认成归档，
     * 且点开头的非归档名不能被误判。逐条与 {@code Archives} 的对齐在 §7 已覆盖。
     */
    private static void archiveDotNameChecks() {
        String[] dotArchives = {
                ".zip", ".tar", ".gz", ".rar", ".7z", ".bz2", ".xz", ".lzma",
                ".jar", ".apk", ".gzip", ".tbz2"
        };
        boolean all = true;
        String miss = "";
        for (String n : dotArchives) {
            if (!IncomingFile.isArchiveName(n)) {
                all = false;
                miss = n;
                break;
            }
        }
        check("点开头的单扩展名归档名都认（.zip/.tar/.gz…）" + (all ? "" : "  漏了 " + miss), all);

        // 点开头的「非归档」不该被误判成归档
        check("点开头的非归档名不算归档（.txt）", !IncomingFile.isArchiveName(".txt"));
        check("点开头的非归档名不算归档（.png）", !IncomingFile.isArchiveName(".png"));
    }

    // ================================================================ 9

    /**
     * 兜底复制不覆盖已有文件 —— {@code uniqueName}。
     *
     * <p>这段逻辑原先埋在 {@code IncomingActivity} 的私有方法里，脱离 Android 根本测不到
     * （等于没测）。落到纯 Java 层之后，这里用**真文件**验证：{@code (n)} 必须真的递增、
     * 扩展名必须留在最后。改坏任一处，这一节必红。
     */
    private static void uniqueNameChecks(Path tmp) throws Exception {
        // 空目录：原名返回
        File empty = Files.createDirectory(tmp.resolve("dl-empty")).toFile();
        checkEq("空目录：名字原样返回", "report.pdf", IncomingFile.uniqueName(empty, "report.pdf"));

        // 逐个占用，钉死 (n) 递增
        File dl = Files.createDirectory(tmp.resolve("dl")).toFile();
        write(dl, "report.pdf", "a");
        checkEq("已有 report.pdf → report (1).pdf", "report (1).pdf",
                IncomingFile.uniqueName(dl, "report.pdf"));
        write(dl, "report (1).pdf", "b");
        checkEq("再占一个 → report (2).pdf", "report (2).pdf",
                IncomingFile.uniqueName(dl, "report.pdf"));
        write(dl, "report (2).pdf", "c");
        checkEq("三份都在 → report (3).pdf", "report (3).pdf",
                IncomingFile.uniqueName(dl, "report.pdf"));

        // 扩展名必须留在最后（不能被括号挤成 `report.pdf (1)`）
        String uniq = IncomingFile.uniqueName(dl, "report.pdf");
        check("去重后仍以 .pdf 结尾（扩展名留在最后）  实际=" + uniq, uniq.endsWith(".pdf"));

        // 无扩展名
        File noextDir = Files.createDirectory(tmp.resolve("dl-noext")).toFile();
        write(noextDir, "noext", "x");
        checkEq("无扩展名：noext → noext (1)", "noext (1)",
                IncomingFile.uniqueName(noextDir, "noext"));

        // 复合后缀沿用既有切口（只按最后一个点切）—— 本次不改语义，只把它钉住
        File comp = Files.createDirectory(tmp.resolve("dl-comp")).toFile();
        write(comp, "report.tar.gz", "x");
        checkEq("复合后缀沿用既有切口：report.tar.gz → report.tar (1).gz",
                "report.tar (1).gz", IncomingFile.uniqueName(comp, "report.tar.gz"));

        // 点开头（dot==0 不算扩展名，原样当 stem）
        File dot = Files.createDirectory(tmp.resolve("dl-dot")).toFile();
        write(dot, ".bashrc", "x");
        checkEq("点开头：.bashrc → .bashrc (1)", ".bashrc (1)",
                IncomingFile.uniqueName(dot, ".bashrc"));

        // 归纳：连续占用 5 次，每次返回的名字都**不与已有文件重名**（(n) 真在递增）
        File seq = Files.createDirectory(tmp.resolve("dl-seq")).toFile();
        write(seq, "a.txt", "0");
        boolean distinct = true;
        String firstClash = "";
        for (int i = 0; i < 5; i++) {
            String got = IncomingFile.uniqueName(seq, "a.txt");
            if (new File(seq, got).exists()) {
                distinct = false;
                firstClash = got;
                break;
            }
            write(seq, got, "x");
        }
        check("连续 5 次返回的名字都不与已有文件重名（(n) 真在递增）"
                + (distinct ? "" : "  首个撞车 " + firstClash), distinct);

        File exhausted = Files.createDirectory(tmp.resolve("dl-exhausted")).toFile();
        write(exhausted, "full.txt", "0");
        for (int i = 1; i <= 999; i++) {
            write(exhausted, "full (" + i + ").txt", "x");
        }
        String after999 = IncomingFile.uniqueName(exhausted, "full.txt");
        checkEq("原名字及 (1) 至 (999) 都占用时继续找 (1000)",
                "full (1000).txt", after999);
        check("去重结果绝不返回已存在文件", !new File(exhausted, after999).exists());

        // 空名 / null 兜底
        checkEq("空名 → 默认名", IncomingFile.DEFAULT_NAME, IncomingFile.uniqueName(empty, ""));
        checkEq("null 名 → 默认名", IncomingFile.DEFAULT_NAME, IncomingFile.uniqueName(empty, null));
    }

    // ================================================================ 10

    /**
     * shell 转义（{@code quoteShell}）。
     *
     * <p>搜索策略会把**对方给的显示名**拼进 `find -name`，而这个入口是 exported 的 ——
     * 转义漏一个字节就是命令注入。这一节同时钉「与 ScriptRunner.quote 同口径」与
     * 「任意敌意串都可逆还原」。
     */
    private static void quoteShellChecks() {
        checkEq("普通名字原样加引号", "'a.txt'", IncomingFile.quoteShell("a.txt"));
        checkEq("含空格不被拆开", "'a b.txt'", IncomingFile.quoteShell("a b.txt"));
        checkEq("null → 空字面量", "''", IncomingFile.quoteShell(null));
        checkEq("空串 → 空字面量", "''", IncomingFile.quoteShell(""));

        // 单个单引号必须写成 '\''（闭合-转义-重开）
        checkEq("单个单引号被转义", "'a'\\''b'", IncomingFile.quoteShell("a'b"));
        checkEq("结尾单引号也不破", "'foo'\\'''", IncomingFile.quoteShell("foo'"));
        checkEq("分号在引号内不截断", "'x; rm -rf /'", IncomingFile.quoteShell("x; rm -rf /"));
        checkEq("$() 被引号锁住", "'$(id)'", IncomingFile.quoteShell("$(id)"));

        // 归纳：任意敌意串解开后都还原成原文（说明转义可逆、无残留）
        String[] hostile = {
                "a'b", "a; b", "$(id)", "`id`", "a\"b", "a\\b", "|", "&&", ">", "<", "\n", "'"
        };
        boolean roundTrip = true;
        String firstBad = "";
        for (String h : hostile) {
            String q = IncomingFile.quoteShell(h);
            if (q.length() < 2 || !q.startsWith("'") || !q.endsWith("'")) {
                roundTrip = false;
                firstBad = h + " -> " + q;
                break;
            }
            // 只要原文含单引号，输出里就必须出现转义序列 '\''（否则就是「没转义」）
            if (h.indexOf('\'') >= 0 && !q.contains("'\\''")) {
                roundTrip = false;
                firstBad = h + " -> 缺转义序列 " + q;
                break;
            }
            String inner = q.substring(1, q.length() - 1);
            String un = inner.replace("'\\''", "'");
            if (!un.equals(h)) {
                roundTrip = false;
                firstBad = h + " -> " + un;
                break;
            }
        }
        check("转义可逆：任意敌意串解开后都还原成原文"
                + (roundTrip ? "" : "  首个不合格 " + firstBad), roundTrip);
    }

    // ================================================================ 11

    /** 搜索目录计划：已知目录拼到外部存储根；根为空则不搜。 */
    private static void searchDirsChecks() {
        List<String> dirs = IncomingFile.knownSearchDirs("/storage/emulated/0");
        checkEq("已知目录个数 = KNOWN_SEARCH_SUBDIRS 的长度",
                IncomingFile.KNOWN_SEARCH_SUBDIRS.size(), dirs.size());
        check("包含 QQ 的 Android/data 目录",
                dirs.contains("/storage/emulated/0/Android/data/com.tencent.mobileqq"));
        check("包含 Tencent 目录", dirs.contains("/storage/emulated/0/Tencent"));
        check("包含 Download 目录", dirs.contains("/storage/emulated/0/Download"));
        check("包含 Documents 目录", dirs.contains("/storage/emulated/0/Documents"));

        // 根目录结尾的斜杠要先剥掉，否则会拼出 // 这种路径
        List<String> withSlash = IncomingFile.knownSearchDirs("/sdcard/");
        boolean noDouble = true;
        for (String d : withSlash) {
            if (d.contains("//")) {
                noDouble = false;
                break;
            }
        }
        check("根目录结尾斜杠被去掉（不出现 //）", noDouble);
        check("剥斜杠后仍是 /sdcard/Tencent", withSlash.contains("/sdcard/Tencent"));

        check("空根 → 空计划（宁可不搜，也不搜错地方）",
                IncomingFile.knownSearchDirs("").isEmpty());
        check("null 根 → 空计划", IncomingFile.knownSearchDirs(null).isEmpty());
    }

    // ================================================================ 12

    /**
     * 搜索命令构造：**优先内置 busybox 的 timeout**、限时、转义、按名 + 大小精确匹配、
     * 丢弃 stderr、**没有可用 timeout 就明确报出来**（不静默）。
     *
     * <p>上一版只用系统 `timeout`：某 ROM 没有它时，`timeout: not found` 会被 `2>/dev/null`
     * 吞掉，搜索静默退化成「0 候选」。这一版把内置 busybox 提到首位，并埋下两种状态标记
     * （{@link IncomingFile#MARK_NO_TIMEOUT} / {@link IncomingFile#MARK_TIMEOUT}）—— 本节钉住这些。
     */
    private static void searchCommandChecks() {
        List<String> dirs = Arrays.asList("/sdcard/Tencent", "/sdcard/Download");
        String busybox = "/data/app/com.rxinfiles.tool/lib/busybox";
        String cmd = IncomingFile.buildSearchCommand(dirs, "a.pdf", 1234L, busybox, 5);

        // 首选内置 busybox 的 timeout（-x 探测其可执行），系统 timeout 只是次选
        check("优先探测内置 busybox 可执行（-x <busybox>）",
                cmd.contains("-x " + IncomingFile.quoteShell(busybox)));
        check("内置 busybox 的 timeout 作为首选赋给 $__rxin_to",
                cmd.contains("__rxin_to=" + IncomingFile.quoteShell(busybox + " timeout")));
        check("系统 timeout 只是次选（command -v timeout）",
                cmd.contains("command -v timeout"));

        check("命令带限时秒数（$__rxin_to 5 find）", cmd.contains("$__rxin_to 5 find"));
        check("命令里是 find", cmd.contains("find "));
        check("目录被单引号包起来",
                cmd.contains("'/sdcard/Tencent'") && cmd.contains("'/sdcard/Download'"));
        check("文件名被单引号包起来", cmd.contains("-name 'a.pdf'"));
        check("大小以字节精确匹配（-size Nc）", cmd.contains("-size 1234c"));
        check("只找普通文件（-type f）", cmd.contains("-type f"));
        check("丢弃 stderr 噪声", cmd.contains("2>/dev/null"));

        // 没有可用 timeout → 明确报出来（不静默退化成 0 候选）
        check("无可用 timeout 时打印 MARK_NO_TIMEOUT（不静默）",
                cmd.contains("printf '" + IncomingFile.MARK_NO_TIMEOUT));
        // 超时退出码 124 → 打印 MARK_TIMEOUT（把「只搜了一部分」和「搜完没命中」分开）
        check("超时（退出码 124）时打印 MARK_TIMEOUT",
                cmd.contains("= 124") && cmd.contains(IncomingFile.MARK_TIMEOUT));

        // 无内置 busybox：不带 -x 探测，但仍尝试系统 timeout
        String noBusybox = IncomingFile.buildSearchCommand(dirs, "a.pdf", 10L, null, 5);
        check("无内置 busybox 时不含 -x 探测", !noBusybox.contains(" -x "));
        check("无内置 busybox 时仍尝试系统 timeout", noBusybox.contains("command -v timeout"));
        check("无内置 busybox 时仍打印 MARK_NO_TIMEOUT 兜底",
                noBusybox.contains("printf '" + IncomingFile.MARK_NO_TIMEOUT));

        // timeout<=0 时按 1 秒处理 —— 绝不能不限时
        String forced = IncomingFile.buildSearchCommand(dirs, "a.pdf", 10L, busybox, 0);
        check("timeout<=0 时按 1 秒处理（绝不不限时）", forced.contains("$__rxin_to 1 find"));

        // 大小未知时不含 -size
        String noSize = IncomingFile.buildSearchCommand(dirs, "a.pdf", -1L, busybox, 5);
        check("大小未知时不含 -size", !noSize.contains("-size"));

        // 命令注入：名字里的单引号必须被转义成 quoteShell 的样子，原始串不能直接出现
        String evilName = "a'; rm -rf /; echo '";
        String evil = IncomingFile.buildSearchCommand(dirs, evilName, 1L, busybox, 5);
        check("敌意名按 quoteShell 拼装", evil.contains("-name " + IncomingFile.quoteShell(evilName)));
        check("未转义的原始名字不出现在命令里（确实转义了）",
                !evil.contains("-name " + evilName));

        // null / 空目录项被跳过：find 后只应跟有效目录
        String withBlank = IncomingFile.buildSearchCommand(
                Arrays.asList(null, "", "/sdcard/Download"), "a", 1L, busybox, 5);
        check("null / 空目录项被跳过（find 后只跟有效目录）",
                withBlank.contains("find '/sdcard/Download' -type f"));
    }

    // ================================================================ 13

    /** 搜索输出解析：只认绝对路径、去重、保序、trim。 */
    private static void parseSearchOutputChecks() {
        check("null → 空表", IncomingFile.parseSearchOutput(null).isEmpty());
        check("空串 → 空表", IncomingFile.parseSearchOutput("").isEmpty());

        List<String> parsed = IncomingFile.parseSearchOutput(
                "/sdcard/a.pdf\n/sdcard/b.pdf\n/sdcard/a.pdf\n");
        checkEq("去重后 2 条", 2, parsed.size());
        checkEq("保序取首条", "/sdcard/a.pdf", parsed.get(0));
        checkEq("次条", "/sdcard/b.pdf", parsed.get(1));

        // shell 的提示行不以 '/' 开头，必须滤掉，免得被当成文件路径
        List<String> noisy = IncomingFile.parseSearchOutput(
                "find: '/x': Permission denied\n/sdcard/ok.pdf\ntimeout: not found\n");
        checkEq("噪声被滤掉，只剩 1 条绝对路径", 1, noisy.size());
        checkEq("留下的是那条绝对路径", "/sdcard/ok.pdf", noisy.get(0));

        List<String> trimmed = IncomingFile.parseSearchOutput("   /sdcard/x.txt   \n");
        checkEq("行首尾空白被 trim", "/sdcard/x.txt", trimmed.get(0));
    }

    // ================================================================ 14

    /**
     * 搜索命中判据（{@code matchesSearchHit}）—— 名字 + 大小都要对。
     *
     * <p>大小是命门：同名文件太常见，只按名字匹配几乎必然带错文件。所以「大小未知」也必须
     * 判不可信（无从确认），绝不因为是唯一一个同名文件就放行。
     */
    private static void searchHitChecks() {
        check("名字相符且大小相同 → 可信",
                IncomingFile.matchesSearchHit("a.pdf", "/sdcard/Tencent/a.pdf", 1234L, 1234L));
        check("同名但大小不同 → 不可信",
                !IncomingFile.matchesSearchHit("a.pdf", "/sdcard/Tencent/a.pdf", 1234L, 1235L));
        check("大小相同但名字不符 → 不可信",
                !IncomingFile.matchesSearchHit("a.pdf", "/sdcard/Tencent/b.pdf", 1234L, 1234L));
        check("目标大小未知 → 不可信（无从确认）",
                !IncomingFile.matchesSearchHit("a.pdf", "/sdcard/Tencent/a.pdf", -1L, 1234L));
        check("大小写不同 → 不可信（类 Unix 区分大小写）",
                !IncomingFile.matchesSearchHit("A.PDF", "/sdcard/Tencent/a.pdf", 1234L, 1234L));
        check("0 字节同名文件 → 可信（大小 0 是已知值）",
                IncomingFile.matchesSearchHit("a.pdf", "/sdcard/a.pdf", 0L, 0L));

        check("名字 + 大小齐备 → 可搜", IncomingFile.canSearchByNameAndSize("a.pdf", 10L));
        check("大小未知 → 不可搜", !IncomingFile.canSearchByNameAndSize("a.pdf", -1L));
        check("名字为空 → 不可搜", !IncomingFile.canSearchByNameAndSize("", 10L));
        check("名字为 null → 不可搜", !IncomingFile.canSearchByNameAndSize(null, 10L));
        check("显示名带路径时按末段判断可搜",
                IncomingFile.canSearchByNameAndSize("/tmp/a.pdf", 10L));

        // 归纳：一组「大小各异」的同名候选里，只有大小也对得上的那个被接受
        String want = "report.pdf";
        long wantSize = 4096L;
        long[] sizes = {4096L, 4097L, 0L, -1L, 100L};
        int accepted = 0;
        boolean onlyExact = true;
        for (long size : sizes) {
            boolean got = IncomingFile.matchesSearchHit(
                    want, "/sdcard/Download/report.pdf", wantSize, size);
            if (got) {
                accepted++;
            }
            if (got != (size == wantSize)) {
                onlyExact = false;
            }
        }
        check("同名文件里只有大小也对得上的那个被接受（接受 " + accepted + " 个）",
                onlyExact && accepted == 1);
    }

    // ================================================================ 15

    /**
     * 诊断输出（{@code formatDiagnostics}）。
     *
     * <p>这一节锁住「定位失败时到底交代了哪些事实」：URI 原文、scheme/authority/path、
     * path 各段、DISPLAY_NAME、SIZE、getType、当前身份、逐条候选与拒绝原因、搜索记录、
     * 兜底结果。少带一样，下一轮排查就还得多猜一轮。未知项必须写成「（未知）」，不留空。
     */
    private static void diagnosticsChecks() {
        String accessReport =
                " · /sdcard/Download\n"
                        + "     Java : 可列出 5 项\n"
                        + "     shell: 可列出 5 项\n"
                        + "     结论 : 一致（两通道都能列出）\n";
        IncomingFile.Diagnostics d = new IncomingFile.Diagnostics(
                "content://com.tencent.mobileqq.fileprovider/external/Tencent/a.pdf",
                "content",
                "com.tencent.mobileqq.fileprovider",
                "/external/Tencent/a.pdf",
                Arrays.asList("external", "Tencent", "a.pdf"),
                "a.pdf",
                1234L,
                "application/pdf",
                "ROOT",
                Arrays.asList(
                        "/sdcard/Download/a.pdf → 文件名与显示名不符",
                        "/sdcard/a.pdf → 以当前身份列不出该文件（目录不可读或不存在）"),
                Arrays.asList(
                        "已知目录 4 个：命中 0 个候选",
                        "全盘搜索 /sdcard：命中 1 个候选"),
                accessReport,
                "已复制一份到 Download：/sdcard/Download/a.pdf");

        String text = IncomingFile.formatDiagnostics(d);
        check("诊断含 URI 原文",
                text.contains("content://com.tencent.mobileqq.fileprovider/external/Tencent/a.pdf"));
        check("诊断含 scheme", text.contains("scheme: content"));
        check("诊断含 authority", text.contains("authority: com.tencent.mobileqq.fileprovider"));
        check("诊断含 path", text.contains("path: /external/Tencent/a.pdf"));
        check("诊断含 path 分段", text.contains("external | Tencent | a.pdf"));
        check("诊断含 DISPLAY_NAME 值", text.contains("DISPLAY_NAME: a.pdf"));
        check("诊断含 SIZE 值", text.contains("SIZE: 1234"));
        check("诊断含 getType 值", text.contains("getType: application/pdf"));
        check("诊断含当前身份", text.contains("Shell身份: ROOT"));
        check("诊断含每条候选与拒绝原因",
                text.contains("/sdcard/Download/a.pdf → 文件名与显示名不符")
                        && text.contains("/sdcard/a.pdf → 以当前身份列不出该文件（目录不可读或不存在）"));
        check("诊断含搜索记录", text.contains("全盘搜索 /sdcard：命中 1 个候选"));
        check("诊断含「目标目录可访问性实测」小节标题",
                text.contains("目标目录可访问性实测"));
        check("诊断原样带出可访问性实测内容",
                text.contains("可列出 5 项") && text.contains("一致（两通道都能列出）"));
        check("诊断含兜底结果",
                text.contains("已复制一份到 Download：/sdcard/Download/a.pdf"));

        // 未知项写「（未知）」而不是留空 —— 复制回来时分得清「没有」与「漏了」
        IncomingFile.Diagnostics empty = new IncomingFile.Diagnostics(
                null, null, null, null, null, null, -1L, null, null, null, null, null, null);
        String etext = IncomingFile.formatDiagnostics(empty);
        check("未知 URI 写成（未知）", etext.contains("URI原文: （未知）"));
        check("未知大小写成「未知」（不是 -1）", etext.contains("SIZE: 未知"));
        check("空候选列表写成（无）", etext.contains("· （无）"));
        check("可访问性实测为空时写成（未测）（不留白）", etext.contains("（未测）"));
        check("null 诊断 → 空串", IncomingFile.formatDiagnostics(null).isEmpty());
    }

    // ================================================================ 16

    /**
     * 搜索结局（{@code parseSearchStatus} / {@code SearchOutcome}）。
     *
     * <p>三种失败必须分得清：① 命令根本没跑（无可用 timeout 工具）、② 超时（只搜了一部分）、
     * ③ 跑完没命中。上一版只返回路径列表，三者在上层看来一模一样，诊断里只剩一句
     * 「命中 0 个候选」，排查又回到靠猜。本节把这些钉死。
     */
    private static void searchStatusChecks() {
        // 无输出：既不可能是「跑完了」（那也会有输出），也不可能是超时/无工具（那有标记）
        check("无输出（null）→ NO_OUTPUT",
                IncomingFile.parseSearchStatus(null) == IncomingFile.SearchStatus.NO_OUTPUT);
        check("无输出（空串）→ NO_OUTPUT",
                IncomingFile.parseSearchStatus("") == IncomingFile.SearchStatus.NO_OUTPUT);
        check("正常输出（有路径、无标记）→ OK",
                IncomingFile.parseSearchStatus("/sdcard/a.pdf\n") == IncomingFile.SearchStatus.OK);
        check("含 MARK_NO_TIMEOUT → NO_TIMEOUT_TOOL",
                IncomingFile.parseSearchStatus(IncomingFile.MARK_NO_TIMEOUT + "\n")
                        == IncomingFile.SearchStatus.NO_TIMEOUT_TOOL);
        check("含 MARK_TIMEOUT → TIMEOUT",
                IncomingFile.parseSearchStatus("/sdcard/a.pdf\n" + IncomingFile.MARK_TIMEOUT + "\n")
                        == IncomingFile.SearchStatus.TIMEOUT);
        // 两种标记都在时，「命令根本没跑」更严重、更该被看到 → 优先报它
        check("两种标记都在 → 优先 NO_TIMEOUT_TOOL",
                IncomingFile.parseSearchStatus(
                        IncomingFile.MARK_TIMEOUT + "\n" + IncomingFile.MARK_NO_TIMEOUT + "\n")
                        == IncomingFile.SearchStatus.NO_TIMEOUT_TOOL);

        // 每种结局都有中文标签（诊断里要能读懂）
        boolean allLabeled = true;
        for (IncomingFile.SearchStatus s : IncomingFile.SearchStatus.values()) {
            if (s.label() == null || s.label().isEmpty()) {
                allLabeled = false;
            }
        }
        check("每个 SearchStatus 都有非空标签", allLabeled);
        check("三种失败标签互不相同（能区分）",
                !IncomingFile.SearchStatus.TIMEOUT.label()
                        .equals(IncomingFile.SearchStatus.NO_TIMEOUT_TOOL.label())
                        && !IncomingFile.SearchStatus.NO_TIMEOUT_TOOL.label()
                        .equals(IncomingFile.SearchStatus.NO_OUTPUT.label()));

        // SearchOutcome：把结局与路径一起带出，失败也不静默
        IncomingFile.SearchOutcome ok = new IncomingFile.SearchOutcome(
                Arrays.asList("/sdcard/a.pdf"), IncomingFile.SearchStatus.OK, "");
        checkEq("SearchOutcome 保留路径", 1, ok.getPaths().size());
        check("SearchOutcome status 正确", ok.getStatus() == IncomingFile.SearchStatus.OK);
        check("describe 写出状态标签", ok.describe("已知目录").contains("正常"));
        check("describe 写出命中数", ok.describe("已知目录").contains("1"));

        IncomingFile.SearchOutcome to = new IncomingFile.SearchOutcome(
                null, IncomingFile.SearchStatus.TIMEOUT, "只搜了一部分");
        check("SearchOutcome：null 路径 → 空表（不抛异常）", to.getPaths().isEmpty());
        check("SearchOutcome：describe 含超时标签", to.describe("全盘").contains("超时"));
        check("SearchOutcome：describe 含细节（失败也不静默）",
                to.describe("全盘").contains("只搜了一部分"));

        IncomingFile.SearchOutcome nullStatus = new IncomingFile.SearchOutcome(
                Arrays.asList("/x"), null, null);
        check("SearchOutcome：null status 兜底为 NO_OUTPUT",
                nullStatus.getStatus() == IncomingFile.SearchStatus.NO_OUTPUT);
    }

    // ================================================================ 17

    /**
     * 目标目录可访问性实测（{@code probeDirs} / {@code formatAccessReport}）。
     *
     * <p>用户真机反馈「MT 管理器不借助 adb / root 也能进 {@code Android/data}」，推翻了我们
     * 「根本读不到」的假设。所以诊断要**实测**我们对关键目录到底能不能列出，并**分通道**
     * 记录（Java vs shell）：两者结论若不一致，答案往往就藏在那条差异里。本节钉住目录计划、
     * 渲染文本与「一致性结论」的判据。
     */
    private static void accessReportChecks() {
        // probeDirs：5 个目录拼到外部存储根，且与 PROBE_SUBDIRS 一一对应
        List<String> dirs = IncomingFile.probeDirs("/storage/emulated/0");
        checkEq("实测目录个数 = PROBE_SUBDIRS 的长度",
                IncomingFile.PROBE_SUBDIRS.size(), dirs.size());
        check("含 Android/data", dirs.contains("/storage/emulated/0/Android/data"));
        check("含 QQ 的 Android/data 目录",
                dirs.contains("/storage/emulated/0/Android/data/com.tencent.mobileqq"));
        check("含 QQfile_recv 目录",
                dirs.contains("/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv"));
        check("含 Tencent 目录", dirs.contains("/storage/emulated/0/Tencent"));
        check("含对照组 Download 目录", dirs.contains("/storage/emulated/0/Download"));

        // 根目录结尾斜杠要剥掉，否则会拼出 // 这种路径
        List<String> withSlash = IncomingFile.probeDirs("/sdcard/");
        boolean noDouble = true;
        for (String d : withSlash) {
            if (d.contains("//")) {
                noDouble = false;
                break;
            }
        }
        check("根目录结尾斜杠被去掉（不出现 //）", noDouble);

        check("空根 → 空（宁可不测，也不测错地方）", IncomingFile.probeDirs("").isEmpty());
        check("null 根 → 空", IncomingFile.probeDirs(null).isEmpty());

        // ListProbe.describe：成功报条数、失败报原因、原因缺失也明说
        checkEq("ListProbe 成功：可列出 N 项", "可列出 3 项",
                new IncomingFile.ListProbe(true, 3, "").describe());
        check("ListProbe 失败：报出原因",
                new IncomingFile.ListProbe(false, 0, "EACCES").describe().contains("EACCES"));
        check("ListProbe 失败且原因空：写明「原因未知」（不留白）",
                new IncomingFile.ListProbe(false, 0, "").describe().contains("原因未知"));

        // formatAccessReport：逐目录「路径 / Java / shell / 结论」四行
        IncomingFile.DirProbe agreeOk = new IncomingFile.DirProbe(
                "/sdcard/Download",
                new IncomingFile.ListProbe(true, 5, ""),
                new IncomingFile.ListProbe(true, 5, ""));
        IncomingFile.DirProbe javaBlockedShellOk = new IncomingFile.DirProbe(
                "/sdcard/Android/data/com.tencent.mobileqq",
                new IncomingFile.ListProbe(false, 0, "EACCES"),
                new IncomingFile.ListProbe(true, 12, ""));
        IncomingFile.DirProbe bothFail = new IncomingFile.DirProbe(
                "/sdcard/Android/data",
                new IncomingFile.ListProbe(false, 0, "EACCES"),
                new IncomingFile.ListProbe(false, 0, "Permission denied"));
        String report = IncomingFile.formatAccessReport(
                Arrays.asList(agreeOk, javaBlockedShellOk, bothFail));

        check("实测报告含目录路径", report.contains("/sdcard/Android/data/com.tencent.mobileqq"));
        check("实测报告含 Java 通道结论", report.contains("Java :"));
        check("实测报告含 shell 通道结论", report.contains("shell:"));
        check("实测报告含「结论」行", report.contains("结论 :"));
        check("两通道都成功 → 结论=一致（都能列出）",
                report.contains("一致（两通道都能列出）"));
        check("Java 列不出 / shell 能列出 → 结论写明「不一致」且指出改走 shell",
                report.contains("不一致") && report.contains("访问应改走 shell 通道"));
        check("两通道都失败 → 结论=一致（都列不出）",
                report.contains("一致（两通道都列不出）"));

        // 空表 / null：不崩，明确写「未测」
        check("null 实测列表 → 写明「未测」",
                IncomingFile.formatAccessReport(null).contains("未测"));
        check("空实测列表 → 写明「未测」",
                IncomingFile.formatAccessReport(Arrays.<IncomingFile.DirProbe>asList()).contains("未测"));

        // 实测结果必须真的进诊断（Diagnostics 构造带 accessReport，formatDiagnostics 渲染它）
        IncomingFile.Diagnostics d = new IncomingFile.Diagnostics(
                "content://x/y", "content", "x", "/y", Arrays.asList("y"),
                "y", 1L, "text/plain", "ROOT",
                Arrays.asList("a"), Arrays.asList("b"),
                report, "已复制");
        String text = IncomingFile.formatDiagnostics(d);
        check("诊断带出实测内容（Java / shell / 结论）",
                text.contains("Java :") && text.contains("shell:") && text.contains("结论 :"));
        check("诊断带出「不一致」结论（这是本轮要回答的关键信号）",
                text.contains("访问应改走 shell 通道"));
    }

    // ================================================================ 18

    /**
     * 定向目录查找（{@code directedLookupDirs} / {@code matchesDirectedHit}）。
     *
     * <p>用户真机确认了 QQ 的下载目录是固定、已知的，于是不再猜 URI，而是把这些**已知落点
     * 直接列出来**按名字 + 大小找 —— 比全盘 `find` 更快、更可靠。这一节钉住目录计划
     * （覆盖 QQ 接收目录、根斜杠剥掉、空根返回空）与命中判据（名字逐字符 + 大小逐字节，
     * 同名不同文件判不中）。
     */
    private static void directedLookupChecks() {
        // directedLookupDirs：已知落点拼到外部存储根，且与 DIRECTED_LOOKUP_SUBDIRS 一一对应
        List<String> dirs = IncomingFile.directedLookupDirs("/storage/emulated/0");
        checkEq("定向目录个数 = DIRECTED_LOOKUP_SUBDIRS 的长度",
                IncomingFile.DIRECTED_LOOKUP_SUBDIRS.size(), dirs.size());
        check("覆盖 QQ 的 QQfile_recv（用户真机确认的那个）",
                dirs.contains("/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv"));
        check("覆盖旧版 QQ 的 Tencent/QQfile_recv",
                dirs.contains("/storage/emulated/0/Tencent/QQfile_recv"));

        // 根目录结尾斜杠要剥掉，否则会拼出 // 这种路径
        List<String> withSlash = IncomingFile.directedLookupDirs("/sdcard/");
        boolean noDouble = true;
        for (String d : withSlash) {
            if (d.contains("//")) {
                noDouble = false;
                break;
            }
        }
        check("根目录结尾斜杠被去掉（不出现 //）", noDouble);
        check("剥斜杠后仍是 QQfile_recv",
                withSlash.contains("/sdcard/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv"));

        check("空根 → 空（宁可不查，也不查错地方）", IncomingFile.directedLookupDirs("").isEmpty());
        check("null 根 → 空", IncomingFile.directedLookupDirs(null).isEmpty());

        // matchesDirectedHit：名字逐字符 + 大小逐字节，缺一不可
        check("名字相符且大小相同 → 命中",
                IncomingFile.matchesDirectedHit("a.pdf", "a.pdf", 1234L, 1234L));
        check("显示名带路径时按末段比对",
                IncomingFile.matchesDirectedHit("/qq/tmp/a.pdf", "a.pdf", 1234L, 1234L));
        check("同名但大小不同 → 不命中（同名不同文件）",
                !IncomingFile.matchesDirectedHit("a.pdf", "a.pdf", 1234L, 1235L));
        check("大小相同但名字不符 → 不命中",
                !IncomingFile.matchesDirectedHit("a.pdf", "b.pdf", 1234L, 1234L));
        check("目标大小未知 → 不命中（无从确认）",
                !IncomingFile.matchesDirectedHit("a.pdf", "a.pdf", -1L, 1234L));
        check("大小写不同 → 不命中（类 Unix 区分大小写）",
                !IncomingFile.matchesDirectedHit("A.PDF", "a.pdf", 1234L, 1234L));
        check("目录项名为空 → 不命中",
                !IncomingFile.matchesDirectedHit("a.pdf", "", 1234L, 1234L));
        check("0 字节同名文件 → 命中（大小 0 是已知值）",
                IncomingFile.matchesDirectedHit("a.pdf", "a.pdf", 0L, 0L));

        // 归纳：目录里一组「名字各异 / 大小各异」的条目里，只有名字+大小都对上的那个命中
        String want = "report.pdf";
        long wantSize = 4096L;
        String[][] entries = {
                {"report.pdf", "4096"},        // 名字+大小都对 → 应命中
                {"report.pdf", "4097"},        // 大小差一点 → 不命中
                {"report (1).pdf", "4096"},    // 近名 → 不命中
                {"report.pdf.bak", "4096"},    // 近名 → 不命中
                {"report.pdf", "0"},           // 大小 0 → 不命中
        };
        int accepted = 0;
        boolean onlyExact = true;
        for (String[] e : entries) {
            boolean got = IncomingFile.matchesDirectedHit(
                    want, e[0], wantSize, Long.parseLong(e[1]));
            boolean should = e[0].equals(want) && Long.parseLong(e[1]) == wantSize;
            if (got) {
                accepted++;
            }
            if (got != should) {
                onlyExact = false;
            }
        }
        check("目录条目里只有名字+大小都对上的那个命中（命中 " + accepted + " 个）",
                onlyExact && accepted == 1);
    }

    // ================================================================ 19

    /**
     * 浏览到 `Android/data` 时的「无权限」提醒。
     *
     * <p>这一节的要害是**分清「空目录」与「读不出来」** —— 判错会让用户在普通空目录里
     * 收到一张劝他装 Shizuku 的面板（骚扰），或者在真的进不去时什么都看不到（等于没做）。
     * 判据全部落在 {@code shouldWarnNoAccess} / {@code isUnderAndroidData} 上，
     * 三个条件必须**同时**成立。
     */
    private static void browserNoAccessChecks() {
        String normal = IncomingFile.NORMAL_LEVEL_LABEL;

        // --- isUnderAndroidData：按路径段判，且两种存储前缀等价 ---

        check("标准路径在 Android/data 之下",
                IncomingFile.isUnderAndroidData("/storage/emulated/0/Android/data"));
        check("Android/data 的子目录也算",
                IncomingFile.isUnderAndroidData(
                        "/storage/emulated/0/Android/data/com.tencent.mobileqq"));
        check("QQfile_recv 那种深层路径也算",
                IncomingFile.isUnderAndroidData(
                        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv"));
        check("/sdcard 前缀与 /storage/emulated/0 判成同一处（软链等价）",
                IncomingFile.isUnderAndroidData("/sdcard/Android/data"));
        check("/storage/self/primary 前缀也等价",
                IncomingFile.isUnderAndroidData("/storage/self/primary/Android/data"));
        check("重复斜杠不影响判定",
                IncomingFile.isUnderAndroidData("/storage/emulated/0//Android//data"));
        check("尾斜杠不影响判定",
                IncomingFile.isUnderAndroidData("/storage/emulated/0/Android/data/"));
        check("Android/obb 与 Android/data 同类（一并纳入）",
                IncomingFile.isUnderAndroidData("/storage/emulated/0/Android/obb"));
        check("Android/data 这个**目录本身**（不是子目录）也算在里面",
                IncomingFile.isUnderAndroidData("/storage/emulated/0/Android/data"));

        // 反面：不能把长得像的路径算进来（用 contains 判就会踩这几条）
        check("普通目录不算",
                !IncomingFile.isUnderAndroidData("/storage/emulated/0/Download"));
        check("名字里含 Android/data 的**同级**目录不算（段比对而非子串包含）",
                !IncomingFile.isUnderAndroidData("/storage/emulated/0/我的Android/data备份"));
        check("Android 段但不是 data/obb 不算",
                !IncomingFile.isUnderAndroidData("/storage/emulated/0/Android/MyData"));
        check("data 段但不在 Android 下不算",
                !IncomingFile.isUnderAndroidData("/storage/emulated/0/data"));
        check("根目录不算", !IncomingFile.isUnderAndroidData("/"));
        check("null 不算（不让它 NPE）",
                !IncomingFile.isUnderAndroidData(null));
        check("空串不算", !IncomingFile.isUnderAndroidData(""));

        // --- 归一化本身 ---

        checkEq("归一化把 /sdcard 展开成 /storage/emulated/0",
                IncomingFile.EMULATED_ROOT + "/Download",
                IncomingFile.normalizeStoragePath("/sdcard/Download"));
        checkEq("归一化把 /storage/self/primary 展开",
                IncomingFile.EMULATED_ROOT + "/Download",
                IncomingFile.normalizeStoragePath("/storage/self/primary/Download"));
        checkEq("归一化压掉重复斜杠",
                IncomingFile.EMULATED_ROOT + "/Android/data",
                IncomingFile.normalizeStoragePath("/storage/emulated/0//Android//data"));
        checkEq("归一化去掉尾斜杠",
                IncomingFile.EMULATED_ROOT + "/Android/data",
                IncomingFile.normalizeStoragePath("/storage/emulated/0/Android/data/"));
        checkEq("归一化不动别的路径",
                "/data/local/tmp",
                IncomingFile.normalizeStoragePath("/data/local/tmp"));
        checkEq("根目录归一化后还是 /（不是空串）", "/",
                IncomingFile.normalizeStoragePath("/"));
        checkEq("null 归一化成空串", "", IncomingFile.normalizeStoragePath(null));
        checkEq("空串归一化成空串", "", IncomingFile.normalizeStoragePath(""));

        // --- shouldWarnNoAccess：三个条件同时成立 ---

        String qqDir = "/storage/emulated/0/Android/data/com.tencent.mobileqq";

        // 三条件全中 → 提醒
        check("普通身份 + 在 Android/data 下 + 列不出 → 提醒",
                IncomingFile.shouldWarnNoAccess(qqDir, normal, false));
        check("/sdcard 前缀同样提醒",
                IncomingFile.shouldWarnNoAccess("/sdcard/Android/data", normal, false));

        // 逐个破一条件 → 都不提醒（这是本节的核心：不能多报，也不能漏报）
        check("**空目录不算**：只有探针说能列出，就不提醒（这是「空」不是「没权限」）",
                !IncomingFile.shouldWarnNoAccess(qqDir, normal, true));
        check("ROOT 身份不提醒（他读不出来是别的原因）",
                !IncomingFile.shouldWarnNoAccess(qqDir, "ROOT", false));
        check("ADB 身份不提醒（同上）",
                !IncomingFile.shouldWarnNoAccess(qqDir, "ADB", false));
        check("身份未知（null）不提醒（先探身份，而不是先劝装东西）",
                !IncomingFile.shouldWarnNoAccess(qqDir, null, false));
        check("路径不在 Android/data 下不提醒（哪怕是普通身份且列不出）",
                !IncomingFile.shouldWarnNoAccess("/storage/emulated/0/Download", normal, false));
        check("名字像但不是（段比对）→ 不提醒",
                !IncomingFile.shouldWarnNoAccess("/storage/emulated/0/我的Android/data备份", normal, false));

        // 归纳：三个布尔条件的 2×2×2 全枚举，只有 (true,true,true) 该为真
        boolean[] paths = {true, false};      // 是否在 Android/data 下
        boolean[] levels = {true, false};     // 是否「普通」身份
        boolean[] probes = {true, false};     // 探针是否列得出
        int warned = 0;
        boolean onlyTriple = true;
        for (boolean underData : paths) {
            for (boolean isNormal : levels) {
                for (boolean probeOk : probes) {
                    String p = underData ? qqDir : "/storage/emulated/0/Download";
                    String lv = isNormal ? normal : "ROOT";
                    boolean got = IncomingFile.shouldWarnNoAccess(p, lv, probeOk);
                    boolean should = underData && isNormal && !probeOk;
                    if (got) {
                        warned++;
                    }
                    if (got != should) {
                        onlyTriple = false;
                    }
                }
            }
        }
        check("全枚举 8 种组合里只有「在 Android/data 下 + 普通身份 + 列不出」为真（命中 " + warned + " 种）",
                onlyTriple && warned == 1);

        // --- 面板文案：与「定位失败」那条是两份，但要一起可用 ---

        check("提醒面板标题非空", !IncomingFile.NO_ACCESS_TITLE.isEmpty());
        check("提醒面板正文非空", !IncomingFile.NO_ACCESS_BODY.isEmpty());
        check("两条提示文案是不同的两份（各答各的问题）",
                !IncomingFile.NO_ACCESS_BODY.equals(IncomingFile.SHIZUKU_HINT_TEXT));
        check("提醒正文明确写出 Android/data（用户要能对上自己点的目录）",
                IncomingFile.NO_ACCESS_BODY.contains("Android/data"));
        check("提醒正文提到 Shizuku（给出出路）",
                IncomingFile.NO_ACCESS_BODY.contains("Shizuku"));
        check("提醒正文说清「不是本应用的问题」（否则用户会当成我们的 bug）",
                IncomingFile.NO_ACCESS_BODY.contains("不是本应用的问题"));
        check("提醒与定位失败两条共用同一个下载地址（真源唯一）",
                IncomingFile.SHIZUKU_DOWNLOAD_URL.equals("https://shizuku.rikka.app/download/"));
    }

    // ================================================================ 工具

    private static File write(Path dir, String name, byte[] data) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.write(f.toPath(), data);
        return f;
    }

    /** 便捷重载：往 [dir] 下的 [name] 写一段 UTF-8 文本（uniqueName 那节要造占位文件） */
    private static File write(File dir, String name, String text) throws Exception {
        File f = new File(dir, name);
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(String name, boolean ok) {
        total++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + name);
        } else {
            System.out.println("  ok    " + name);
        }
    }

    private static void checkEq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        total++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + name + "  期望=" + expected + " 实际=" + actual);
        } else {
            System.out.println("  ok    " + name);
        }
    }
}
