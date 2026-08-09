package org.vovchenko.webdavsync.domain.sync

/**
 * Single source of truth for the Status line shown on Overview and the home-screen widget.
 *
 * Both surfaces must call [resolve] with the same inputs:
 * - [lastSyncStatus] from the most recently finished folder pair (DB)
 * - [syncing] true only while a WorkManager sync worker is **RUNNING**
 *
 * Never paint a forced "Syncing…" that outlives the worker — that left the widget on
 * "Syncing…" while Overview already showed ERROR after a finished failed pass.
 */
object SyncStatusDisplay {

    enum class Kind {
        SYNCING,
        OK,
        ERROR,
        CANCELLED,
        READY,
        OTHER,
    }

    data class Resolved(
        val text: String,
        val kind: Kind,
    )

    fun resolve(lastSyncStatus: String?, syncing: Boolean): Resolved {
        if (syncing) {
            return Resolved(text = TEXT_SYNCING, kind = Kind.SYNCING)
        }
        return when (lastSyncStatus?.uppercase()) {
            "OK" -> Resolved(text = "OK", kind = Kind.OK)
            "ERROR" -> Resolved(text = "ERROR", kind = Kind.ERROR)
            "CANCELLED" -> Resolved(text = "CANCELLED", kind = Kind.CANCELLED)
            null, "" -> Resolved(text = TEXT_READY, kind = Kind.READY)
            "READY" -> Resolved(text = TEXT_READY, kind = Kind.READY)
            else -> Resolved(text = lastSyncStatus, kind = Kind.OTHER)
        }
    }

    const val TEXT_SYNCING = "Sync in process..."
    const val TEXT_READY = "Ready"
}
