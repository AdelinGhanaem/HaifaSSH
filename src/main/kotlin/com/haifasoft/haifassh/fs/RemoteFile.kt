package com.haifasoft.haifassh.fs

/**
 * Metadata for a remote file or directory in SFTP or FTP.
 */
data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: String,
    val lastModified: Long,
    val isLink: Boolean = false,
) {
    fun formattedSize(): String {
        if (isDirectory) return "-"
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.1f GB", gb)
    }
}
