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

/** Web 版 renderWidget を Canvas に移植。サイズに応じて FULL/MEDIUM/COMPACT を描き分ける。 */
object ChartRenderer {

    enum class Mode { FULL, MEDIUM, COMPACT }

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

    fun render(key: String, root: Root, W: Int, H: Int, mode: Mode): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val uBg = W / 340f
        val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(C_BG) }
        val rad = (if (mode == Mode.COMPACT) 14f else 18f) * uBg
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), rad, rad, bgP)

        val fs = root.funds[key] ?: root.funds.values.firstOrNull()
        if (fs == null || fs.vals.size < 2) {
            drawCenter(c, W, H, uBg, "データなし")
            return bmp
        }

        val n = fs.vals.size
        val last = fs.vals[n - 1]
        val prev = fs.vals[n - 2]
        val chg = last - prev
        val pc = chg / prev * 100.0
        val up = chg >= 0
        val accent = if (up) C_GREEN else C_RED

        when (mode) {
            Mode.COMPACT -> drawCompact(c, key, fs, root, last, chg, pc, accent, W, H)
            else -> drawCard(c, key, fs, root, last, chg, pc, accent, W, H, mode)
        }
        return bmp
    }

    fun placeholder(W: Int, H: Int, msg: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = W / 340f
        val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(C_BG) }
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), 14f * u, 14f * u, bgP)
        drawCenter(c, W, H, u, msg)
        return bmp
    }

    // ---------- FULL / MEDIUM ----------

    private fun drawCard(
        c: Canvas, key: String, fs: FundSeries, root: Root,
        last: Double, chg: Double, pc: Double, accent: String,
        W: Int, H: Int, mode: Mode
    ) {
        val full = mode == Mode.FULL
        val targetW = if (full) 340f else 300f
        val targetH = if (full) 250f else 170f
        val u = min(W / targetW, H / targetH)
        val m = 14f * u

        // header: 旗＋名前＋CFD
        val flagW = 30f * u
        val flagH = 20f * u
        drawFlag(c, key, m, m, flagW, flagH, u)
        val name = if (key == "sp500") "S&P500" else "ACWI"
        val nameP = textPaint(BOLD, 19f * u, accent)
        val nameX = m + flagW + 7f * u
        val nameBase = m + flagH / 2f - (nameP.descent() + nameP.ascent()) / 2f
        c.drawText(name, nameX, nameBase, nameP)
        val nameW = nameP.measureText(name)
        c.drawText("CFD", nameX + nameW + 7f * u, nameBase, textPaint(BOLD, 12f * u, C_TAG))

        // header right: 更新情報
        val updP = textPaint(BOLD, 10f * u, C_UPD).apply { textAlign = Paint.Align.RIGHT }
        drawTextTop(c, "最新 " + fmtMD(fs.dates[fs.dates.size - 1]), W - m, m, updP)
        if (full) {
            val gen = root.generatedAt.split(" ")
            if (gen.size >= 2) drawTextTop(c, "更新 " + fmtMD(gen[0]) + " " + gen[1], W - m, m + 12f * u, updP)
        }

        var y = m + flagH + 6f * u

        // 変動率
        val pctP = textPaint(BOLD, (if (full) 30f else 26f) * u, accent)
        drawTextTop(c, sign(pc) + fmt2(pc) + "%", m, y, pctP)
        y += textHeight(pctP) + 2f * u

        // レイアウト
        val valueP = textPaint(BOLD, (if (full) 26f else 22f) * u, C_VALUE)
        val footerH = textHeight(valueP) + 4f * u
        val dateP = textPaint(SANS, 10f * u, C_DATE)
        val dateH = if (full) textHeight(dateP) + 4f * u else 0f
        val chartBottom = H - m - footerH
        val plot = RectF(m, y + 6f * u, W - m - 44f * u, chartBottom - dateH)
        drawChart(c, fs, last, plot, u, W.toFloat(), full, dateP)

        // 下段：現在値＋変動幅
        val valBase = chartBottom + 2f * u - valueP.ascent()
        val valStr = comma(Math.round(last))
        c.drawText(valStr, m, valBase, valueP)
        val vW = valueP.measureText(valStr)
        c.drawText(chgStr(chg), m + vW + 10f * u, valBase, textPaint(BOLD, (if (full) 17f else 15f) * u, accent))
    }

    /** 折れ線＋目盛り＋黄色帯＋ピル＋（必要なら）日付軸 */
    private fun drawChart(
        c: Canvas, fs: FundSeries, last: Double, plot: RectF,
        u: Float, cardW: Float, showDates: Boolean, dateP: Paint
    ) {
        val (wv, wd) = window(fs)
        val cnt = wv.size
        var lo = wv.min()
        var hi = wv.max()
        val span = max(hi - lo, 1e-9)
        lo -= span * 0.12; hi += span * 0.12

        fun xAt(i: Int) = plot.left + (i / (cnt - 1f)) * (plot.right - plot.left)
        fun yAt(v: Double) = (plot.top + (1 - (v - lo) / (hi - lo)) * (plot.bottom - plot.top)).toFloat()

        // 目盛り
        val step = niceNum((hi - lo) / 3.0, true)
        val levels = ArrayList<Double>()
        var g = Math.ceil(lo / step) * step
        while (g <= hi) { levels.add(g); g += step }
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
            c.drawLine(plot.left, ly, plot.right, ly, gridP)
            lblP.color = when {
                top -> Color.parseColor(C_CYAN_LBL)
                bot -> Color.parseColor(C_PINK_LBL)
                else -> Color.parseColor(C_DATE)
            }
            c.drawText(comma(Math.round(levels[i])), plot.right + 5f * u, ly - (lblP.descent() + lblP.ascent()) / 2f, lblP)
        }

        // 黄色帯（直近14日レンジ）
        val rW = min(14, cnt)
        val rv = wv.subList(cnt - rW, cnt)
        val by = yAt(rv.max())
        val bh = max(yAt(rv.min()) - by, 2f)
        c.drawRect(xAt(cnt - rW), by, plot.right, by + bh,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(77, 245, 205, 70) })
        c.drawRect(xAt(cnt - rW), by, plot.right, by + bh,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1f * u; color = Color.argb(140, 220, 180, 40)
            })

        // ライン
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

        // 末端ドット＋最新値ピル
        val ex = xAt(cnt - 1); val ey = yAt(last)
        val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
        dotP.color = Color.WHITE; c.drawCircle(ex, ey, 4.2f * u, dotP)
        dotP.color = Color.parseColor(C_LINE); c.drawCircle(ex, ey, 2.6f * u, dotP)
        val tag = comma(Math.round(last))
        val tagP = textPaint(BOLD, 11f * u, Color.WHITE)
        val tpad = 6f * u; val pillH = 17f * u
        val pillW = tagP.measureText(tag) + tpad * 2
        var px = ex + 8f * u
        if (px + pillW > cardW) px = cardW - pillW
        val py = max(0f, min(ey - pillH / 2f, plot.bottom - pillH))
        c.drawRoundRect(RectF(px, py, px + pillW, py + pillH), 5f * u, 5f * u,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(C_LINE) })
        tagP.textAlign = Paint.Align.LEFT
        c.drawText(tag, px + tpad, py + pillH / 2f - (tagP.descent() + tagP.ascent()) / 2f, tagP)

        // 日付軸
        if (showDates) {
            val ticks = 4
            for (t in 0 until ticks) {
                val i = Math.round(t * (cnt - 1f) / (ticks - 1))
                dateP.textAlign = when (t) {
                    0 -> Paint.Align.LEFT
                    ticks - 1 -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                drawTextTop(c, fmtMD(wd[i]), xAt(i), plot.bottom + 4f * u, dateP)
            }
        }
    }

    // ---------- COMPACT（小） ----------

    private fun drawCompact(
        c: Canvas, key: String, fs: FundSeries, root: Root,
        last: Double, chg: Double, pc: Double, accent: String, W: Int, H: Int
    ) {
        val u = min(W / 300f, H / 120f)
        val pad = 12f * u
        val name = if (key == "sp500") "S&P500" else "ACWI"

        // 左：旗＋名前
        val flagW = 20f * u; val flagH = 13f * u
        drawFlag(c, key, pad, pad, flagW, flagH, u)
        val nameP = textPaint(BOLD, 13f * u, accent)
        c.drawText(name, pad + flagW + 5f * u, pad + flagH / 2f - (nameP.descent() + nameP.ascent()) / 2f, nameP)

        // 左：変動率
        val pctP = textPaint(BOLD, 22f * u, accent)
        drawTextTop(c, sign(pc) + fmt2(pc) + "%", pad, pad + flagH + 3f * u, pctP)

        // 左下：現在値＋変動幅
        val valP = textPaint(BOLD, 14f * u, C_VALUE)
        val valStr = comma(Math.round(last))
        val valBase = H - pad - valP.descent()
        c.drawText(valStr, pad, valBase, valP)
        val vW = valP.measureText(valStr)
        c.drawText(chgStr(chg), pad + vW + 6f * u, valBase, textPaint(BOLD, 11f * u, accent))

        // 右：スパークライン（軸なし）
        drawSparkline(c, fs, last, RectF(W * 0.54f, pad, W - pad, H - pad), u)
    }

    private fun drawSparkline(c: Canvas, fs: FundSeries, last: Double, plot: RectF, u: Float) {
        val (wv, _) = window(fs)
        val cnt = wv.size
        var lo = wv.min(); var hi = wv.max()
        val span = max(hi - lo, 1e-9); lo -= span * 0.1; hi += span * 0.1
        fun xAt(i: Int) = plot.left + (i / (cnt - 1f)) * (plot.right - plot.left)
        fun yAt(v: Double) = (plot.top + (1 - (v - lo) / (hi - lo)) * (plot.bottom - plot.top)).toFloat()
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
        val ex = xAt(cnt - 1); val ey = yAt(last)
        val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
        dotP.color = Color.WHITE; c.drawCircle(ex, ey, 3.6f * u, dotP)
        dotP.color = Color.parseColor(C_LINE); c.drawCircle(ex, ey, 2.4f * u, dotP)
    }

    // ---------- helpers ----------

    private fun window(fs: FundSeries): Pair<List<Double>, List<String>> {
        val n = fs.vals.size
        val start = max(0, n - 60)
        return fs.vals.subList(start, n) to fs.dates.subList(start, n)
    }

    private fun drawCenter(c: Canvas, W: Int, H: Int, u: Float, msg: String) {
        val p = textPaint(BOLD, 14f * u, C_TAG).apply { textAlign = Paint.Align.CENTER }
        c.drawText(msg, W / 2f, H / 2f - (p.descent() + p.ascent()) / 2f, p)
    }

    private fun textPaint(tf: Typeface, size: Float, color: String) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; this.color = Color.parseColor(color) }

    private fun textPaint(tf: Typeface, size: Float, color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; this.color = color }

    private fun textHeight(p: Paint) = p.descent() - p.ascent()

    private fun drawTextTop(c: Canvas, text: String, x: Float, topY: Float, p: Paint) {
        c.drawText(text, x, topY - p.ascent(), p)
    }

    private fun sign(v: Double) = if (v >= 0) "+" else ""
    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun chgStr(chg: Double): String {
        val v = Math.round(chg); return if (v >= 0) "+$v" else "$v"
    }
    private fun comma(v: Long): String = String.format(Locale.US, "%,d", v)
    private fun fmtMD(s: String): String = try {
        "${s.substring(5, 7).toInt()}/${s.substring(8, 10).toInt()}"
    } catch (e: Exception) { s }

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

    private fun drawFlag(c: Canvas, key: String, x: Float, y: Float, w: Float, h: Float, u: Float) {
        val r = 4f * u
        c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * u; color = Color.argb(30, 0, 0, 0)
        })
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(x, y, x + w, y + h), r, r, Path.Direction.CW) })
        if (key == "sp500") {
            val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#b22234") }
            val stripes = 7
            val step = h / stripes
            var sy = y
            for (i in 0 until stripes) {
                if (i % 2 == 0) c.drawRect(x, sy, x + w, sy + step, red)
                sy += step
            }
            c.drawRect(x, y, x + w * 0.42f, y + h * 0.54f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3c3b6e") })
        } else {
            val cx = x + w / 2f; val cy = y + h / 2f
            val rr = min(w, h) / 2f - 1f * u
            c.drawCircle(cx, cy, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2f6df0") })
            val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1f * u; color = Color.argb(180, 255, 255, 255)
            }
            c.drawLine(cx - rr, cy, cx + rr, cy, ln)
            c.drawOval(RectF(cx - rr * 0.5f, cy - rr, cx + rr * 0.5f, cy + rr), ln)
        }
        c.restore()
    }
}
