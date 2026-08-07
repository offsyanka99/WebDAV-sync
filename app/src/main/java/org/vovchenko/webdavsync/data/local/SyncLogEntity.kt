package org.vovchenko.webdavsync.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.vovchenko.webdavsync.data.model.SyncEventType

/** Sync activity/history entries feeding the Overview "Recent changes" card (plan §3, §4.2). */
@Entity(
    tableName = "sync_log",
    foreignKeys = [
        ForeignKey(
            entity = FolderPairEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderPairId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("folderPairId"), Index("timestamp")],
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderPairId: Long,
    val timestamp: Long,
    val eventType: SyncEventType,
    val fileCount: Int = 0,
    val message: String? = null,
)
