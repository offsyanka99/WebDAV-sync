package org.vovchenko.webdavsync.util

/** Joins folder-pair-relative path segments (local SAF tree and remote scan walks). */
object RelativePaths {
    fun joinRelative(prefix: String, name: String): String =
        if (prefix.isEmpty()) name else "$prefix/$name"
}
