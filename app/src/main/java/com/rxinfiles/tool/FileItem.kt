package com.rxinfiles.tool

/**
 * 文件/目录条目。
 * 与原始实现保持一致的字段结构。
 */
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isScript: Boolean,
    val size: Long,
    val lastModified: Long
)
