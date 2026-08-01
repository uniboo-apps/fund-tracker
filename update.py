#!/usr/bin/env python3
"""Fetch fund NAV / stock price and index data, generate data.js  (CI equivalent of update.ps1)"""

import json
import re
import sys
import os
import requests
from datetime import datetime, timezone, timedelta
from urllib.parse import quote

UA  = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
JST = timezone(timedelta(hours=9))

STOCKS_FILE = "stocks.json"
# 個別株の比較指数の既定値（stocks.json で上書き可能）
DEFAULT_STOCK_INDEX = dict(indexSym="^N225", indexName="日経平均株価")

FUNDS = [
    dict(
        key="orukan",
        name="eMAXIS Slim 全世界株式（オール・カントリー）",
        short="オルカン",
        isin="JP90C000H1T1",
        code="0331418A",
        indexName="MSCI ACWI (ACWI ETF)",
        indexSym="ACWI",
        flag="world",
        widgetLabel="ACWI",
    ),
    dict(
        key="sp500",
        name="eMAXIS Slim 米国株式（S&P500）",
        short="S&P500",
        isin="JP90C000GKC6",
        code="03311187",
        indexName="S&P 500 指数",
        indexSym="^GSPC",
        flag="us",
        widgetLabel="S&P500",
    ),
]

# 為替（ウィジェット右下に表示）。Yahoo Finance の USD/JPY = "JPY=X"
FX = dict(key="usdjpy", name="ドル/円", indexSym="JPY=X")


def get_nav(isin, code):
    url = (
        "https://toushin-lib.fwg.ne.jp/FdsWeb/FDST030000/csv-file-download"
        f"?isinCd={isin}&associFundCd={code}"
    )
    r = requests.get(url, timeout=60)
    r.raise_for_status()
    txt = r.content.decode("shift_jis")
    out = []
    for line in txt.splitlines():
        m = re.match(r"^(\d{4})年(\d{2})月(\d{2})日,([0-9]+),", line)
        if m:
            out.append([f"{m.group(1)}-{m.group(2)}-{m.group(3)}", int(m.group(4))])
    return out


def fetch_chart(sym):
    """Yahoo Finance chart API を叩いて result オブジェクトを返す"""
    url = (
        f"https://query1.finance.yahoo.com/v8/finance/chart/{quote(sym, safe='=.')}"
        "?range=10y&interval=1d"
    )
    r = requests.get(url, headers={"User-Agent": UA}, timeout=60)
    r.raise_for_status()
    return r.json()["chart"]["result"][0]


def series_from(res, adjusted=False):
    """timestamp と終値から [["YYYY-MM-DD", value], ...] を作る。
    adjusted=True なら分割・配当調整済みの adjclose を優先（個別株用）。"""
    closes = None
    if adjusted:
        adj = res["indicators"].get("adjclose")
        if adj and adj[0].get("adjclose"):
            closes = adj[0]["adjclose"]
    if closes is None:
        closes = res["indicators"]["quote"][0]["close"]
    out = []
    for ts, cl in zip(res["timestamp"], closes):
        if cl is not None:
            d = datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%d")
            out.append([d, round(float(cl), 2)])
    return out


def get_index(sym):
    return series_from(fetch_chart(sym))


_index_cache = {}


def get_index_cached(sym):
    if sym not in _index_cache:
        _index_cache[sym] = get_index(sym)
    return _index_cache[sym]


def auto_key(symbol):
    """"4755.T" -> "s4755" / "AAPL" -> "aapl"（数字始まりは JS のキー順が崩れるので s を付ける）"""
    k = re.sub(r"\.[A-Za-z]+$", "", symbol).lower()
    k = re.sub(r"[^a-z0-9]+", "_", k).strip("_") or "stock"
    return ("s" + k) if k[0].isdigit() else k


def load_stocks():
    """stocks.json（任意）を読む。無ければ個別株なしで従来通り動く。"""
    if not os.path.exists(STOCKS_FILE):
        return []
    with open(STOCKS_FILE, encoding="utf-8") as fh:
        cfg = json.load(fh)
    default_sym = cfg.get("indexSym", DEFAULT_STOCK_INDEX["indexSym"])
    default_name = cfg.get("indexName", DEFAULT_STOCK_INDEX["indexName"])
    out = []
    for s in cfg.get("stocks", []):
        symbol = (s.get("symbol") or "").strip()
        if not symbol:
            print(f"Skipping stock entry without symbol: {s}", file=sys.stderr)
            continue
        out.append(dict(
            key=s.get("key") or auto_key(symbol),
            symbol=symbol,
            short=s.get("short"),
            name=s.get("name"),
            code=s.get("code") or re.sub(r"\.[A-Za-z]+$", "", symbol),
            flag=s.get("flag") or ("jp" if symbol.upper().endswith(".T") else "world"),
            indexSym=s.get("indexSym", default_sym),
            indexName=s.get("indexName", default_name),
        ))
    return out


def to_js_series(lst):
    return "[" + ",".join(f'["{d}",{v}]' for d, v in lst) + "]"


def js_str(s):
    return json.dumps(s, ensure_ascii=False)


