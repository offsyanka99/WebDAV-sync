package org.vovchenko.webdavsync.data.remote.push

import okhttp3.HttpUrl

/** True when [a] and [b] share scheme, host, and effective port (RFC 6454 origin). */
fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
    a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
