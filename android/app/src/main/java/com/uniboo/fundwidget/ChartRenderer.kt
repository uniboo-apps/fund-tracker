package com.uniboo.fundwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Web 版 renderWidget をそのまま Canvas に移植して Bitmap を生成 */
object ChartRenderer {

    private val SANS = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val BOLD = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private const val C_BG = "#eef0ee"
    private const val C_GREEN = "#1f9e4d"
    private const val C_RED = "#e23b3b"
    private const val C_TAG = "#8a8f8a"
    private const val C_VALUE = "#111111"
    private const val C_LINE = "#2f6df0"
    private const val C_CYAN_LBL = "#0fb6c9"
    private const val C_PINK_LBL = "#e85a93"
    private const val C_UPD = "#9a9f9a"
    private const val C_DATE = "#7a7f7a"

    fun render(key: String, root: Root, W: Int, H: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = W / 340f

        // カード背景（角丸・外側は透過）
        val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(C_BG) }
        val rad = 18f * u
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), rad, rad, bgP)

        val fs = root.funds[key] ?: root.funds.values.firstOrNull()
        if (fs == null || fs.vals.size < 2) {
            drawCenter(c, W, H, u, "データなし")
            return bmp
        }

        val n = fs.vals.size
        val last = fs.vals[n - 1]
        val prev = fs.vals[n - 2]
        val chg = last - prev
        val pc = chg / prev * 100.0
        val up = chg >= 0
        val accent = if (up) C_GREEN else C_RED

        val m = 16f * u
        val flagW = 30f * u
        val flagH = 20f * u
        val headerTop = m

        // --- ヘッダー：旗バッジ＋名前＋CFD ---
        drawFlag(c, key, m, headerTop, flagW, flagH, u)
        val name = if (key == "sp500") "S&P500" else "ACWI"
        val nameP = textPaint(BOLD, 19f * u, accent)
        val nameX = m + flagW + 7f * u
        val nameBaseline = headerTop + flagH / 2f - (nameP.descent() + nameP.ascent()) / 2f
        c.drawText(name, nameX, nameBaseline, nameP)
        val nameW = nameP.measureText(name)
        val cfdTagP = textPaint(BOLD, 12f * u, C_TAG)
        c.drawText("CFD", nameX + nameW + 7f * u, nameBaseline, cfdTagP)

        // --- ヘッダー右：更新情報（2行） ---
        val updP = textPaint(BOLD, 10f * u, C_UPD).apply { textAlign = Paint.Align.RIGHT }
        val lineH = 12f * u
        drawTextTop(c, "最新 " + fmtMD(fs.dates[n - 1]), W - m, headerTop, updP)
        val gen = root.generatedAt.split(" ")
        if (gen.size >= 2) {
            drawTextTop(c, "更新 " + fmtMD(gen[0]) + " " + gen[1], W - m, headerTop + lineH, updP)
        }

        // --- 変動率（大） ---
        var y = headerTop + flagH + 6f * u
        val pctP = textPaint(BOLD, 30f * u, accent)
        drawTextTop(c, sign(pc) + String.format(Locale.US, "%.2f", pc) + "%", m, y, pctP)
        y += textHeight(pctP) + 2f * u

        // --- レイアウト（チャート / 下段） ---
        val valueP = textPaint(BOLD, 26f * u, C_VALUE)
        val bottomRowH = textHeight(valueP) + 4f * u
        val dateP = textPaint(SANS, 10f * u, C_DATE)
        val dateH = textHeight(dateP) + 4f * u

        val plotLeft = m
        val plotRight = W - m - 46f * u
        val chartTop = y + 2f * u
        val chartBottom = H - m - bottomRowH
        val plotTop = chartTop + 6f * u
        val plotBottom = chartBottom - dateH

        // --- 直近60日のウィンドウ ---
        val start = max(0, n - 60)
        val wv = fs.vals.subList(start, n)
        val wd = fs.dates.subList(start, n)
        val cnt = wv.size
        var lo = wv.min()
        var hi = wv.max()
        val span = max(hi - lo, 1e-9)
        lo -= span * 0.12
        hi += span * 0.12

        fun xAt(i: Int) = plotLeft + (i / (cnt - 1f)) * (plotRight - plotLeft)
        fun yAt(v: Double) =
            (plotTop + (1 - (v - lo) / (hi - lo)) * (plotBottom - plotTop)).toFloat()

        // --- 価格目盛り（上=シアン / 下=ピンク / 中間=薄グレー） ---
        val step = niceNum((hi - lo) / 3.0, true)
        val levels = ArrayList<Double>()
        var g = Math.ceil(lo / step) * step
        while (g <= hi) {
            levels.add(g); g += step
        }
        val gridP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val lblP = textPaint(SANS, 11f * u, C_DATE).apply { textAlign = Paint.Align.LEFT }
        for (i in levels.indices) {
            val ly = yAt(levels[i])
            val top = i == levels.size - 1
            val bot = i == 0
            gridP.color = when {
                top -> Color.argb(217, 25, 195, 214)
                bot -> Color.argb(217, 240, 110, 160)
                else -> Color.argb(56, 150, 150, 150)
            }
            gridP.strokeWidth = if (top || bot) 1.2f * u else 1f * u
            c.drawLine(plotLeft, ly, plotLeft + (plotRight - plotLeft), ly, gridP)
            lblP.color = when {
                top -> Color.parseColor(C_CYAN_LBL)
                bot -> Color.parseColor(C_PINK_LBL)
                else -> Color.parseColor(C_DATE)
            }
            val lblBaseline = ly - (lblP.descent() + lblP.ascent()) / 2f
            c.drawText(comma(Math.round(levels[i])), plotRight + 5f * u, lblBaseline, lblP)
        }

        // --- 黄色帯＝直近14日のレンジ ---
        val rW = min(14, cnt)
        val rStart = cnt - rW
        val rv = wv.subList(rStart, cnt)
        val rHi = rv.max()
        val rLo = rv.min()
        val bx = xAt(rStart)
        val by = yAt(rHi)
        val bh = max(yAt(rLo) - by, 2f)
        val bandFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(77, 245, 205, 70) }
        val bandStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * u; color = Color.argb(140, 220, 180, 40)
        }
        c.drawRect(bx, by, plotLeft + (plotRight - plotLeft), by + bh, bandFill)
        c.drawRect(bx, by, plotLeft + (plotRight - plotLeft), by + bh, bandStroke)

        // --- 価格ライン ---
        val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.parseColor(C_LINE)
            strokeWidth = 1.6f * u; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val path = Path()
        for (i in 0 until cnt) {
            val px = xAt(i); val py = yAt(wv[i])
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        c.drawPath(path, lineP)

        // --- 末端ドット（白リング）＋最新値ピル ---
        val ex = xAt(cnt - 1)
        val ey = yAt(last)
        val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
        dotP.color = Color.WHITE; c.drawCircle(ex, ey, 4.2f * u, dotP)
        dotP.color = Color.parseColor(C_LINE); c.drawCircle(ex, ey, 2.6f * u, dotP)

        val tag = comma(Math.round(last))
        val tagP = textPaint(BOLD, 11f * u, Color.WHITE)
        val tpad = 6f * u
        val pillH = 17f * u
        val pillW = tagP.measureText(tag) + tpad * 2
        var px = ex + 8f * u
        var py = ey - pillH / 2f
        if (px + pillW > W) px = W - pillW
        py = max(0f, min(py, plotBottom - pillH))
        val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(C_LINE) }
        c.drawRoundRect(RectF(px, py, px + pillW, py + pillH), 5f * u, 5f * u, pillBg)
        tagP.textAlign = Paint.Align.LEFT
        val tagBaseline = py + pillH / 2f - (tagP.descent() + tagP.ascent()) / 2f
        c.drawText(tag, px + tpad, tagBaseline, tagP)

        // --- 横軸の日付ラベル ---
        dateP.textAlign = Paint.Align.LEFT
        val ticks = 4
        for (t in 0 until ticks) {
            val i = Math.round(t * (cnt - 1f) / (ticks - 1))
            val d = wd[i]
            val md = fmtMD(d)
            val dx = xAt(i)
            dateP.textAlign = when (t) {
                0 -> Paint.Align.LEFT
                ticks - 1 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            drawTextTop(c, md, dx, plotBottom + 4f * u, dateP)
        }

        // --- 下段：現在値＋変動幅 ---
        val byTop = chartBottom + 2f * u
        val valBaseline = byTop - valueP.ascent()
        c.drawText(tag, m, valBaseline, valueP)
        val vW = valueP.measureText(tag)
        val chgVal = Math.round(chg)
        val chgStr = (if (chgVal >= 0) "+$chgVal" else "$chgVal")
        val chgP = textPaint(BOLD, 17f * u, accent)
        c.drawText(chgStr, m + vW + 10f * u, valBaseline, chgP)

        return bmp
    }

    fun placeholder(W: Int, H: Int, msg: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = W / 340f
        val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(C_BG) }
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), 18f * u, 18f * u, bgP)
        drawCenter(c, W, H, u, msg)
        return bmp
    }

    // ---- helpers ----

    private fun drawCenter(c: Canvas, W: Int, H: Int, u: Float, msg: String) {
        val p = textPaint(BOLD, 15f * u, C_TAG).apply { textAlign = Paint.Align.CENTER }
        c.drawText(msg, W / 2f, H / 2f - (p.descent() + p.ascent()) / 2f, p)
    }

    private fun textPaint(tf: Typeface, size: Float, color: String) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; this.color = Color.parseColor(color)
        }

    private fun textPaint(tf: Typeface, size: Float, color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; this.color = color
        }

    private fun textHeight(p: Paint) = p.descent() - p.ascent()

    /** topY を文字の上端として描画 */
    private fun drawTextTop(c: Canvas, text: String, x: Float, topY: Float, p: Paint) {
        c.drawText(text, x, topY - p.ascent(), p)
    }

    private fun sign(v: Double) = if (v >= 0) "+" else ""

    private fun comma(v: Long): String = String.format(Locale.US, "%,d", v)

    private fun fmtMD(s: String): String {
        // "YYYY-MM-DD" -> "M/D"
        return try {
            "${s.substring(5, 7).toInt()}/${s.substring(8, 10).toInt()}"
        } catch (e: Exception) {
            s
        }
    }

    private fun niceNum(range: Double, round: Boolean): Double {
        val exp = floor(log10(range))
        val fr = range / 10.0.pow(exp)
        val nf = if (round) {
            if (fr < 1.5) 1.0 else if (fr < 3) 2.0 else if (fr < 7) 5.0 else 10.0
        } else {
            if (fr <= 1) 1.0 else if (fr <= 2) 2.0 else if (fr <= 5) 5.0 else 10.0
        }
        return nf * 10.0.pow(exp)
    }

    /** 小さな国旗/地球バッジ */
    private fun drawFlag(c: Canvas, key: String, x: Float, y: Float, w: Float, h: Float, u: Float) {
        val r = 4f * u
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, white)
        // 枠
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * u; color = Color.argb(30, 0, 0, 0)
        }
        c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, edge)

        c.save()
        val clip = Path().apply { addRoundRect(RectF(x, y, x + w, y + h), r, r, Path.Direction.CW) }
        c.clipPath(clip)
        if (key == "sp500") {
            // 米国旗：赤白ストライプ＋青いカントン
            val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#b22234") }
            val stripes = 7
            val sh = h / 13f * (13f / stripes) // ざっくり7本
            var sy = y
            val step = h / stripes
            for (i in 0 until stripes) {
                if (i % 2 == 0) c.drawRect(x, sy, x + w, sy + step, red)
                sy += step
            }
            val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3c3b6e") }
            c.drawRect(x, y, x + w * 0.42f, y + h * 0.54f, blue)
        } else {
            // 地球：青い円＋白い経緯線
            val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2f6df0") }
            val cx = x + w / 2f; val cy = y + h / 2f
            val rr = min(w, h) / 2f - 1f * u
            c.drawCircle(cx, cy, rr, blue)
            val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1f * u; color = Color.argb(180, 255, 255, 255)
            }
            c.drawLine(cx - rr, cy, cx + rr, cy, ln)
            c.drawOval(RectF(cx - rr * 0.5f, cy - rr, cx + rr * 0.5f, cy + rr), ln)
        }
        c.restore()
    }
}
