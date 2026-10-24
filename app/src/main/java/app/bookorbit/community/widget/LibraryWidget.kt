package app.bookorbit.community.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.bookorbit.community.MainActivity
import app.bookorbit.community.R

/** Shortcuts resolve the active account only after the app opens. */
class LibraryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.library_widget)
            listOf(
                    R.id.widget_home to 0,
                    R.id.widget_listen to 1,
                    R.id.widget_read to 2,
                    R.id.widget_downloads to 3,
                )
                .forEach { (view, tab) ->
                    val intent =
                        Intent(context, MainActivity::class.java)
                            .setAction(ACTION)
                            .putExtra("tab", tab)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                    views.setOnClickPendingIntent(
                        view,
                        PendingIntent.getActivity(
                            context,
                            tab,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        const val ACTION = "app.bookorbit.community.WIDGET_OPEN"
    }
}
