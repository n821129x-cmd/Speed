#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
測速照相資料前處理工具
----------------------
把警政署 / 各縣市警察局的原始 CSV·JSON 合併成 App 可直接讀的統一格式。

功能
  1. 自動判斷編碼（UTF-8 / UTF-8-BOM / Big5(CP950)）
  2. 自動比對欄位名稱（各機關欄位命名都不一樣）
  3. TWD97 二度分帶座標自動轉 WGS84 經緯度（純 Python，不需 pyproj）
  4. 方向文字（「南往北」「往東」）正規化成方位角
  5. 去重（40 公尺內且方向相近視為同一點）
  6. 缺座標者可選擇性呼叫 Nominatim 地理編碼，並輸出未解析清單供人工補

用法
  mkdir raw && 把下載的 csv/json 全部丟進去
  python build_cameras.py                       # 只合併
  python build_cameras.py --geocode             # 併同地理編碼（慢，1 req/sec）
  python build_cameras.py --in raw --out cameras.csv

輸出
  cameras.csv        給 App 用
  unresolved.csv     缺座標、需人工處理的清單
  geocode_cache.json 地理編碼快取（可重複使用，勿刪）
"""

import argparse
import csv
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request

# ---------------------------------------------------------------- 基本設定

TW_BBOX = (21.0, 26.5, 118.0, 123.0)          # lat_min, lat_max, lon_min, lon_max
DEDUP_METERS = 40                              # 幾公尺內視為同一支桿
DEDUP_ANGLE = 45                               # 方向差幾度內視為同向
OUT_FIELDS = ["設備編號", "設置地點", "座標緯度", "座標經度", "速限", "拍攝方向", "來源"]

# 欄位關鍵字對應表：程式欄位 -> 可能出現的原始欄名片段
FIELD_KEYS = {
    "lat":   ["座標緯度", "緯度", "lat", "y座標", "twd97y", "wgs84y"],
    "lon":   ["座標經度", "經度", "lon", "lng", "x座標", "twd97x", "wgs84x"],
    "name":  ["設置地點", "設置位置", "測照地點", "地點", "位置", "路段", "location", "address", "name"],
    "limit": ["速限", "限速", "limit", "speed"],
    "dir":   ["拍攝方向", "取締方向", "測照方向", "方向", "direction", "bearing"],
    "id":    ["設備編號", "編號", "序號", "id"],
    "city":  ["縣市", "city"],
    "dist":  ["行政區", "區域", "鄉鎮", "district"],
    "item":  ["取締項目", "型式", "item", "type"],
}

DIR_MAP = [("東北", 45), ("東南", 135), ("西南", 225), ("西北", 315),
           ("北", 0), ("東", 90), ("南", 180), ("西", 270)]


# ---------------------------------------------------------------- 座標

def haversine(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def tm2_to_wgs84(E, N, lon0=121.0):
    """TWD97 二度分帶 (EPSG:3826, 澎金馬用 lon0=119) 反算成經緯度"""
    a, f = 6378137.0, 1 / 298.257222101
    k0, FE, FN = 0.9999, 250000.0, 0.0
    e2 = f * (2 - f)
    e1 = (1 - math.sqrt(1 - e2)) / (1 + math.sqrt(1 - e2))
    M = (N - FN) / k0
    mu = M / (a * (1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256))
    phi1 = (mu
            + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu)
            + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu)
            + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    ep2 = e2 / (1 - e2)
    C1 = ep2 * math.cos(phi1) ** 2
    T1 = math.tan(phi1) ** 2
    N1 = a / math.sqrt(1 - e2 * math.sin(phi1) ** 2)
    R1 = a * (1 - e2) / ((1 - e2 * math.sin(phi1) ** 2) ** 1.5)
    D = (E - FE) / (N1 * k0)
    lat = phi1 - (N1 * math.tan(phi1) / R1) * (
        D ** 2 / 2
        - (5 + 3 * T1 + 10 * C1 - 4 * C1 ** 2 - 9 * ep2) * D ** 4 / 24
        + (61 + 90 * T1 + 298 * C1 + 45 * T1 ** 2 - 252 * ep2 - 3 * C1 ** 2) * D ** 6 / 720)
    lon = math.radians(lon0) + (
        D - (1 + 2 * T1 + C1) * D ** 3 / 6
        + (5 - 2 * C1 + 28 * T1 - 3 * C1 ** 2 + 8 * ep2 + 24 * T1 ** 2) * D ** 5 / 120) / math.cos(phi1)
    return math.degrees(lat), math.degrees(lon)


def normalize_xy(x, y):
    """判斷是經緯度還是 TWD97 投影座標，統一回傳 (lat, lon) 或 None"""
    if x is None or y is None:
        return None
    # 經緯度
    if 118 <= x <= 123 and 21 <= y <= 27:
        return (y, x)          # x=lon, y=lat
    if 118 <= y <= 123 and 21 <= x <= 27:
        return (x, y)          # 欄位對調的髒資料
    # TWD97 TM2：E 約 150000~370000，N 約 2400000~2800000
    if 100000 <= x <= 400000 and 2000000 <= y <= 2900000:
        return tm2_to_wgs84(x, y, 121.0)
    if 100000 <= y <= 400000 and 2000000 <= x <= 2900000:
        return tm2_to_wgs84(y, x, 121.0)
    return None


def in_taiwan(lat, lon):
    return TW_BBOX[0] <= lat <= TW_BBOX[1] and TW_BBOX[2] <= lon <= TW_BBOX[3]


# ---------------------------------------------------------------- 解析

def read_text(path):
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "utf-8", "cp950", "big5hkscs"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def map_columns(header):
    """回傳 {程式欄位: 索引}"""
    idx = {}
    low = [h.strip().lower() for h in header]
    for field, keys in FIELD_KEYS.items():
        for k in keys:
            k = k.lower()
            hit = next((i for i, h in enumerate(low) if k in h), None)
            if hit is not None:
                idx[field] = hit
                break
    return idx


def parse_dir(s):
    if not s:
        return ""
    s = str(s).strip()
    m = re.fullmatch(r"\d+(\.\d+)?", s)
    if m:
        return str(int(float(s)) % 360)
    t = s.split("往")[-1] if "往" in s else s
    for k, v in DIR_MAP:
        if k in t:
            return str(v)
    return ""


def parse_limit(s):
    if not s:
        return ""
    m = re.search(r"\d+", str(s))
    return m.group() if m else ""


def num(s):
    try:
        return float(str(s).replace(",", "").strip())
    except (TypeError, ValueError):
        return None


def rows_from_csv(path):
    text = read_text(path)
    lines = [l for l in text.splitlines() if l.strip()]
    if len(lines) < 2:
        return []
    rdr = list(csv.reader(lines))
    header, body = rdr[0], rdr[1:]
    idx = map_columns(header)
    out = []
    for r in body:
        def g(f):
            i = idx.get(f)
            return r[i].strip() if i is not None and i < len(r) else ""
        out.append({
            "id": g("id"), "name": g("name") or g("dist") or g("city"),
            "x": num(g("lon")), "y": num(g("lat")),
            "limit": parse_limit(g("limit")), "dir": parse_dir(g("dir")),
            "extra": g("item"),
            "raw_addr": " ".join(filter(None, [g("city"), g("dist"), g("name")])),
        })
    return out


def rows_from_json(path):
    data = json.loads(read_text(path))
    if isinstance(data, dict):
        for k in ("data", "result", "records", "features", "items"):
            if isinstance(data.get(k), list):
                data = data[k]
                break
            if isinstance(data.get(k), dict) and isinstance(data[k].get("records"), list):
                data = data[k]["records"]
                break
    if not isinstance(data, list) or not data:
        return []
    if not isinstance(data[0], dict):
        return []
    header = list(data[0].keys())
    idx = map_columns(header)
    rev = {f: header[i] for f, i in idx.items()}
    out = []
    for d in data:
        def g(f):
            k = rev.get(f)
            return str(d.get(k, "")).strip() if k else ""
        out.append({
            "id": g("id"), "name": g("name") or g("dist") or g("city"),
            "x": num(g("lon")), "y": num(g("lat")),
            "limit": parse_limit(g("limit")), "dir": parse_dir(g("dir")),
            "extra": g("item"),
            "raw_addr": " ".join(filter(None, [g("city"), g("dist"), g("name")])),
        })
    return out


# ---------------------------------------------------------------- 地理編碼

class Geocoder:
    """Nominatim（免費、需遵守 1 req/sec）。有 TGOS 金鑰的話優先用 TGOS，準確度高很多。"""

    UA = "speedcam-dataprep/1.0 (contact: your-email@example.com)"

    def __init__(self, cache_path, tgos_key=None):
        self.cache_path = cache_path
        self.tgos_key = tgos_key
        self.cache = {}
        if os.path.exists(cache_path):
            self.cache = json.load(open(cache_path, encoding="utf-8"))

    def save(self):
        json.dump(self.cache, open(self.cache_path, "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)

    def lookup(self, addr):
        if not addr:
            return None
        if addr in self.cache:
            c = self.cache[addr]
            return tuple(c) if c else None
        res = self._nominatim(addr)
        self.cache[addr] = list(res) if res else None
        time.sleep(1.1)
        return res

    def _nominatim(self, addr):
        q = urllib.parse.urlencode({
            "q": addr if "台" in addr or "臺" in addr else "台灣 " + addr,
            "format": "json", "limit": 1, "countrycodes": "tw",
        })
        url = "https://nominatim.openstreetmap.org/search?" + q
        try:
            req = urllib.request.Request(url, headers={"User-Agent": self.UA})
            with urllib.request.urlopen(req, timeout=20) as r:
                j = json.load(r)
            if j:
                lat, lon = float(j[0]["lat"]), float(j[0]["lon"])
                return (lat, lon) if in_taiwan(lat, lon) else None
        except Exception as e:
            print(f"    地理編碼失敗 {addr}: {e}", file=sys.stderr)
        return None


# ---------------------------------------------------------------- 去重

def dedupe(points):
    buckets = {}
    kept = []
    for p in points:
        gx, gy = round(p["lat"], 3), round(p["lon"], 3)
        dup = False
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for q in buckets.get((round(gx + dy * 0.001, 3), round(gy + dx * 0.001, 3)), []):
                    if haversine(p["lat"], p["lon"], q["lat"], q["lon"]) > DEDUP_METERS:
                        continue
                    pd, qd = p["dir"], q["dir"]
                    if pd and qd:
                        diff = abs(int(pd) - int(qd)) % 360
                        diff = min(diff, 360 - diff)
                        if diff > DEDUP_ANGLE:
                            continue
                    # 合併：補上對方缺的欄位
                    for f in ("limit", "dir", "name"):
                        if not q[f] and p[f]:
                            q[f] = p[f]
                    dup = True
                    break
                if dup:
                    break
            if dup:
                break
        if not dup:
            buckets.setdefault((gx, gy), []).append(p)
            kept.append(p)
    return kept


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="indir", default="raw", help="原始檔資料夾")
    ap.add_argument("--out", default="cameras.csv")
    ap.add_argument("--geocode", action="store_true", help="對缺座標者做地理編碼（慢）")
    ap.add_argument("--tgos-key", default=None, help="TGOS API 金鑰（預留）")
    args = ap.parse_args()

    if not os.path.isdir(args.indir):
        print(f"找不到資料夾 {args.indir}，請先建立並放入下載的 csv/json", file=sys.stderr)
        sys.exit(1)

    files = sorted(f for f in os.listdir(args.indir)
                   if f.lower().endswith((".csv", ".json")))
    if not files:
        print(f"{args.indir} 內沒有 csv/json", file=sys.stderr)
        sys.exit(1)

    all_rows = []
    for fn in files:
        path = os.path.join(args.indir, fn)
        rows = rows_from_csv(path) if fn.lower().endswith(".csv") else rows_from_json(path)
        for r in rows:
            r["src"] = fn
        print(f"  讀取 {fn}: {len(rows)} 列")
        all_rows.extend(rows)

    gc = Geocoder("geocode_cache.json", args.tgos_key) if args.geocode else None

    good, bad = [], []
    for r in all_rows:
        ll = normalize_xy(r["x"], r["y"])
        if ll is None and gc:
            ll = gc.lookup(r["raw_addr"])
        if ll is None or not in_taiwan(*ll):
            bad.append(r)
            continue
        good.append({
            "id": r["id"], "name": r["name"] or "測速照相",
            "lat": round(ll[0], 6), "lon": round(ll[1], 6),
            "limit": r["limit"], "dir": r["dir"], "src": r["src"],
        })
    if gc:
        gc.save()

    before = len(good)
    good = dedupe(good)

    with open(args.out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(OUT_FIELDS)
        for i, p in enumerate(good, 1):
            w.writerow([p["id"] or f"P{i:05d}", p["name"], p["lat"], p["lon"],
                        p["limit"], p["dir"], p["src"]])

    if bad:
        with open("unresolved.csv", "w", encoding="utf-8", newline="") as f:
            w = csv.writer(f)
            w.writerow(["來源檔", "編號", "地點描述", "速限", "方向", "原始X", "原始Y"])
            for r in bad:
                w.writerow([r["src"], r["id"], r["raw_addr"], r["limit"], r["dir"], r["x"], r["y"]])

    print(f"\n讀入 {len(all_rows)} 列")
    print(f"有效座標 {before} 點，去重後 {len(good)} 點 -> {args.out}")
    if bad:
        print(f"缺座標 {len(bad)} 點 -> unresolved.csv"
              + ("" if args.geocode else "（可加 --geocode 嘗試自動定位）"))


if __name__ == "__main__":
    main()
