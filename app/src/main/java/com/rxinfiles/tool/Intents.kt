package com.rxinfiles.tool

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * 「用其它应用打开 / 分享」这类对外动作。
 *
 * 统一走 FileProvider 暴露 content:// 而非 file://：
 * Android 7 起 file:// 直接抛 FileUriExposedException，而且 content://
 * 才能把临时读权限精确授予目标应用。
 */
object Intents {

    /** 扩展名 → MIME。MimeTypeMap 覆盖不到的常见类型在这里补上 */
    private val EXTRA_MIME = mapOf(
        "sh" to "text/x-shellscript",
        "c" to "text/x-csrc",
        "h" to "text/x-chdr",
        "cpp" to "text/x-c++src",
        "java" to "text/x-java",
        "kt" to "text/x-kotlin",
        "js" to "application/javascript",
        "json" to "application/json",
        "xml" to "text/xml",
        "md" to "text/markdown",
        "yml" to "text/yaml",
        "yaml" to "text/yaml",
        "ini" to "text/plain",
        "conf" to "text/plain",
        "log" to "text/plain",
        "props" to "text/plain",
        "apk" to "application/vnd.android.package-archive",
        "zip" to "application/zip",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "opus" to "audio/opus",
        "mkv" to "video/x-matroska"
    )

    fun mimeOf(file: File): String {
        val name = file.name.lowercase(Locale.US)
        val ext = name.substringAfterLast('.', "")
        if (ext.isNotEmpty()) {
            EXTRA_MIME[ext]?.let { return it }
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "application/octet-stream"
    }

    /** 拿到可对外暴露的 content:// URI；失败返回 null */
    fun uriFor(context: Context, file: File): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (t: Throwable) {
        // 少数情况（路径不在 file_paths 覆盖范围内、文件不存在）会抛异常
        null
    }

    /** 交给系统里能处理该类型的应用打开 */
    fun openWith(context: Context, file: File): Boolean {
        val uri = uriFor(context, file) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "打开方式"))
            true
        } catch (t: ActivityNotFoundException) {
            false
        } catch (t: Throwable) {
            false
        }
    }

    fun share(context: Context, file: File): Boolean {
        val uri = uriFor(context, file) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_SEND)
                .setType(mimeOf(file))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "分享"))
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun copyToClipboard(context: Context, text: String, label: String = "path") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
