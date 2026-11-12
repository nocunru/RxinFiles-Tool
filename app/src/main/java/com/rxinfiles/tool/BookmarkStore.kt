package com.rxinfiles.tool

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 书签条目。
 */
data class Bookmark(
    val name: String,
    val path: String
)

/**
 * 书签持久化存储。
 *
 * 书签的读写可能并发发生（界面线程、后台线程，以及未来的多进程扩展）。早先的实现把
 * 整张书签表放在 SharedPreferences 里，每次修改都是「读出整表 → 改内存 → 整表覆盖写回」；
 * SharedPreferences 的内存缓存按进程各自维护，并发写入方仍会基于自己的旧快照整表覆盖，
 * 于是并发读改写会互相覆盖，更新随机丢失。
 *
 * 现在改为单一数据文件 + 跨进程文件锁的事务模型：
 * - 数据文件：`filesDir/rouxin_bookmarks.json`，JSON 数组，元素为 `{"name","path"}`；
 * - 锁文件：`filesDir/rouxin_bookmarks.lock`，读写都在 FileChannel.lock() 内完成，
 *   由操作系统在进程之间串行化；
 * - 写入先落到 `rouxin_bookmarks.json.tmp`，fsync 后再 renameTo 原子替换数据文件，
 *   进程中途被杀也不会留下半个文件；
 * - 新增/删除/撤销/重命名等复合操作在同一把锁内完成整个「读—改—写」事务。
 *
 * 公开方法在文件系统异常（磁盘满、目录不可写、锁文件打不开等）时只记录 Log.w
 * 并返回降级结果（空列表 / null / false / 忽略），不把异常抛给调用方：书签操作
 * 发生在主线程，抛异常会让界面崩溃；旧 SharedPreferences 实现也不会因此抛出。
 *
 * 旧 SharedPreferences（`rouxin_bookmarks` / `bookmark_list`）里的数据只在数据
 * 文件尚不存在时、在锁内读取一次并迁移到数据文件；迁移完成后不再向
 * SharedPreferences 写入任何书签。
 */
object BookmarkStore {

    private const val FILE_NAME = "rouxin_bookmarks.json"
    private const val LOCK_NAME = "rouxin_bookmarks.lock"

    /** 旧版本使用过的 SharedPreferences 名称与键，仅用于一次性迁移。 */
    private const val LEGACY_PREFS = "rouxin_bookmarks"
    private const val LEGACY_KEY = "bookmark_list"

    private const val TAG = "BookmarkStore"

    @Synchronized
    fun load(context: Context): MutableList<Bookmark> =
        guarded(mutableListOf()) { withLock(context) { loadUnlocked(context) } }

    @Synchronized
    fun save(context: Context, list: List<Bookmark>) {
        swallow {
            transaction(context) { current ->
                current.clear()
                current.addAll(list)
            }
        }
    }

    /** 若路径已存在返回 false */
    @Synchronized
    fun add(context: Context, bookmark: Bookmark): Boolean =
        guarded(false) {
            transaction(context) { list ->
                if (list.any { it.path == bookmark.path }) {
                    false
                } else {
                    list.add(bookmark)
                    true
                }
            }
        }

    @Synchronized
    fun remove(context: Context, path: String) {
        swallow { take(context, path) }
    }

    /**
     * 删除记录 —— 「撤销」要按**原位**放回去，而不是追加到列表末尾。
     *
     * 书签列表是用户自己排过序的（常用的放前面），追加到末尾等于打乱顺序，
     * 撤了比没撤还烦。所以删除时把下标一起留下。
     */
    data class Removed(val bookmark: Bookmark, val index: Int)

    /**
     * 取出并删除，返回原位信息；路径不存在时返回 null。
     *
     * [remove] 也走这里，两条删除路径共用同一份逻辑，不会出现「撤销得了 / 撤销不了」
     * 两种行为。
     */
    @Synchronized
    fun take(context: Context, path: String): Removed? =
        guarded<Removed?>(null) {
            transaction(context) { list ->
                val index = list.indexOfFirst { it.path == path }
                if (index < 0) {
                    null
                } else {
                    Removed(list.removeAt(index), index)
                }
            }
        }

    /**
     * 撤销删除：插回原来的下标（列表已经变短时收敛到末尾）。
     *
     * 若同名路径已经又被加回来了，就不再插一份 —— 返回 false 由调用方提示。
     */
    @Synchronized
    fun insert(context: Context, removed: Removed): Boolean =
        guarded(false) {
            transaction(context) { list ->
                if (list.any { it.path == removed.bookmark.path }) {
                    false
                } else {
                    list.add(removed.index.coerceIn(0, list.size), removed.bookmark)
                    true
                }
            }
        }

    /** 按路径找条目（用于「移除当前目录的书签」这类只知道路径的入口） */
    @Synchronized
    fun find(context: Context, path: String): Bookmark? =
        guarded<Bookmark?>(null) {
            withLock(context) { loadUnlocked(context) }.firstOrNull { it.path == path }
        }

