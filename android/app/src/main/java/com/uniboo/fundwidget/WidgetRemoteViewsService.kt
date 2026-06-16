package com.uniboo.fundwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlin.math.roundToInt

/**
 * StackView の各カード（S&P500 / オルカン）を供給するサービス。
 * スワイプでカードをめくると 2 ファンドが切り替わる。
 */
class WidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val mode = runCatching {
            ChartRenderer.Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "FULL")
        }.getOrDefault(ChartRenderer.Mode.FULL)
        return FundStackFactory(applicationContext, appWidgetId, mode)
    }

    companion object {
        const val EXTRA_MODE = "mode"
    }
}

private class FundStackFactory(
    private val ctx: Context,
    private val appWidgetId: Int,
    private val mode: ChartRenderer.Mode
) : RemoteViewsService.RemoteViewsFactory {

    private val funds = listOf("sp500", "orukan")
    private var root: Root? = null

    override fun onCreate() {}

    // 別スレッドで呼ばれる（ネットワーク取得OK）
    override fun onDataSetChanged() { root = DataRepo.load(ctx) }

    override fun onDestroy() {}

    override fun getCount(): Int = funds.size

    override fun getViewAt(position: Int): RemoteViews {
        val key = funds[position]
        val (w, h) = sizeFor()
        val dark = isDark()
        val rv = RemoteViews(ctx.packageName, R.layout.widget_item)
        val r = root
        val bmp = if (r != null) ChartRenderer.render(key, r, w, h, mode, dark)
                  else ChartRenderer.placeholder(w, h, "取得失敗（タップでアプリを開く）", dark)
        rv.setImageViewBitmap(R.id.item_image, bmp)
        // タップ → テンプレート(アプリ起動)に流す
        rv.setOnClickFillInIntent(R.id.item_image, Intent())
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true

    private fun isDark(): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun sizeFor(): Pair<Int, Int> {
        val mgr = AppWidgetManager.getInstance(ctx)
        val opts = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
            mgr.getAppWidgetOptions(appWidgetId) else null
        val minWDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val minHDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        fun px(v: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
        ).roundToInt()
        val (defW, defH) = when (mode) {
            ChartRenderer.Mode.COMPACT -> 150 to 56
            ChartRenderer.Mode.MEDIUM -> 200 to 120
            ChartRenderer.Mode.FULL -> 260 to 200
        }
        // StackView はカードに少し余白を付けるので内側を気持ち小さめに
        val w = px(if (minWDp > 0) minWDp else defW).coerceIn(150, 520)
        val h = px(if (minHDp > 0) minHDp else defH).coerceIn(70, 430)
        return w to h
    }
}
