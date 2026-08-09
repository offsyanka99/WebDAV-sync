package org.vovchenko.webdavsync.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import org.vovchenko.webdavsync.MainActivity
import org.vovchenko.webdavsync.R
import org.vovchenko.webdavsync.domain.sync.SyncStatusDisplay
import org.vovchenko.webdavsync.ui.components.Formatters

/** Builds [RemoteViews] for the 4×2 sync status widget. */
object SyncWidgetRenderer {

    fun build(context: Context, state: SyncWidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_sync_4x1)

        // Identical labels/colors to Overview via [SyncStatusDisplay].
        val resolved = SyncStatusDisplay.resolve(state.status, state.syncing)
        val statusColorRes = when (resolved.kind) {
            SyncStatusDisplay.Kind.SYNCING -> R.color.status_warn
            SyncStatusDisplay.Kind.ERROR -> R.color.status_error
            SyncStatusDisplay.Kind.CANCELLED -> R.color.status_warn
            SyncStatusDisplay.Kind.OK, SyncStatusDisplay.Kind.READY -> R.color.status_ok
            SyncStatusDisplay.Kind.OTHER -> R.color.md_theme_on_surface
        }
        views.setTextViewText(R.id.widget_status, resolved.text)
        views.setTextColor(R.id.widget_status, ContextCompat.getColor(context, statusColorRes))

        val lastSync = if (state.lastSyncAtMillis == null) {
            context.getString(R.string.widget_last_sync_none)
        } else {
            context.getString(
                R.string.widget_last_sync,
                Formatters.timestamp(state.lastSyncAtMillis),
                Formatters.duration(state.lastSyncDurationMs),
            )
        }
        views.setTextViewText(R.id.widget_last_sync, lastSync)

        views.setTextViewText(
            R.id.widget_changes,
            context.getString(
                R.string.widget_changes_format,
                state.uploaded,
                state.downloaded,
                state.deletedDevice,
                state.deletedCloud,
            ),
        )

        // Open app when tapping the body (not the Sync button).
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, openApp)

        val syncIntent = Intent(context, SyncWidgetProvider::class.java).apply {
            action = SyncWidgetProvider.ACTION_SYNC_NOW
        }
        val syncPending = PendingIntent.getBroadcast(
            context,
            REQUEST_SYNC,
            syncIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_sync_button, syncPending)
        // Same bind-time colors as WiFi-VPN widgetToggle (primary_container + on_primary_container).
        views.setInt(R.id.widget_sync_button, "setBackgroundResource", R.drawable.widget_button_background)
        views.setTextColor(
            R.id.widget_sync_button,
            ContextCompat.getColor(context, R.color.md_theme_on_primary_container),
        )

        return views
    }

    private const val REQUEST_OPEN_APP = 3101
    private const val REQUEST_SYNC = 3102
}
