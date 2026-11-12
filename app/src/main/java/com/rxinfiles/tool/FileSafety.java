package com.rxinfiles.tool;

import java.io.File;
import java.io.IOException;

final class FileSafety {
    private FileSafety() {
    }

    /**
     * target 是否等于 source 或落在 source 内部（用于拦住「把目录复制进它自己」）。
     *
     * 规范化路径优先用 getCanonicalPath()（会解析符号链接，避免 /sdcard 与
     * /storage/emulated/0 被当成两个目录）。但 ROOT-only 路径普通身份解析不了
     * canonical（父目录不可穿越，会抛 IOException）—— 此时**退回绝对路径**做词法比较，
     * 而不是抛异常把复制整体挡掉（BUG日志-20261110 的 BUG-004）。
     *
     * 注意：退回绝对路径时不做符号链接解析，属于尽力而为；真正的写操作由 ROOT 执行，
     * 这一层只负责拦住最危险的「源 == 目标 / 目标是源的子路径」。
     */
    static boolean isSameOrInside(File source, File target) throws IOException {
        String sourcePath = canonicalOrAbsolute(source);
        String targetPath = canonicalOrAbsolute(target);
        if (sourcePath.equals(targetPath)) {
            return true;
        }
        String prefix = sourcePath.endsWith(File.separator)
                ? sourcePath
                : sourcePath + File.separator;
        return targetPath.startsWith(prefix);
    }

    private static String canonicalOrAbsolute(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException | SecurityException e) {
            return file.getAbsolutePath();
        }
    }
}
