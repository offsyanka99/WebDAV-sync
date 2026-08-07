package org.vovchenko.webdavsync.data.local.saf

/**
 * Some SAF document providers silently rewrite characters that aren't valid in the underlying
 * filesystem (FAT/exFAT-style restrictions) when a file/directory is created — e.g. a remote name
 * containing `?` gets persisted locally as `_`. If the sync engine keeps comparing against the
 * original, un-rewritten remote name it never finds a local match, so it re-downloads the remote
 * file and re-uploads the rewritten local copy on every pass, producing duplicate files. Applying
 * the same rewrite ourselves before matching keeps local/remote/baseline path keys in agreement.
 */
object LocalNameSanitizer {
    private val UNSAFE_CHARS = charArrayOf('\\', '*', '?', '"', '<', '>', '|', ':')

    fun sanitizeRelativePath(relativePath: String): String =
        relativePath.split('/').joinToString("/") { sanitizeSegment(it) }

    private fun sanitizeSegment(segment: String): String {
        var result = segment
        for (c in UNSAFE_CHARS) result = result.replace(c, '_')
        // Some providers also strip trailing dots/spaces from the segment.
        return result.trimEnd(' ', '.').ifEmpty { "_" }
    }
}
