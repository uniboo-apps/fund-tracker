#!/usr/bin/env python3
"""Fetch fund NAV and index data, generate data.js  (CI equivalent of update.ps1)"""

import re
import sys
import requests
from datetime import datetime, timezone, timedelta

UA  = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
JST = timezone(timedelta(hours=9))

FUNDS = [
    dict(
        key="orukan",
        name="eMAXIS Slim 全世界株式（オール・カントリー）",
        short="オルカン",
        isin="JP90C000H1T1",
        code="0331418A",
        indexName="MSCI ACWI (ACWI ETF)",
        indexSym="ACWI",
    ),
    dict(
        key="sp500",
        name="eMAXIS Slim 米国株式（S&P500）",
        short="S&P500",
        isin="JP90C000GKC6",
        code="03311187",
        indexName="S&P 500 指数",
        indexSym="%5EGSPC",
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


def get_index(sym):
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}?range=10y&interval=1d"
    r = requests.get(url, headers={"User-Agent": UA}, timeout=60)
    r.raise_for_status()
    j = r.json()
    res = j["chart"]["result"][0]
    out = []
    for ts, cl in zip(res["timestamp"], res["indicators"]["quote"][0]["close"]):
        if cl is not None:
            d = datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%d")
            out.append([d, round(float(cl), 2)])
    return out


def to_js_series(lst):
    return "[" + ",".join(f'["{d}",{v}]' for d, v in lst) + "]"


now = datetime.now(JST).strftime("%Y-%m-%d %H:%M")
blocks = []

for f in FUNDS:
    print(f"Fetching {f['short']} ...", end=" ", flush=True)
    try:
        nav = get_nav(f["isin"], f["code"])
        idx = get_index(f["indexSym"])
    except Exception as e:
        print(f"FAILED: {e}", file=sys.stderr)
        sys.exit(1)
    print(f"NAV {len(nav)} days / index {len(idx)} days")
    blocks.append(
        f'  "{f["key"]}": {{\n'
        f'    "name": "{f["name"]}",\n'
        f'    "short": "{f["short"]}",\n'
        f'    "code": "{f["code"]}",\n'
        f'    "indexName": "{f["indexName"]}",\n'
        f'    "nav": {to_js_series(nav)},\n'
        f'    "index": {to_js_series(idx)}\n'
        f'  }}'
    )

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
    f'      "name": "{FX["name"]}",\n'
    f'      "index": {to_js_series(fx)}\n'
    f'    }}\n'
    '  }'
)

js = (
    "// Auto-generated -- do not edit (overwritten by update workflow)\n"
    "window.DATA = {\n"
    f'  "generatedAt": "{now}",\n'
    '  "funds": {\n'
    + ",\n".join(blocks) + "\n"
    "  },\n"
    + fx_block + "\n"
    "};\n"
)

with open("data.js", "w", encoding="utf-8") as fh:
    fh.write(js)

print(f"Done: data.js ({now})")
