package org.vovchenko.webdavsync.data.local.saf

/** Shared glob-style exclusion matcher for the per-folder-pair exclusion list (plan §4.7). */
object PathExclusion {
    fun isExcluded(relativePath: String, patterns: List<String>): Boolean =
        patterns.any { pattern -> globToRegex(pattern).matches(relativePath) }

    private fun globToRegex(pattern: String): Regex {
        // Regex.escape() wraps the *whole* string in \Q..\E rather than escaping character by
        // character, so splitting on '*' first (and escaping each literal segment) is required
        // for '*' to actually behave as a wildcard instead of a literal asterisk.
        val regex = pattern.split("*").joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex(/.*)?$")
    }
}
