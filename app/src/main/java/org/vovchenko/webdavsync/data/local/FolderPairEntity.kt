package org.vovchenko.webdavsync.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.vovchenko.webdavsync.data.model.SyncMethod

/** A configured local↔cloud folder pair (requirements.md §Add folder window, plan §3). */
@Entity(
    tableName = "folder_pairs",
    foreignKeys = [
        ForeignKey(
            entity = WebDavAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId")],
)
data class FolderPairEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val name: String,
    val remoteFolderPath: String,
    val localFolderUri: String,
    val syncMethod: SyncMethod = SyncMethod.TWO_WAY,
    val excludeHiddenFiles: Boolean = true,
    // Relative paths/globs to skip, recursive sync otherwise — plan §4.7.
    val excludedSubfolders: List<String> = emptyList(),
    val deleteEmptyFolders: Boolean = false,
    val instantUpload: Boolean = false,
    val enabled: Boolean = true,
    val lastSyncAt: Long? = null,
    val lastSyncDurationMs: Long? = null,
    val lastSyncStatus: String? = null,
    /** Full local-tree fingerprint from the last successful scan; used to skip idle to-cloud passes. */
    val lastLocalFingerprint: Long? = null,
)