def block(key, fields, nav):
    """指数の系列は top-level の indexes に置いて共有する（同じ指数を何銘柄で使っても 1 本）"""
    lines = [f'  {js_str(key)}: {{']
    lines += [f"    {js_str(k)}: {js_str(v)}," for k, v in fields.items()]
    lines.append(f'    "nav": {to_js_series(nav)}')
    lines.append("  }")
    return "\n".join(lines)


now = datetime.now(JST).strftime("%Y-%m-%d %H:%M")
blocks = []
order = []

for f in FUNDS:
    print(f"Fetching {f['short']} ...", end=" ", flush=True)
    try:
        nav = get_nav(f["isin"], f["code"])
        idx = get_index_cached(f["indexSym"])
    except Exception as e:
        print(f"FAILED: {e}", file=sys.stderr)
        sys.exit(1)
    print(f"NAV {len(nav)} days / index {len(idx)} days")
    order.append(f["key"])
    blocks.append(block(f["key"], {
        "type": "fund",
        "name": f["name"],
        "short": f["short"],
        "code": f["code"],
        "flag": f["flag"],
        "widgetLabel": f["widgetLabel"],
        "indexSym": f["indexSym"],
        "indexName": f["indexName"],
    }, nav))

for s in load_stocks():
    print(f"Fetching {s['symbol']} ...", end=" ", flush=True)
    try:
        res = fetch_chart(s["symbol"])
        px = series_from(res, adjusted=True)
        idx = get_index_cached(s["indexSym"])
    except Exception as e:
        print(f"FAILED: {e}", file=sys.stderr)
        sys.exit(1)
    meta = res.get("meta", {})
    name = s["name"] or meta.get("longName") or meta.get("shortName") or s["symbol"]
    short = s["short"] or (name[:6] if len(name) > 7 else name)
    print(f"{short}: price {len(px)} days / index {len(idx)} days")
    if s["key"] in order:
        print(f"Duplicate key {s['key']} in stocks.json", file=sys.stderr)
        sys.exit(1)
    order.append(s["key"])
    blocks.append(block(s["key"], {
        "type": "stock",
        "name": name,
        "short": short,
        "code": s["code"],
        "symbol": s["symbol"],
        "flag": s["flag"],
        "widgetLabel": short,
        "indexSym": s["indexSym"],
        "indexName": s["indexName"],
    }, px))

print("Fetching USD/JPY ...", end=" ", flush=True)
try:
    fx = get_index(FX["indexSym"])
except Exception as e:
    print(f"FAILED: {e}", file=sys.stderr)
    sys.exit(1)
print(f"{len(fx)} days")

fx_block = (
    '  "fx": {\n'
    f'    "usdjpy": {{\n'
    f'      "name": {js_str(FX["name"])},\n'
    f'      "index": {to_js_series(fx)}\n'
    f'    }}\n'
    '  }'
)

index_block = (
    '  "indexes": {\n'
    + ",\n".join(
        f"    {js_str(sym)}: {to_js_series(series)}"
        for sym, series in _index_cache.items()
    )
    + "\n  }"
)

js = (
    "// Auto-generated -- do not edit (overwritten by update workflow)\n"
    "window.DATA = {\n"
    f'  "generatedAt": "{now}",\n'
    f'  "order": {json.dumps(order)},\n'
    '  "funds": {\n'
    + ",\n".join(blocks) + "\n"
    "  },\n"
    + index_block + ",\n"
    + fx_block + "\n"
    "};\n"
)


def parse_data_js(text):
    """`window.DATA = {...};` の JSON 部分を dict にする"""
    start = text.find("{")
    end = text.rfind("}")
    return json.loads(text[start:end + 1])


def series_counts(data):
    """{名前: (件数, 厳格に減少を禁止するか)}"""
    out = {}
    for k, v in data.get("funds", {}).items():
        # 基準価額は追記のみ＝1件でも減れば異常。株価は Yahoo の 10 年ローリングで古い日が落ちる
        out[f"{k}.nav"] = (len(v.get("nav", [])), v.get("type", "fund") == "fund")
    for sym, series in data.get("indexes", {}).items():
        out[f"index:{sym}"] = (len(series), False)
    return out


def validate_no_shrink(new_js):
    """取得失敗で data.js が痩せるのを防ぐ。ローリング系列は 15 件の余裕を持たせる。"""
    if os.environ.get("FORCE_WRITE") == "1" or not os.path.exists("data.js"):
        return
    try:
        with open("data.js", encoding="utf-8") as fh:
            old = series_counts(parse_data_js(fh.read()))
        new = series_counts(parse_data_js(new_js))
    except (OSError, UnicodeDecodeError, ValueError):
        return
    failures = []
    for name, (old_count, strict) in old.items():
        if name not in new:
            continue  # stocks.json から削除された銘柄・指数
        new_count = new[name][0]
        if new_count < (old_count if strict else old_count - 15):
            failures.append(f"{name}: {old_count} -> {new_count}")
    if failures:
        print("Refusing to shrink data.js: " + "; ".join(failures), file=sys.stderr)
        sys.exit(1)


validate_no_shrink(js)
with open("data.js", "w", encoding="utf-8") as fh:
    fh.write(js)

print(f"Done: data.js ({now})")
