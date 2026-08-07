package org.vovchenko.webdavsync.domain.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        val dateStr = DATE_FORMAT.format(Date(nowMillis))
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
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            // Deterministic regardless of device timezone (also fixes ConflictResolverTest, which
            // previously only passed by coincidence on UTC-timezone machines).
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
}