    /**
     * 重命名书签（只改显示名，不动路径）。
     * 传入空白名时忽略，避免书签在列表里变成无标题条目。
     */
    @Synchronized
    fun rename(context: Context, path: String, newName: String): Boolean {
        val name = newName.trim()
        if (name.isEmpty()) return false
        return guarded(false) {
            transaction(context) { list ->
                val index = list.indexOfFirst { it.path == path }
                if (index < 0) {
                    false
                } else {
                    list[index] = list[index].copy(name = name)
                    true
                }
            }
        }
    }

    @Synchronized
    fun contains(context: Context, path: String): Boolean =
        guarded(false) {
            withLock(context) { loadUnlocked(context) }.any { it.path == path }
        }

    // ---------- 内部实现：以下文件操作都要求调用方已持有跨进程锁 ----------

    /**
     * 公开 API 边界的异常兜底：文件系统异常只记录日志并返回 [default]。
     *
     * 书签操作由主线程调用，磁盘满、目录不可写、锁文件打开失败等情况不能把
     * IOException 抛给界面；旧 SharedPreferences 实现同样不会因此抛出。兜底只包在
     * 公开方法外层，锁内的读—改—写事务与原子写入逻辑不受影响。
     */
    private inline fun <T> guarded(default: T, block: () -> T): T = try {
        block()
    } catch (throwable: Throwable) {
        logSwallowed("书签存储访问失败，已按降级结果处理", throwable)
        default
    }

    /** [guarded] 的 Unit 版本：remove/save 失败时只记录日志。 */
    private inline fun swallow(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            logSwallowed("书签存储写入失败，已忽略", throwable)
        }
    }

    /** 记日志本身也不能在兜底路径上再抛异常。 */
    private fun logSwallowed(message: String, throwable: Throwable) {
        try {
            Log.w(TAG, message, throwable)
        } catch (_: Throwable) {
            // 日志系统不可用时保持静默
        }
    }

    /**
     * 读—改—写事务：[block] 与前后两次文件访问都在同一把跨进程锁内完成。
     *
     * 同进程内再由 @Synchronized 串行化：同一个进程对同一文件重复加锁会抛
     * OverlappingFileLockException，方法级同步可避免这种重入。
     */
    @Synchronized
    private fun <T> transaction(context: Context, block: (MutableList<Bookmark>) -> T): T =
        withLock(context) {
            val list = loadUnlocked(context)
            val result = block(list)
            writeUnlocked(context, list)
            result
        }

    /** 打开锁文件、持有跨进程排它锁执行 [block]；返回时锁与文件句柄一并关闭。 */
    private fun <T> withLock(context: Context, block: () -> T): T {
        context.filesDir.mkdirs()
        return FileOutputStream(File(context.filesDir, LOCK_NAME)).channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun dataFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 读取数据文件；调用方必须已持有锁。数据文件缺失时在锁内完成旧数据的一次性迁移。 */
    private fun loadUnlocked(context: Context): MutableList<Bookmark> {
        val target = dataFile(context)
        if (target.exists()) {
            val raw = try {
                target.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
            return parse(raw)
        }
        val migrated = parse(readLegacy(context))
        try {
            // 即使旧数据为空也写出空数组，标记迁移已完成，之后读取只碰文件。
            writeUnlocked(context, migrated)
        } catch (_: IOException) {
            // 迁移落盘失败时先返回读到的数据，下一次访问会再次尝试迁移。
        }
        return migrated
    }

    /** 旧 SharedPreferences 数据，只在数据文件缺失时读取一次；绝不回写。 */
    private fun readLegacy(context: Context): String? = try {
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_KEY, null)
    } catch (_: Throwable) {
        null
    }

    /** 解析整表 JSON；数据损坏时返回空列表。 */
    private fun parse(raw: String?): MutableList<Bookmark> {
        if (raw.isNullOrEmpty()) return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<Bookmark>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                val path = obj.optString("path")
                if (path.isNotEmpty()) {
                    result.add(Bookmark(name.ifEmpty { path }, path))
                }
            }
            result
        } catch (_: Throwable) {
            // 数据损坏时按空列表处理，不影响应用启动
            mutableListOf()
        }
    }

    /** 序列化并原子提交；调用方必须已持有锁。写临时文件 → fsync → renameTo 替换。 */
    private fun writeUnlocked(context: Context, list: List<Bookmark>) {
        val array = JSONArray()
        list.forEach { bookmark ->
            val obj = JSONObject()
            obj.put("name", bookmark.name)
            obj.put("path", bookmark.path)
            array.put(obj)
        }
        val target = dataFile(context)
        target.parentFile?.mkdirs()
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(array.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                throw IOException("无法提交书签数据")
            }
        } finally {
            temporary.delete()
        }
    }
}
