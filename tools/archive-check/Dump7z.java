package com.rxinfiles.tool;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;

import java.io.File;

/** 一次性探测：7z 的「内容加密」能不能从 getContentMethods() 看出来 */
public class Dump7z {
    public static void main(String[] args) throws Exception {
        for (String a : args) {
            System.out.println("=== " + a);
            File f = new File(a);
            System.out.println("    needsPassword(Archives) = " + Archives.needsPassword(f));
            System.out.println("    SevenZArchive.needsPassword = " + SevenZArchive.needsPassword(f));
            try (SevenZFile zf = new SevenZFile(f)) {
                SevenZArchiveEntry e;
                while ((e = zf.getNextEntry()) != null) {
                    StringBuilder m = new StringBuilder();
                    Iterable<? extends SevenZMethodConfiguration> ms = e.getContentMethods();
                    if (ms == null) {
                        m.append("null");
                    } else {
                        for (SevenZMethodConfiguration c : ms) {
                            m.append(c.getMethod()).append(' ');
                        }
                    }
                    boolean aes = false;
                    if (ms != null) {
                        for (SevenZMethodConfiguration c : ms) {
                            if (c.getMethod() == SevenZMethod.AES256SHA256) {
                                aes = true;
                            }
                        }
                    }
                    System.out.printf("    %-24s size=%-6d methods=[%s] aes=%s%n",
                            e.getName(), e.getSize(), m.toString().trim(), aes);
                }
            } catch (Throwable t) {
                System.out.println("    打开失败: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }
    }
}
