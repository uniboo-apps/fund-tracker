package com.uniboo.fundwidget

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView

/** ウィジェットの追加方法を案内する簡易画面。起動時にウィジェットを更新する。 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 起動のたびにウィジェットを最新化（タップで開く＝この経路で更新）
        for (cls in listOf(FundWidgetSmall::class.java, FundWidgetMedium::class.java, FundWidgetLarge::class.java)) {
            sendBroadcast(Intent(this, cls).apply { action = FundWidgetBase.ACTION_REFRESH })
        }

        val tv = TextView(this).apply {
            text = """
                ファンドウィジェット

                ■ 使い方
                1. ホーム画面を長押し →「ウィジェット」を開く
                2.「ファンドウィジェット（小／中／大）」から好きな大きさを配置
                3. ウィジェットをタップ → S&P500 ⇄ オルカン を切替
                4. 右下の ☀／🌙 ボタンをタップ → ライト／ダークを切替（中・大のみ）

                ■ サイズ
                ・大… 旗＋変動率＋チャート＋日付軸＋更新情報（フル表示）
                ・中… チャート＋目盛り＋現在値＋日付軸＋更新情報
                ・小… 変動率＋現在値＋ミニ折れ線（省スペース）
                ※ 配置後にウィジェットの枠をドラッグしてサイズ調整も可

                ■ 仕様
                ・テーマは手動切替（既定はライト＝白）。中・大の右下ボタンで切替
                ・横軸の目盛り線は固定位置（銘柄を切り替えても高さが揃う）
                ・指数データは fund-tracker の公開データを自動取得
                ・約6時間ごと＋アプリ起動時に自動更新
                ・オフライン時は前回データを表示

                このアプリ自体に開く画面はありません。
                ホーム画面のウィジェットでご利用ください。
            """.trimIndent()
            setTextColor(Color.parseColor("#1a1a1a"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.START
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setLineSpacing(0f, 1.3f)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#f5f6f5"))
            addView(tv)
        }
        setContentView(scroll)
    }
}
