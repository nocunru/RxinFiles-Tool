package com.rxinfiles.tool;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileSafetyCheck {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("rx-file-safety");
        try {
            File source = tmp.resolve("a").toFile();
            check("目录与目标相同", true, FileSafety.isSameOrInside(source, source));
            check("目标是直接子目录", true,
                    FileSafety.isSameOrInside(source, new File(source, "b")));
            check("目标是更深层子目录", true,
                    FileSafety.isSameOrInside(source, new File(source, "b/c")));
            check("规范化后仍落在源内", true,
                    FileSafety.isSameOrInside(source, new File(source, "x/../b")));
            check("相似前缀不是子目录", false,
                    FileSafety.isSameOrInside(source, tmp.resolve("a2").toFile()));
            check("规范化后的相似前缀不是子目录", false,
                    FileSafety.isSameOrInside(source, new File(source, "../a2")));
            check("父目录不是源内目标", false,
                    FileSafety.isSameOrInside(source, tmp.toFile()));
            check("同级目录不是源内目标", false,
                    FileSafety.isSameOrInside(source, tmp.resolve("b").toFile()));
            check("普通文件覆盖自身", true,
                    FileSafety.isSameOrInside(tmp.resolve("f.txt").toFile(),
                            tmp.resolve("f.txt").toFile()));
            check("普通文件写到其他位置", false,
                    FileSafety.isSameOrInside(tmp.resolve("f.txt").toFile(),
                            tmp.resolve("g.txt").toFile()));
        } finally {
            try (var paths = Files.walk(tmp)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> p.toFile().delete());
            }
        }
        System.out.println("结果: " + (checks - failures) + " 项通过, " + failures + " 项失败");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean expected, boolean actual) {
        checks++;
        if (expected == actual) {
            System.out.println("  ok    " + name);
        } else {
            failures++;
            System.out.println("  FAIL  " + name + "：期望 " + expected + "，实际 " + actual);
        }
    }
}
