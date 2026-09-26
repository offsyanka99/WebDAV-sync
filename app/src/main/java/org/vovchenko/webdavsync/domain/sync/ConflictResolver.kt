package org.vovchenko.webdavsync.domain.sync

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import org.vovchenko.webdavsync.domain.model.SyncAction

/** Builds "conflicted copy" file names for the losing side of a true conflict (plan §4.2). */
class ConflictResolver @Inject constructor() {

    fun conflictedCopyPath(
        relativePath: String,
        losingSide: SyncAction.Side,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val sideTag = when (losingSide) {
            SyncAction.Side.LOCAL -> "device"
            SyncAction.Side.REMOTE -> "cloud"
        }
        val dateStr = DATE_FORMAT.format(Instant.ofEpochMilli(nowMillis))
        val dir = relativePath.substringBeforeLast('/', "")
        val fileName = relativePath.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', "")
        val baseName = if (extension.isEmpty()) fileName else fileName.removeSuffix(".$extension")
        val newName = if (extension.isEmpty()) {
            "$baseName (conflicted copy, $sideTag, $dateStr)"
        } else {
            "$baseName (conflicted copy, $sideTag, $dateStr).$extension"
        }
        return if (dir.isEmpty()) newName else "$dir/$newName"
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    }
}
