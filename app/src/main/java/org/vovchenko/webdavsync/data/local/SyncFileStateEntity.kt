package org.vovchenko.webdavsync.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-file three-way sync baseline used by the conflict-aware diff (plan §4.2, §3).
 * One row per synced file per folder pair; absence of a row means the file was never synced.
 */
@Entity(
    tableName = "sync_file_state",
    foreignKeys = [
        ForeignKey(
            entity = FolderPairEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderPairId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("folderPairId", "relativePath", unique = true)],
)
data class SyncFileStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderPairId: Long,
    val relativePath: String,
    val lastSyncedMtime: Long,
    val lastSyncedSize: Long,
    val lastSyncedHash: String? = null,
)
