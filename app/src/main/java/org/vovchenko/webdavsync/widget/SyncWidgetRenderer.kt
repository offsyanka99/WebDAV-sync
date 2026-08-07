package org.vovchenko.webdavsync.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import org.vovchenko.webdavsync.MainActivity
import org.vovchenko.webdavsync.R
import org.vovchenko.webdavsync.ui.components.Formatters

/** Builds [RemoteViews] for the 4×2 sync status widget. */
object SyncWidgetRenderer {

    fun build(context: Context, state: SyncWidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_sync_4x1)

        val (statusText, statusColorRes) = when {
            state.syncing -> context.getString(R.string.widget_status_syncing) to R.color.status_warn
            state.status.equals("ERROR", ignoreCase = true) ->
                context.getString(R.string.widget_status_error) to R.color.md_theme_error
            state.status.equals("CANCELLED", ignoreCase = true) ->
                context.getString(R.string.widget_status_cancelled) to R.color.status_warn
            state.status.equals("OK", ignoreCase = true) ->
                context.getString(R.string.widget_status_ok) to R.color.status_ok
            state.status.isBlank() || state.status.equals("Ready", ignoreCase = true) ->
                context.getString(R.string.widget_status_ready) to R.color.status_ok
            else -> state.status to R.color.md_theme_on_surface
        }
        views.setTextViewText(R.id.widget_status, statusText)
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
        // Ensure white-on-blue pill (RemoteViews can ignore some theme text colors on OEM skins).
        views.setTextColor(
            R.id.widget_sync_button,
            ContextCompat.getColor(context, R.color.widget_button_text),
        )

        return views
    }

    private const val REQUEST_OPEN_APP = 3101
    private const val REQUEST_SYNC = 3102
}
