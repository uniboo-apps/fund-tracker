package com.uniboo.fundwidget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 指数の時系列（日付・値）を 1 ファンド分保持 */
data class FundSeries(val dates: List<String>, val vals: List<Double>)

/** data.js をパースした結果 */
data class Root(
    val generatedAt: String,
    val funds: Map<String, FundSeries>,
    val usdjpy: FundSeries? = null
)

object DataRepo {

    // fund-tracker の公開データ（main の最新コミット）
    private const val DATA_URL =
        "https://raw.githubusercontent.com/uniboo-apps/fund-tracker/main/data.js"
    private const val CACHE_FILE = "data.js"

    /** ネットから取得。失敗時は端末キャッシュにフォールバック。 */
    fun load(ctx: Context): Root? {
        val raw = loadRaw(ctx) ?: return null
        return try {
            parse(raw)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadRaw(ctx: Context): String? {
        try {
            val con = (URL(DATA_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "FundWidget/1.0")
            }
            con.inputStream.bufferedReader().use { reader ->
                val txt = reader.readText()
                // 取得成功 → キャッシュに保存
                runCatching {
                    ctx.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE).use {
                        it.write(txt.toByteArray(Charsets.UTF_8))
                    }
                }
                return txt
            }
        } catch (e: Exception) {
            // オフライン等 → キャッシュ
            return runCatching {
                ctx.openFileInput(CACHE_FILE).bufferedReader().use { it.readText() }
            }.getOrNull()
        }
    }

    /** `window.DATA = { ... };` から JSON 部分を取り出してパース */
    private fun parse(raw: String): Root {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        val obj = JSONObject(raw.substring(s, e + 1))
        val gen = obj.optString("generatedAt", "")
        val fundsObj = obj.getJSONObject("funds")
        val map = HashMap<String, FundSeries>()
        val keys = fundsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val fo = fundsObj.getJSONObject(key)
            val idx = fo.getJSONArray("index")
            val dates = ArrayList<String>(idx.length())
            val vals = ArrayList<Double>(idx.length())
            for (i in 0 until idx.length()) {
                val p = idx.getJSONArray(i)
                dates.add(p.getString(0))
                vals.add(p.getDouble(1))
            }
            map[key] = FundSeries(dates, vals)
        }
        // 為替（任意）: fx.usdjpy.index
        val usdjpy = runCatching {
            val idx = obj.getJSONObject("fx").getJSONObject("usdjpy").getJSONArray("index")
            val dates = ArrayList<String>(idx.length())
            val vals = ArrayList<Double>(idx.length())
            for (i in 0 until idx.length()) {
                val p = idx.getJSONArray(i)
                dates.add(p.getString(0))
                vals.add(p.getDouble(1))
            }
            FundSeries(dates, vals)
        }.getOrNull()
        return Root(gen, map, usdjpy)
    }
}
