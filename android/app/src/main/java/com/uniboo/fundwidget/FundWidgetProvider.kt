package com.uniboo.fundwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** 共通ロジック。サイズ違いの 3 つの provider が継承する。 */
abstract class FundWidgetBase : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.uniboo.fundwidget.TOGGLE"
        const val ACTION_REFRESH = "com.uniboo.fundwidget.REFRESH"
        private const val PREFS = "fundwidget"
        private const val KEY_FUND = "fund"
        private val FUNDS = listOf("sp500", "orukan")

        /** provider クラス → 描画モード */
        private val PROVIDER_MODES: List<Pair<Class<*>, ChartRenderer.Mode>> = listOf(
            FundWidgetSmall::class.java to ChartRenderer.Mode.COMPACT,
            FundWidgetMedium::class.java to ChartRenderer.Mode.MEDIUM,
            FundWidgetLarge::class.java to ChartRenderer.Mode.FULL
        )

        fun currentFund(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FUND, "sp500") ?: "sp500"

        fun toggleFund(ctx: Context) {
            val next = FUNDS[(FUNDS.indexOf(currentFund(ctx)) + 1) % FUNDS.size]
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FUND, next).apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> { toggleFund(context); asyncUpdateAll(context) }
            ACTION_REFRESH -> asyncUpdateAll(context)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        asyncUpdateAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        asyncUpdateAll(context)
    }

    private fun asyncUpdateAll(context: Context) {
        val pending = goAsync()
        Thread {
            try { updateAll(context.applicationContext) } finally { pending.finish() }
        }.start()
    }

    private fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val root = DataRepo.load(ctx)
        val key = currentFund(ctx)
        val tapPI = togglePendingIntent(ctx)

        for ((cls, mode) in PROVIDER_MODES) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            for (id in ids) {
                val (w, h) = sizeFor(ctx, mgr, id, mode)
                val rv = RemoteViews(ctx.packageName, R.layout.widget)
                val bmp = if (root != null) ChartRenderer.render(key, root, w, h, mode)
                          else ChartRenderer.placeholder(w, h, "取得失敗（タップで再試行）")
                rv.setImageViewBitmap(R.id.widget_image, bmp)
                rv.setOnClickPendingIntent(R.id.widget_image, tapPI)
                mgr.updateAppWidget(id, rv)
            }
        }
    }

    private fun sizeFor(ctx: Context, mgr: AppWidgetManager, id: Int, mode: ChartRenderer.Mode): Pair<Int, Int> {
        val opts = mgr.getAppWidgetOptions(id)
        val minWDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minHDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        fun px(v: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
        ).roundToInt()
        // モードごとの既定サイズ（dp）。実サイズが取れればそちらを優先。
        val (defW, defH) = when (mode) {
            ChartRenderer.Mode.COMPACT -> 150 to 56
            ChartRenderer.Mode.MEDIUM -> 200 to 120
            ChartRenderer.Mode.FULL -> 260 to 200
        }
        val w = px(if (minWDp > 0) minWDp else defW).coerceIn(150, 520)
        val h = px(if (minHDp > 0) minHDp else defH).coerceIn(70, 430)
        return w to h
    }

    private fun togglePendingIntent(ctx: Context): PendingIntent {
        // どのサイズをタップしても Large 経由で全ウィジェットを更新
        val intent = Intent(ctx, FundWidgetLarge::class.java).apply { action = ACTION_TOGGLE }
        return PendingIntent.getBroadcast(
            ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class FundWidgetSmall : FundWidgetBase()
class FundWidgetMedium : FundWidgetBase()
class FundWidgetLarge : FundWidgetBase()
