import com.rxinfiles.tool.FileSort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FileSort 的行为断言（纯 JVM，不需要 Android）。
 *
 * 排序是「看着对、其实错」的重灾区：字符串排序在 file1/file2/file10 这种名字上
 * 一眼看不出问题，而用户的目录里到处都是这种名字。所以每一条都得有断言钉住。
 */
public class FileSortCheck {

    private static int passed = 0;
    private static int failed = 0;
    private static String section = "";

    public static void main(String[] args) {
        section("1. 自然排序：名字里的数字按数值比，不按字符比");
        naturalChecks();

        section("2. 大小写与 locale：不能随系统语言变");
        caseChecks();

        section("3. 类型键 typeKey：口径与 extOf 一致");
        typeKeyChecks();

        section("4. compare：目录优先 / 四个维度 / 升降序");
        compareChecks();

        section("5. 打平时的名称兜底：顺序必须稳定");
        tieBreakChecks();

        section("6. 偏好读回：不认识的值一律回默认");
        parseChecks();

        section("7. 合起来：默认设置真排一次");
        sortedChecks();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 各组

    private static void naturalChecks() {
        check("file2 < file10（字符串排序会得出相反结论）",
                FileSort.naturalCompare("file2", "file10") < 0);
        check("file10 > file2", FileSort.naturalCompare("file10", "file2") > 0);
        check("file1 < file2", FileSort.naturalCompare("file1", "file2") < 0);
        check("file9 < file10（跨位数）", FileSort.naturalCompare("file9", "file10") < 0);
        check("file099 < file100（跨位数且带前导零）",
                FileSort.naturalCompare("file099", "file100") < 0);
        check("img7 与 img007 数值相等时不在这里定胜负，继续比后面",
                FileSort.naturalCompare("img7.txt", "img007.txt") == 0);
        check("file2a < file10b", FileSort.naturalCompare("file2a", "file10b") < 0);
        check("a2b10 < a2b9", FileSort.naturalCompare("a2b10", "a2b9") > 0);
        check("同一个串相等", FileSort.naturalCompare("abc", "abc") == 0);
        check("前缀短的排前面：a < ab", FileSort.naturalCompare("a", "ab") < 0);
        check("空串排最前", FileSort.naturalCompare("", "a") < 0);
        check("null 当空串处理，不抛", FileSort.naturalCompare(null, null) == 0);
        check("纯数字整体比：5 < 12", FileSort.naturalCompare("5", "12") < 0);
        check("全零与零相等：0 与 00 数值相等", FileSort.naturalCompare("0", "00") == 0);
        check("中文可比较且不抛", FileSort.naturalCompare("中文A", "中文B") < 0);
    }

    private static void caseChecks() {
        check("大小写不影响主序：a 与 A 相等",
                FileSort.naturalCompare("a.txt", "A.txt") == 0);
        check("大小写不影响主序：File2 与 file10",
                FileSort.naturalCompare("File2", "file10") < 0);
        check("typeKey 统一小写", FileSort.typeKey("PHOTO.JPG").equals("jpg"));
    }

    private static void typeKeyChecks() {
        check("取扩展名并小写", FileSort.typeKey("a.tar.gz").equals("gz"));
        check("无点 → 空串", FileSort.typeKey("README").equals(""));
        check("点开头视为无扩展名（与 extOf 口径一致）",
                FileSort.typeKey(".gitignore").equals(""));
        check("末尾是点 → 空串", FileSort.typeKey("foo.").equals(""));
        check("null → 空串", FileSort.typeKey(null).equals(""));
        check("多点取最后一段", FileSort.typeKey("a.b.c.TXT").equals("txt"));
    }

    private static void compareChecks() {
        // 目录优先
        check("目录优先开：目录排在文件前",
                FileSort.compare("zzz", true, 0L, 0L,
                        "aaa", false, 0L, 0L,
                        FileSort.Key.NAME, FileSort.Order.ASC, true) < 0);
        check("目录优先关：由名称决定（aaa 在前）",
                FileSort.compare("zzz", true, 0L, 0L,
                        "aaa", false, 0L, 0L,
                        FileSort.Key.NAME, FileSort.Order.ASC, false) > 0);

        // 名称
        check("名称升序：file2 < file10",
                FileSort.compare("file2", false, 0, 0, "file10", false, 0, 0,
                        FileSort.Key.NAME, FileSort.Order.ASC, true) < 0);
        check("名称降序：file2 > file10",
                FileSort.compare("file2", false, 0, 0, "file10", false, 0, 0,
                        FileSort.Key.NAME, FileSort.Order.DESC, true) > 0);

        // 大小
        check("大小升序：100 < 200",
                FileSort.compare("a", false, 100, 0, "b", false, 200, 0,
                        FileSort.Key.SIZE, FileSort.Order.ASC, true) < 0);
        check("大小降序：100 > 200 变为 100 在后",
                FileSort.compare("a", false, 100, 0, "b", false, 200, 0,
                        FileSort.Key.SIZE, FileSort.Order.DESC, true) > 0);

        // 时间
        check("时间升序：旧的在前面",
                FileSort.compare("a", false, 0, 1000, "b", false, 0, 2000,
                        FileSort.Key.TIME, FileSort.Order.ASC, true) < 0);
        check("时间降序：新的在前面",
                FileSort.compare("a", false, 0, 1000, "b", false, 0, 2000,
                        FileSort.Key.TIME, FileSort.Order.DESC, true) > 0);

        // 类型
        check("类型升序：jpg < png",
                FileSort.compare("a.jpg", false, 0, 0, "b.png", false, 0, 0,
                        FileSort.Key.TYPE, FileSort.Order.ASC, true) < 0);
        check("类型排序与文件名无关：z.jpg 仍排在 a.png 前",
                FileSort.compare("z.jpg", false, 0, 0, "a.png", false, 0, 0,
                        FileSort.Key.TYPE, FileSort.Order.ASC, true) < 0);
        check("无扩展名的排在类型升序最前",
                FileSort.compare("README", false, 0, 0, "a.txt", false, 0, 0,
                        FileSort.Key.TYPE, FileSort.Order.ASC, true) < 0);

        // 目录优先与维度同时生效：目录之间仍按维度排
        check("两个目录之间按名称排",
                FileSort.compare("dir2", true, 0, 0, "dir10", true, 0, 0,
                        FileSort.Key.NAME, FileSort.Order.ASC, true) < 0);
    }

    private static void tieBreakChecks() {
        check("大小相同 → 用名称兜底（顺序稳定，不会每次刷新换位）",
                FileSort.compare("b", false, 0, 0, "a", false, 0, 0,
                        FileSort.Key.SIZE, FileSort.Order.ASC, true) > 0);
        check("时间相同 → 用名称兜底",
                FileSort.compare("b", false, 0, 7, "a", false, 0, 7,
                        FileSort.Key.TIME, FileSort.Order.ASC, true) > 0);
        check("类型相同 → 用名称兜底",
                FileSort.compare("b.txt", false, 0, 0, "a.txt", false, 0, 0,
                        FileSort.Key.TYPE, FileSort.Order.ASC, true) > 0);
        check("降序时兜底方向也跟着降：b 排在 a 前面，所以 a 的结果为正",
                FileSort.compare("a", false, 0, 0, "b", false, 0, 0,
                        FileSort.Key.SIZE, FileSort.Order.DESC, true) > 0);
        check("名称维度不需要兜底，直接相等",
                FileSort.compare("same", false, 0, 0, "same", false, 0, 0,
                        FileSort.Key.NAME, FileSort.Order.ASC, true) == 0);
    }

    private static void parseChecks() {
        check("key 读回 NAME", FileSort.keyOf("NAME") == FileSort.Key.NAME);
        check("key 读回 TIME", FileSort.keyOf("TIME") == FileSort.Key.TIME);
        check("key 不认识 → 默认 NAME", FileSort.keyOf("WHAT") == FileSort.DEFAULT_KEY);
        check("key 为 null → 默认 NAME", FileSort.keyOf(null) == FileSort.DEFAULT_KEY);
        check("order 读回 DESC", FileSort.orderOf("DESC") == FileSort.Order.DESC);
        check("order 不认识 → 默认 ASC", FileSort.orderOf("x") == FileSort.DEFAULT_ORDER);
        check("order 为 null → 默认 ASC", FileSort.orderOf(null) == FileSort.DEFAULT_ORDER);

        check("默认维度是「名称」", FileSort.DEFAULT_KEY == FileSort.Key.NAME);
        check("默认方向是升序", FileSort.DEFAULT_ORDER == FileSort.Order.ASC);
        check("默认目录优先开启", FileSort.DEFAULT_DIRS_FIRST);

        check("显示名：NAME → 名称", FileSort.labelOf(FileSort.Key.NAME).equals("名称"));
        check("显示名：TIME → 修改时间", FileSort.labelOf(FileSort.Key.TIME).equals("修改时间"));
        check("显示名：ASC → 升序", FileSort.orderLabelOf(FileSort.Order.ASC).equals("升序"));
        check("显示名：DESC → 降序", FileSort.orderLabelOf(FileSort.Order.DESC).equals("降序"));
    }

    // ------------------------------------------------------------------ 收尾：真排一次

    /**
     * 用默认设置把一批真实名字排一遍，确认**人眼期待的顺序**真的出现了。
     * 上面那些两两比较证的是「判据对不对」，这一条证的是「合起来是不是那个意思」。
     */
    private static void sortedChecks() {
        List<String> names = new ArrayList<>(Arrays.asList(
                "file10.txt", "file2.txt", "file1.txt", "README", "dir10", "dir2"
        ));
        names.sort((a, b) -> FileSort.compare(a, false, 0, 0, b, false, 0, 0,
                FileSort.Key.NAME, FileSort.Order.ASC, true));
        List<String> want = Arrays.asList("dir2", "dir10", "file1.txt", "file2.txt",
                "file10.txt", "README");
        System.out.println("  实际顺序：" + names);
        check("默认排序得到人眼期待的顺序", names.equals(want));
    }

    // ------------------------------------------------------------------ 辅助

    private static void section(String title) {
        System.out.println();
        System.out.println(title);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + what);
        } else {
            failed++;
            System.out.println("  ✗ " + what);
        }
    }
}
