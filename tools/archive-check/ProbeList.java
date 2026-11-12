import com.rxinfiles.tool.Archives;

import java.io.File;

/**
 * 一次性探针：把 [Archives.list] 的**条目顺序**原样打出来，用来验证
 * 「面板里的树到底还连不连得起来」。
 *
 * 用法：java ... ProbeList <归档> [密码]
 *
 * 看两件事：
 *   1. 每个目录后面跟的是不是**它自己的**子项（路径序 / 树序）；
 *   2. 缩进深度序列有没有出现「同级条目被别的目录隔开」这种断点。
 */
public class ProbeList {

    public static void main(String[] args) throws Exception {
        File file = new File(args[0]);
        char[] pw = args.length > 1 ? args[1].toCharArray() : null;

        Archives.Listing listing = Archives.list(file, pw);
        System.out.println("=== " + file.getName() + "  "
                + listing.items.size() + " 条  格式=" + listing.kind);

        int index = 0;
        int prevDepth = -1;
        for (Archives.Item item : listing.items) {
            String trimmed = trimTrailingSlash(item.name);
            int depth = countSlash(trimmed);
            String mark = item.directory ? "D" : " ";
            StringBuilder indent = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                indent.append("    ");
            }
            // 断点提示：深度跳了不止一级 —— 说明父目录行没出现过
            String gap = "";
            if (prevDepth >= 0 && depth > prevDepth + 1) {
                gap = "   <== 深度从 " + prevDepth + " 跳到 " + depth + "（中间没有父目录行）";
            }
            System.out.printf("  %2d  %s  %s%s%s%n", index, mark, indent, trimmed, gap);
            prevDepth = depth;
            index++;
        }
    }

    private static String trimTrailingSlash(String name) {
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) == '/') {
            end--;
        }
        return name.substring(0, end);
    }

    private static int countSlash(String name) {
        int n = 0;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == '/') {
                n++;
            }
        }
        return n;
    }
}
