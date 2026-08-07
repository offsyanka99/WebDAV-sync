package org.vovchenko.webdavsync.data.model

/** Per-folder-pair sync direction (Add Folder Pair screen, requirements.md §Add folder window). */
enum class SyncMethod {
    TWO_WAY,
    TO_DEVICE,
    TO_CLOUD,
}

/** WebDAV auth scheme, set on an account after the auto-detection probe (plan §4.4). */
enum class AuthScheme {
    BASIC,
    DIGEST,
}

/** SyncLogEntity event kinds (plan §4.2, §4.5 data model). */
enum class SyncEventType {
    SYNC_START,
    SYNC_END,
    UPLOAD,
    DOWNLOAD,
    DELETE_DEVICE,
    DELETE_CLOUD,
    CONFLICT,
    ERROR,
}
