package com.uniboo.fundwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** 共通ロジック。サイズ違いの 3 つの provider が継承する。 */
abstract class FundWidgetBase : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.uniboo.fundwidget.TOGGLE"
        const val ACTION_THEME = "com.uniboo.fundwidget.THEME"
        const val ACTION_REFRESH = "com.uniboo.fundwidget.REFRESH"
        private const val PREFS = "fundwidget"
        private const val KEY_FUND = "fund"
        private const val KEY_THEME = "theme"
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

        /** テーマ。既定はライト（白）。 */
        fun isDark(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, "light") == "dark"

        fun toggleTheme(ctx: Context) {
            val next = if (isDark(ctx)) "light" else "dark"
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, next).apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> { toggleFund(context); asyncUpdateAll(context) }
            ACTION_THEME -> { toggleTheme(context); asyncUpdateAll(context) }
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
        val dark = isDark(ctx)
        val tapPI = broadcastPI(ctx, ACTION_TOGGLE, 0)
        val themePI = broadcastPI(ctx, ACTION_THEME, 1)

        for ((cls, mode) in PROVIDER_MODES) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            val showTheme = mode != ChartRenderer.Mode.COMPACT
            for (id in ids) {
                val (w, h) = sizeFor(ctx, mgr, id, mode)
                val rv = RemoteViews(ctx.packageName, R.layout.widget)
                val bmp = if (root != null) ChartRenderer.render(key, root, w, h, mode, dark, showTheme)
                          else ChartRenderer.placeholder(w, h, "取得失敗（タップで再試行）", dark)
                rv.setImageViewBitmap(R.id.widget_image, bmp)
                rv.setOnClickPendingIntent(R.id.widget_image, tapPI)
                // 中・大のみ右下にテーマ切替ボタンを出す
                if (showTheme) {
                    rv.setViewVisibility(R.id.theme_hit, View.VISIBLE)
                    rv.setOnClickPendingIntent(R.id.theme_hit, themePI)
                } else {
                    rv.setViewVisibility(R.id.theme_hit, View.GONE)
                }
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
        val (defW, defH) = when (mode) {
            ChartRenderer.Mode.COMPACT -> 150 to 56
            ChartRenderer.Mode.MEDIUM -> 200 to 120
            ChartRenderer.Mode.FULL -> 260 to 200
        }
        val w = px(if (minWDp > 0) minWDp else defW).coerceIn(150, 520)
        val h = px(if (minHDp > 0) minHDp else defH).coerceIn(70, 430)
        return w to h
    }

    /** どのサイズをタップしても Large 経由で全ウィジェットを更新 */
    private fun broadcastPI(ctx: Context, action: String, reqCode: Int): PendingIntent {
        val intent = Intent(ctx, FundWidgetLarge::class.java).apply { this.action = action }
        val imm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(
            ctx, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or imm
        )
    }
}

class FundWidgetSmall : FundWidgetBase()
class FundWidgetMedium : FundWidgetBase()
class FundWidgetLarge : FundWidgetBase()
