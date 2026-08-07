package org.vovchenko.webdavsync.data.local.diagnostics

/**
 * Strips secrets from diagnostic log lines before they are written (security audit: diagnostic
 * log redaction). Prefer logging high-level events rather than raw HTTP headers; this is a
 * last-line defense when exception messages embed credentials.
 */
object DiagnosticRedactor {

    private val authorizationHeader =
        Regex("""(?i)(Authorization\s*:\s*)(Basic|Digest|Bearer)\s+\S+""")
    private val passwordField =
        Regex("""(?i)(password\s*[=:]\s*)([^\s,&;"']+)""")
    private val basicToken =
        Regex("""(?i)\b(Basic)\s+[A-Za-z0-9+/=]{8,}""")
    private val bearerToken =
        Regex("""(?i)\b(Bearer)\s+[A-Za-z0-9._\-+/=]{8,}""")
    private val urlUserInfo =
        Regex("""(://)([^/\s:@]+):([^/\s@]+)@""")

    fun redact(message: String): String {
        var result = message
        result = authorizationHeader.replace(result, "$1$2 ***")
        result = passwordField.replace(result, "$1***")
        result = basicToken.replace(result, "$1 ***")
        result = bearerToken.replace(result, "$1 ***")
        result = urlUserInfo.replace(result, "$1$2:***@")
        return result
    }
}
