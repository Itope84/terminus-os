package com.terminus.rec

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

class RecWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val i = Intent(ctx, RecWidget::class.java).apply { action = TOGGLE }
            val pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val v = RemoteViews(ctx.packageName, R.layout.widget_rec)
            v.setTextViewText(R.id.widgetLabel, if (RecorderService.running) "■ stop" else "● rec")
            v.setOnClickPendingIntent(R.id.widgetBtn, pi)
            mgr.updateAppWidget(id, v)
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == TOGGLE) {
            val i = Intent(ctx, RecorderService::class.java)
            if (RecorderService.running) ctx.stopService(i)
            else if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    companion object {
        const val TOGGLE = "com.terminus.rec.TOGGLE"
    }
}
