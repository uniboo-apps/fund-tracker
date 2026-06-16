package com.uniboo.fundwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.roundToInt

class FundWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.uniboo.fundwidget.TOGGLE"
        const val ACTION_REFRESH = "com.uniboo.fundwidget.REFRESH"
        private const val PREFS = "fundwidget"
        private const val KEY_FUND = "fund"
        private val FUNDS = listOf("sp500", "orukan")

        fun currentFund(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FUND, "sp500")
                ?: "sp500"

        fun toggleFund(ctx: Context) {
            val cur = currentFund(ctx)
            val next = FUNDS[(FUNDS.indexOf(cur) + 1) % FUNDS.size]
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_FUND, next).apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                toggleFund(context)
                asyncUpdateAll(context)
            }
            ACTION_REFRESH,
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> {
                asyncUpdateAll(context)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        asyncUpdateAll(context)
    }

    /** 別スレッドでデータ取得→描画（ネットをメインスレッドで触らない） */
    private fun asyncUpdateAll(context: Context) {
        val pending = goAsync()
        Thread {
            try {
                updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, FundWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val root = DataRepo.load(ctx)
        val key = currentFund(ctx)
        val tapPI = togglePendingIntent(ctx)

        for (id in ids) {
            val (w, h) = sizeFor(ctx, mgr, id)
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val bmp = if (root != null) {
                ChartRenderer.render(key, root, w, h)
            } else {
                ChartRenderer.placeholder(w, h, "データ取得に失敗（タップで再試行）")
            }
            rv.setImageViewBitmap(R.id.widget_image, bmp)
            rv.setOnClickPendingIntent(R.id.widget_image, tapPI)
            mgr.updateAppWidget(id, rv)
        }
    }

    /** ウィジェットの実サイズ(dp)→px。RemoteViews 転送上限を超えないよう上限クランプ。 */
    private fun sizeFor(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
        val opts = mgr.getAppWidgetOptions(id)
        val minWDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val minHDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
        fun dp(v: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
        ).roundToInt()
        val w = dp(if (minWDp > 0) minWDp else 250).coerceIn(300, 520)
        val h = dp(if (minHDp > 0) minHDp else 180).coerceIn(240, 420)
        return w to h
    }

    private fun togglePendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, FundWidgetProvider::class.java).apply { action = ACTION_TOGGLE }
        return PendingIntent.getBroadcast(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
