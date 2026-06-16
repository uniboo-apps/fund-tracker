package com.uniboo.fundwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews

/** 共通ロジック。サイズ違いの 3 つの provider が継承する。 */
abstract class FundWidgetBase : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.uniboo.fundwidget.REFRESH"

        /** provider クラス → 描画モード */
        private val PROVIDER_MODES: List<Pair<Class<*>, ChartRenderer.Mode>> = listOf(
            FundWidgetSmall::class.java to ChartRenderer.Mode.COMPACT,
            FundWidgetMedium::class.java to ChartRenderer.Mode.MEDIUM,
            FundWidgetLarge::class.java to ChartRenderer.Mode.FULL
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) asyncUpdateAll(context)
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
        for ((cls, mode) in PROVIDER_MODES) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            for (id in ids) {
                val rv = RemoteViews(ctx.packageName, R.layout.widget_stack)

                // StackView にカード(2ファンド)を供給するアダプタ
                val svc = Intent(ctx, WidgetRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    putExtra(WidgetRemoteViewsService.EXTRA_MODE, mode.name)
                    // id+mode ごとに一意な data を付け、ファクトリを使い分けさせる
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                rv.setRemoteAdapter(R.id.stack, svc)
                rv.setEmptyView(R.id.stack, R.id.empty)

                // タップ → アプリ(MainActivity)を起動するテンプレート
                rv.setPendingIntentTemplate(R.id.stack, launchTemplate(ctx, id))

                mgr.updateAppWidget(id, rv)
                mgr.notifyAppWidgetViewDataChanged(id, R.id.stack)
            }
        }
    }

    private fun launchTemplate(ctx: Context, reqCode: Int): PendingIntent {
        val launch = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // テンプレート＋fill-in 方式なので Android 12+ では MUTABLE が必須
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getActivity(
            ctx, reqCode, launch, PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
    }
}

class FundWidgetSmall : FundWidgetBase()
class FundWidgetMedium : FundWidgetBase()
class FundWidgetLarge : FundWidgetBase()
