// IShizukuShellService.aidl
package com.rxinfiles.tool;

import android.os.ParcelFileDescriptor;

/**
 * Shizuku 用户服务接口。
 *
 * 实现类运行在由 Shizuku 以 shell(uid 2000) 身份拉起的进程中，
 * 因此它创建出来的 shell 进程天然具备 adb 权限。
 *
 * 注意：服务端可能同时持有多个 PTY（终端多标签），
 * 因此所有操作都以 pid 作为会话句柄，而不是隐式的单例状态。
 */
interface IShizukuShellService {

    /**
     * 在 shell 身份下创建一个 PTY 并 fork 出 shell 进程。
     *
     * @param shellPath 要执行的 shell，例如 /system/bin/sh
     * @param cwd       工作目录
     * @param rows      终端行数
     * @param cols      终端列数
     * @param outPid    长度 >= 1 的数组，返回该 shell 进程的 pid；
     *                  该 pid 同时作为本会话的句柄，用于 resize/closePty
     * @return PTY 主设备(master)端的 fd；失败时返回 null
     */
    ParcelFileDescriptor openPty(String shellPath, String cwd, int rows, int cols, inout int[] outPid);

    /**
     * 释放指定会话。
     * 服务端会关闭自己持有的 master fd 引用；当客户端也关闭其 fd 后，
     * 内核会向该 PTY 的前台进程组发送 SIGHUP，shell 随之退出。
     */
    void closePty(int pid);

    /** 以 adb 权限执行一条命令，返回 stdout + stderr 合并后的文本 */
    String exec(String command);
}
