"""
Steam Diskon Finder - aplikasi desktop (bisa dijadikan .exe portable).

Alur: atur harga/diskon/region lalu klik Cari (memindai Steam). Setelah ada hasil,
panel "Filter hasil" bisa menyaring daftar tanpa memindai ulang: kolom cari nama
dan ceklist genre. Kosongkan filter (atau klik Reset filter) untuk kembali ke hasil awal.

Like: klik ikon hati di sebelah kiri cover (atau klik kanan / tekan L) untuk menyimpan
game ke database (steam_diskon_likes.db, di folder yang sama dengan program/exe). Setelah
program dibuka ulang, centang "Hanya yang di-like" untuk memantau game yang disimpan.

Fitur lain: cover game, rating bintang, ulasan positif/negatif berwarna, tanggal
berakhir diskon, urutkan lewat klik judul kolom, export Excel (sesuai tampilan).

BELUM DIUJI terhadap Steam (tidak ada akses dari lingkungan saya).

Jalankan langsung : python steam_diskon_app.py
Jadikan .exe      : jalankan build.bat (hasil: dist\\SteamDiskonFinder.exe)
"""
import html as htmllib
import io
import json
import math
import os
import queue
import re
import sqlite3
import sys
import threading
import time
import tkinter as tk
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from tkinter import filedialog, messagebox, ttk

import requests
from openpyxl import Workbook
from openpyxl.drawing.image import Image as XLImage
from openpyxl.styles import Alignment, Font, PatternFill
from PIL import Image, ImageDraw, ImageTk

URL = "https://store.steampowered.com/search/results/"
API_URL = "https://api.steampowered.com/IStoreBrowseService/GetItems/v1"
HEADERS = {"User-Agent": "Mozilla/5.0"}
PAGE = 50
COVER_W, COVER_H, BAR_W = 120, 45, 8
LIKE_W = 34      # lebar area ikon hati di sebelah kiri cover (di dalam gambar kolom pertama)
LIKE_HIT = 70    # klik dalam jarak ini dari tepi kiri kolom pertama = klik tombol like

# Warna ulasan. Tukar nilainya di sini kalau mau (Steam asli: positif biru, negatif merah).
COLOR_POS = "#D32F2F"   # positif = merah
COLOR_NEG = "#2E7D32"   # negatif = hijau
COLOR_MIX = "#F9A825"   # campuran = kuning
RCOLOR = {"positive": COLOR_POS, "mixed": COLOR_MIX, "negative": COLOR_NEG}

REGIONS = {
    "Indonesia (Rp)": {"cc": "id", "sym": "Rp", "min": 10000, "max": 100000, "dec": 0},
    "Amerika Serikat (USD)": {"cc": "us", "sym": "$", "min": 1, "max": 10, "dec": 2},
    "Jepang (JPY)": {"cc": "jp", "sym": "\u00a5", "min": 100, "max": 1000, "dec": 0},
}
DEFAULT_REGION = "Indonesia (Rp)"
REGION_BY_CC = {v["cc"]: v for v in REGIONS.values()}
TIERS = [("1-24%", 1, 24), ("25-49%", 25, 49), ("50-74%", 50, 74), ("75-100%", 75, 100)]

# Genre / fitur untuk filter hasil: (nama tampil, ID tag komunitas Steam).
# Tambah atau ubah sesuka hati. Data tag tiap game diambil dari API toko Steam (maks. 20 tag teratas).
GENRES = [
    ("Action", 19), ("Adventure", 21), ("RPG", 122), ("FPS", 1663),
    ("Shooter", 1774), ("Strategy", 9), ("Simulation", 599), ("Horor", 1667),
    ("Visual Novel", 3799), ("Puzzle", 1664), ("Racing", 699), ("Sports", 701),
    ("Open World", 1695), ("Survival", 1662), ("Roguelike", 1716),
    ("Platformer", 1625), ("Fighting", 1743), ("Anime", 4085),
    ("Indie", 492), ("Casual", 597), ("Single-player", 4182),
    ("Multiplayer", 3859), ("Co-op", 1685), ("Online Co-op", 3843),
    ("PvP", 1775), ("MMO", 128), ("MMORPG", 1754),
]

# ---- Filter "AAA" ----
# Steam tidak punya penanda AAA resmi, jadi ini PERKIRAAN: game dari penerbit besar, atau game
# mahal (harga normal di atas batas) dengan ulasan sangat banyak dan tanpa tag Indie.
# Ubah angka/daftar di bawah sesuai selera.
INDIE_TAG = 492
AAA_MIN_PRICE = {"us": 29.0, "id": 450000.0, "jp": 3800.0}   # harga normal minimum per region
AAA_MIN_REVIEWS = 2000
AAA_PUBLISHERS = [
    "electronic arts", "ea", "ubisoft", "activision", "blizzard", "bethesda", "zenimax", "capcom",
    "square enix", "sony", "playstation", "bandai namco", "sega", "take-two", "2k", "rockstar",
    "warner bros", "cd projekt", "konami", "microsoft", "xbox game studios", "valve",
    "koei tecmo", "larian",
]

ROW_RE = re.compile(r'<a [^>]*data-ds-appid="([\d,]+)"[^>]*>(.*?)</a>', re.S)
TITLE_RE = re.compile(r'<span class="title">(.*?)</span>', re.S)
DISC_RE = re.compile(r'data-discount="(\d+)"')
FINAL_RE = re.compile(r'discount_final_price[^>]*>(.*?)<', re.S)
ORIG_RE = re.compile(r'discount_original_price[^>]*>(.*?)<', re.S)
IMG_RE = re.compile(r'<img[^>]+src="([^"]+)"')
SPAN_RE = re.compile(r'<span[^>]*search_review_summary[^>]*>', re.S)
RCLS_RE = re.compile(r'search_review_summary\s+(positive|mixed|negative)')
TIP_RE = re.compile(r'data-tooltip-html="([^"]*)"')
PCT_RE = re.compile(r'(\d+)%\s+of\s+the\s+([\d,.]+)\s+user reviews')


class Cancelled(Exception):
    pass


def parse_price(text, cc):
    text = (text or "").replace("\xa0", " ")
    if cc == "us":
        m = re.search(r"\d[\d,]*(?:\.\d+)?", text)
        return float(m.group().replace(",", "")) if m else None
    d = re.sub(r"\D", "", text)
    return float(d) if d else None


def parse_items(data, cc):
    """Ubah results_html Steam jadi daftar game."""
    out = []
    for appid, body in ROW_RE.findall(data.get("results_html", "")):
        t, d = TITLE_RE.search(body), DISC_RE.search(body)
        f, o = FINAL_RE.search(body), ORIG_RE.search(body)
        if not (t and d and f and o):
            continue
        final, initial = parse_price(f.group(1), cc), parse_price(o.group(1), cc)
        if final is None or initial is None:
            continue
        appid = appid.split(",")[0]
        img = IMG_RE.search(body)
        pct = nrev = rlabel = rcls = None
        sp = SPAN_RE.search(body)
        if sp:
            tag = sp.group(0)
            c = RCLS_RE.search(tag)
            rcls = c.group(1) if c else None
            tip = TIP_RE.search(tag)
            if tip:
                tt = htmllib.unescape(tip.group(1))
                rlabel = re.split(r"<br\s*/?>", tt)[0].strip() or None
                m = PCT_RE.search(tt)
                if m:
                    pct = int(m.group(1))
                    nrev = int(re.sub(r"\D", "", m.group(2)) or 0) or None
        out.append({
            "name": htmllib.unescape(t.group(1)).strip(),
            "appid": appid,
            "disc": int(d.group(1)),
            "initial": initial,
            "final": final,
            "img": img.group(1) if img else
            f"https://cdn.cloudflare.steamstatic.com/steam/apps/{appid}/capsule_sm_120.jpg",
            "pct": pct, "nrev": nrev, "rlabel": rlabel, "rcls": rcls,
            "end": None, "tags": None, "cc": cc, "pubs": None,
        })
    return out


def fmt_price(v, cfg):
    if cfg["dec"]:
        return f'{cfg["sym"]}{v:,.{cfg["dec"]}f}'
    return f'{cfg["sym"]} {v:,.0f}'


def review_class(g):
    if g.get("rcls") in RCOLOR:
        return g["rcls"]
    pct = g.get("pct")
    if pct is None:
        return None
    return "positive" if pct >= 70 else "mixed" if pct >= 40 else "negative"


def stars_text(pct):
    if pct is None:
        return "-"
    s = pct / 20
    n = round(s)
    return "\u2605" * n + "\u2606" * (5 - n) + f" {s:.1f}"


def review_text(g):
    if g.get("pct") is None:
        return "-"
    label = g.get("rlabel") or review_class(g).capitalize()
    n = g.get("nrev")
    return f"{label} ({g['pct']}%, {n:,} ulasan)" if n else f"{label} ({g['pct']}%)"


def end_datetime(g):
    try:
        return datetime.fromtimestamp(g["end"]) if g.get("end") else None
    except (OSError, OverflowError, ValueError):
        return None


def end_text(g):
    dt = end_datetime(g)
    return dt.strftime("%d %b %Y %H:%M") if dt else "-"


def cfg_of(g):
    """Pengaturan mata uang sesuai region tempat data game itu diambil."""
    return REGION_BY_CC.get(g.get("cc"), REGIONS[DEFAULT_REGION])


def rating_score(g):
    """
    Skor peringkat = batas bawah Wilson dari persen ulasan positif dan jumlah ulasan.
    Contoh: 100% dari 2 ulasan kalah dari 92% dari 5.000 ulasan. Tanpa data jumlah ulasan = -1.
    """
    pct, n = g.get("pct"), g.get("nrev")
    if pct is None or not n:
        return -1.0
    p, z = pct / 100.0, 1.96
    denom = 1 + z * z / n
    centre = p + z * z / (2 * n)
    margin = z * math.sqrt((p * (1 - p) + z * z / (4 * n)) / n)
    return (centre - margin) / denom


def is_big_publisher(name):
    n = (name or "").lower()
    return any(re.search(r"\b" + re.escape(k) + r"\b", n) for k in AAA_PUBLISHERS)


def is_aaa(g):
    """Perkiraan game AAA: penerbit besar, atau harga normal tinggi + ulasan sangat banyak + bukan Indie."""
    if any(is_big_publisher(p) for p in (g.get("pubs") or [])):
        return True
    if g["initial"] < AAA_MIN_PRICE.get(g.get("cc"), AAA_MIN_PRICE["us"]):
        return False
    if (g.get("nrev") or 0) < AAA_MIN_REVIEWS:
        return False
    tags = g.get("tags")
    if tags and INDIE_TAG in tags:
        return False
    return True


def game_matches(g, tokens, sel_tags, any_mode, like_only=False, liked=frozenset(), aaa_only=False):
    """
    Filter tampilan:
    - game 'snapshot' (hanya ada di database like) hanya tampil kalau mode like aktif
    - mode like: hanya game yang di-like
    - semua kata di kolom cari harus ada di nama; genre cocok salah satu / semuanya
    """
    if g.get("src") == "like" and not like_only:
        return False
    if like_only and g["appid"] not in liked:
        return False
    if aaa_only and not is_aaa(g):
        return False
    name = g["name"].lower()
    for t in tokens:
        if t not in name:
            return False
    if not sel_tags:
        return True
    tags = g.get("tags")
    if not tags:
        return False
    return bool(tags & sel_tags) if any_mode else sel_tags <= tags


def _heart(d, x, y, size, fill):
    d.ellipse([x, y, x + size * 0.52, y + size * 0.52], fill=fill)
    d.ellipse([x + size * 0.48, y, x + size, y + size * 0.52], fill=fill)
    d.polygon([(x + size * 0.04, y + size * 0.36), (x + size * 0.96, y + size * 0.36),
               (x + size * 0.5, y + size)], fill=fill)


def draw_heart(d, x, y, size, liked, bg="#2b2b2b"):
    """Ikon like: hati merah penuh = di-like, hati abu-abu berongga = belum."""
    if liked:
        _heart(d, x, y, size, "#E53935")
    else:
        k = size / 24.0
        _heart(d, x, y, size, "#9E9E9E")
        _heart(d, x + 3 * k, y + 3.5 * k, size - 6 * k, bg)


def make_thumb(img, color, liked=False):
    """[batang warna ulasan][ikon like][cover] dalam satu gambar untuk kolom pertama."""
    canvas = Image.new("RGB", (BAR_W + LIKE_W + COVER_W, COVER_H), "#2b2b2b")
    if img is not None:
        canvas.paste(img, (BAR_W + LIKE_W, 0))
    # ikon digambar 4x lebih besar lalu diperkecil supaya tepinya halus
    k = 4
    layer = Image.new("RGB", (LIKE_W * k, COVER_H * k), "#2b2b2b")
    draw_heart(ImageDraw.Draw(layer), 5 * k, ((COVER_H - 24) // 2) * k, 24 * k, liked)
    canvas.paste(layer.resize((LIKE_W, COVER_H), Image.LANCZOS), (BAR_W, 0))
    if color:
        canvas.paste(Image.new("RGB", (BAR_W, COVER_H), color), (0, 0))
    return canvas


# ---------- database like ----------
def app_dir():
    """Folder program (atau folder .exe kalau sudah di-build) supaya database ikut portable."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def game_to_json(g):
    d = {k: g.get(k) for k in ("name", "appid", "disc", "initial", "final", "img",
                               "pct", "nrev", "rlabel", "rcls", "end", "cc", "pubs")}
    d["tags"] = sorted(g["tags"]) if g.get("tags") else None
    return json.dumps(d, ensure_ascii=False)


def game_from_json(text):
    d = json.loads(text)
    d["tags"] = set(d["tags"]) if d.get("tags") else None
    d.setdefault("cc", "id")
    for k in ("pct", "nrev", "rlabel", "rcls", "end", "pubs"):
        d.setdefault(k, None)
    return d


class LikeDB:
    """Penyimpanan game yang di-like (SQLite): data game + cover, supaya tetap ada setelah program ditutup."""

    def __init__(self, path):
        self.path = path
        self.conn = sqlite3.connect(path)
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS likes (appid TEXT PRIMARY KEY, data TEXT NOT NULL, "
            "cover BLOB, liked_at TEXT NOT NULL, updated_at TEXT NOT NULL)")
        self.conn.commit()

    @staticmethod
    def _now():
        return datetime.now().isoformat(timespec="seconds")

    def ids(self):
        return {r[0] for r in self.conn.execute("SELECT appid FROM likes")}

    def load_all(self):
        """Daftar (game, cover_bytes, waktu_like), terbaru di-like di atas."""
        out = []
        for data, cover, liked_at in self.conn.execute(
                "SELECT data, cover, liked_at FROM likes ORDER BY liked_at DESC"):
            try:
                out.append((game_from_json(data), cover, liked_at))
            except (ValueError, KeyError):
                continue
        return out

    def add(self, g, cover=None):
        now = self._now()
        self.conn.execute("INSERT OR REPLACE INTO likes VALUES (?, ?, ?, ?, ?)",
                          (g["appid"], game_to_json(g), cover, now, now))
        self.conn.commit()

    def update(self, g):
        self.conn.execute("UPDATE likes SET data = ?, updated_at = ? WHERE appid = ?",
                          (game_to_json(g), self._now(), g["appid"]))
        self.conn.commit()

    def set_cover(self, appid, cover):
        self.conn.execute("UPDATE likes SET cover = ? WHERE appid = ?", (cover, appid))
        self.conn.commit()

    def remove(self, appid):
        self.conn.execute("DELETE FROM likes WHERE appid = ?", (appid,))
        self.conn.commit()


def open_like_db():
    """Buka database di folder program; kalau tidak bisa ditulis, pindah ke folder home; terakhir di memori."""
    name = "steam_diskon_likes.db"
    for path in (os.path.join(app_dir(), name), os.path.join(os.path.expanduser("~"), name)):
        try:
            return LikeDB(path)
        except (sqlite3.Error, OSError):
            continue
    return LikeDB(":memory:")


class Scraper:
    def __init__(self, cc, delay, cancel, status):
        self.cc, self.delay, self.cancel, self.status = cc, delay, cancel, status
        self.details_failed = False
        self.got_dates = 0
        self.got_tags = 0
        self.got_pubs = 0
        self.no_basic = False

    def pause(self, secs):
        end = time.time() + secs
        while time.time() < end:
            if self.cancel.is_set():
                raise Cancelled()
            time.sleep(0.2)

    def _get(self, url, params, delay, tries=8):
        for attempt in range(1, tries + 1):
            if self.cancel.is_set():
                raise Cancelled()
            try:
                r = requests.get(url, params=params, headers=HEADERS, timeout=30)
            except requests.RequestException as e:
                self.status(f"Gagal koneksi ({e.__class__.__name__}), coba lagi...")
                self.pause(5 * attempt)
                continue
            if r.status_code == 429:
                try:
                    wait = min(int(r.headers.get("Retry-After", "")), 300)
                except ValueError:
                    wait = 30 * attempt
                self.status(f"Kena limit Steam (429), menunggu {wait} detik...")
                self.pause(wait)
                continue
            r.raise_for_status()
            self.pause(delay)
            return r.json()
        raise RuntimeError("Steam terus menolak/gagal. Coba lagi beberapa menit lagi.")

    def fetch_page(self, start):
        params = {
            "query": "", "start": start, "count": PAGE, "specials": 1,
            "sort_by": "Price_ASC", "category1": 998, "cc": self.cc,
            "l": "english", "infinite": 1,
        }
        return self._get(URL, params, self.delay)

    def _details_request(self, games, with_basic):
        data_request = {"include_reviews": True, "include_tag_count": 20}
        if with_basic:
            data_request["include_basic_info"] = True
        payload = {
            "ids": [{"appid": int(g["appid"])} for g in games],
            "context": {"language": "english", "country_code": self.cc.upper()},
            "data_request": data_request,
        }
        return self._get(API_URL, {"input_json": json.dumps(payload)}, 0.5, tries=2)

    def fetch_details(self, games):
        """Ambil tanggal berakhir diskon, genre (tag), penerbit, dan ulasan bila kosong dari API toko Steam."""
        try:
            try:
                data = self._details_request(games, not self.no_basic)
            except Cancelled:
                raise
            except Exception:
                if self.no_basic:
                    raise
                self.no_basic = True    # mungkin include_basic_info ditolak: coba lagi tanpa penerbit
                data = self._details_request(games, False)
            items = (data.get("response") or {}).get("store_items") or []
        except Cancelled:
            raise
        except Exception:
            self.details_failed = True
            return
        by_id = {str(it.get("appid") or it.get("id")): it for it in items}
        for g in games:
            it = by_id.get(g["appid"])
            if not it:
                continue
            discs = (it.get("best_purchase_option") or {}).get("active_discounts") or []
            end = discs[0].get("discount_end_date") if discs else None
            if end:
                g["end"] = int(end)
                self.got_dates += 1
            tg = it.get("tagids")
            if tg is None:
                tg = [t.get("tagid") for t in (it.get("tags") or []) if isinstance(t, dict)]
            tg = {int(x) for x in tg if x is not None}
            if tg:
                g["tags"] = tg
                self.got_tags += 1
            pubs = [p.get("name") for p in ((it.get("basic_info") or {}).get("publishers") or [])
                    if isinstance(p, dict) and p.get("name")]
            if pubs:
                g["pubs"] = pubs
                self.got_pubs += 1
            if g.get("pct") is None:
                rev = (it.get("reviews") or {}).get("summary_filtered") or {}
                if rev.get("percent_positive") is not None:
                    g["pct"] = int(rev["percent_positive"])
                    g["nrev"] = int(rev.get("review_count") or 0) or None
                    g["rlabel"] = rev.get("review_score_label") or None

    def parse(self, data):
        return parse_items(data, self.cc)

    def find_start(self, lo):
        """Binary search halaman pertama yang harganya sudah >= lo. None = tidak ada hasil sama sekali."""
        first = self.fetch_page(0)
        total = int(first.get("total_count") or 0)
        if total == 0:
            return None
        if not self.parse(first):
            raise RuntimeError("Tidak ada harga yang terbaca. Format Steam mungkin berubah.")
        if lo <= 0:
            return 0, total
        a, b = 0, max(total // PAGE, 1)
        while a < b:
            mid = (a + b) // 2
            items = self.parse(self.fetch_page(mid * PAGE))
            if not items:
                b = mid
            elif items[-1]["final"] >= lo:
                b = mid
            else:
                a = mid + 1
        return max(a - 1, 0) * PAGE, total

    def search(self, lo, hi, tiers, want, on_game):
        self.status("Mencari posisi harga...")
        pos = self.find_start(lo)
        if pos is None:
            return []
        start, total = pos
        games, seen = [], set()
        while len(games) < want and (total == 0 or start < total):
            self.status(f"Memindai... {len(games)}/{want} ditemukan")
            items = self.parse(self.fetch_page(start))
            if not items:
                break
            beyond = 0
            page = []
            for g in items:
                if g["final"] > hi:
                    beyond += 1
                    continue
                if g["final"] < lo or g["initial"] <= g["final"] or g["appid"] in seen:
                    continue
                if not any(a <= g["disc"] <= b for a, b in tiers):
                    continue
                seen.add(g["appid"])
                page.append(g)
                if len(games) + len(page) >= want:
                    break
            if page:
                self.fetch_details(page)
                for g in page:
                    games.append(g)
                    on_game(g)
            start += PAGE
            if beyond == len(items):
                break
        return games


def write_excel(path, rows, covers):
    wb = Workbook()
    ws = wb.active
    ws.title = "Diskon Steam"
    ws.append(["No", "Cover", "Game", "Rating", "Ulasan", "Diskon (%)",
               "Harga normal", "Harga diskon", "Berakhir", "Link Steam"])
    for c in ws[1]:
        c.font = Font(bold=True, color="FFFFFF")
        c.fill = PatternFill("solid", fgColor="1F3864")
        c.alignment = Alignment(horizontal="center", vertical="center")
    for i, g in enumerate(rows, 1):
        num_fmt = "#,##0.00" if cfg_of(g)["dec"] else "#,##0"
        link = f"https://store.steampowered.com/app/{g['appid']}/"
        end = end_datetime(g)
        ws.append([i, None, g["name"], stars_text(g.get("pct")), review_text(g),
                   g["disc"] / 100, g["initial"], g["final"], end or "-", link])
        r = ws.max_row
        ws.cell(r, 6).number_format = "0%"
        ws.cell(r, 7).number_format = num_fmt
        ws.cell(r, 8).number_format = num_fmt
        if end:
            ws.cell(r, 9).number_format = "dd mmm yyyy hh:mm"
        ws.cell(r, 10).hyperlink = link
        for col in range(1, 11):
            ws.cell(r, col).alignment = Alignment(
                horizontal="center" if col in (6, 7, 8) else None, vertical="center")
        color = RCOLOR.get(review_class(g))
        if color:
            ws.cell(r, 5).font = Font(bold=True, color=color.lstrip("#"))
        if g.get("pct") is not None:
            ws.cell(r, 4).font = Font(color="F5A623")
        data = covers.get(g["appid"])
        if data:
            w, h = Image.open(io.BytesIO(data)).size
            xl = XLImage(io.BytesIO(data))
            xl.width, xl.height = COVER_W, max(int(h * COVER_W / w), 1)
            ws.add_image(xl, f"B{r}")
            ws.row_dimensions[r].height = xl.height * 0.75 + 6
    for col, w in zip("ABCDEFGHIJ", [6, 20, 45, 14, 34, 11, 16, 16, 18, 45]):
        ws.column_dimensions[col].width = w
    ws.freeze_panes = "A2"
    ws.auto_filter.ref = f"A1:J{ws.max_row}"
    wb.save(path)


class App:
    def __init__(self, root):
        self.root = root
        root.title("Steam Diskon Finder")
        root.geometry("1280x900")
        self.q = queue.Queue()
        self.cancel = threading.Event()
        self.games, self.covers, self.photos, self.thumbs = {}, {}, {}, {}
        self.order = []            # urutan appid saat ini (awal = urutan hasil pindai)
        self.seq = 0               # penanda urutan kedatangan hasil pindai
        self.f_tokens, self.f_tags, self.f_any, self.f_like, self.f_aaa = [], set(), True, False, False
        self._fjob = None
        self.gen = 0
        self.sort_rev = {}
        self.cfg = REGIONS[DEFAULT_REGION]
        self.pool = ThreadPoolExecutor(max_workers=4)
        self.db = open_like_db()
        self.liked = self.db.ids()
        self.build_ui()
        self.load_snapshots()
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.poll()

    # ---------- UI ----------
    def build_ui(self):
        ttk.Style().configure("Treeview", rowheight=COVER_H + 8)
        top = ttk.Frame(self.root, padding=10)
        top.pack(fill="x")

        ttk.Label(top, text="Region:").grid(row=0, column=0, sticky="w")
        self.region_var = tk.StringVar(value=DEFAULT_REGION)
        cb = ttk.Combobox(top, textvariable=self.region_var, values=list(REGIONS),
                          state="readonly", width=22)
        cb.grid(row=0, column=1, padx=6)
        cb.bind("<<ComboboxSelected>>", self.on_region)

        ttk.Label(top, text="Harga min:").grid(row=0, column=2, padx=(14, 0))
        self.min_var = tk.StringVar(value=str(self.cfg["min"]))
        ttk.Entry(top, textvariable=self.min_var, width=10).grid(row=0, column=3, padx=6)
        ttk.Label(top, text="Harga max:").grid(row=0, column=4)
        self.max_var = tk.StringVar(value=str(self.cfg["max"]))
        ttk.Entry(top, textvariable=self.max_var, width=10).grid(row=0, column=5, padx=6)

        ttk.Label(top, text="Jumlah game:").grid(row=1, column=0, sticky="w", pady=(8, 0))
        self.count_var = tk.StringVar(value="100")
        ttk.Spinbox(top, from_=1, to=100000, increment=50, textvariable=self.count_var,
                    width=10).grid(row=1, column=1, sticky="w", padx=6, pady=(8, 0))
        ttk.Label(top, text="Jeda request (dtk):").grid(row=1, column=2, pady=(8, 0), padx=(14, 0))
        self.delay_var = tk.StringVar(value="2")
        ttk.Entry(top, textvariable=self.delay_var, width=10).grid(row=1, column=3, padx=6, pady=(8, 0))

        box = ttk.LabelFrame(top, text="Diskon (ceklist yang mau dimasukkan)", padding=6)
        box.grid(row=2, column=0, columnspan=6, sticky="w", pady=(10, 0))
        self.tier_vars = []
        for label, _, _ in TIERS:
            v = tk.BooleanVar(value=True)
            self.tier_vars.append(v)
            ttk.Checkbutton(box, text=label, variable=v).pack(side="left", padx=8)

        bar = ttk.Frame(self.root, padding=(10, 0))
        bar.pack(fill="x")
        self.btn_search = ttk.Button(bar, text="Cari", command=self.start_search)
        self.btn_search.pack(side="left")
        self.btn_stop = ttk.Button(bar, text="Stop", command=self.stop, state="disabled")
        self.btn_stop.pack(side="left", padx=6)
        self.cover_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(bar, text="Sertakan cover di Excel", variable=self.cover_var).pack(side="left", padx=14)
        ttk.Button(bar, text="Export ke Excel", command=self.export).pack(side="left")
        self.status_var = tk.StringVar(
            value="Siap. Atur filter lalu klik Cari. Klik ikon hati untuk like, klik dua kali game untuk buka Steam. "
                  f"Database like: {self.db.path}")
        ttk.Label(self.root, textvariable=self.status_var, padding=(10, 6)).pack(fill="x")

        # ---- Filter hasil (bagian terpisah; bekerja pada hasil yang sudah dipindai) ----
        ff = ttk.LabelFrame(
            self.root, padding=6,
            text="Filter hasil (tanpa memindai ulang; kosongkan untuk kembali ke hasil awal)")
        ff.pack(fill="x", padx=10, pady=(0, 6))
        r0 = ttk.Frame(ff)
        r0.pack(fill="x")
        ttk.Label(r0, text="Cari nama:").pack(side="left")
        self.term_var = tk.StringVar()
        ttk.Entry(r0, textvariable=self.term_var, width=40).pack(side="left", padx=6)
        self.like_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(r0, text="\u2665 Hanya yang di-like", variable=self.like_var).pack(side="left", padx=10)
        self.aaa_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(r0, text="Hanya game AAA", variable=self.aaa_var).pack(side="left", padx=6)
        ttk.Button(r0, text="Reset filter", command=self.reset_filter).pack(side="left", padx=6)
        self.genre_toggle = ttk.Button(r0, text="Sembunyikan genre", command=self.toggle_genres)
        self.genre_toggle.pack(side="left", padx=6)
        self.shown_var = tk.StringVar(value="")
        ttk.Label(r0, textvariable=self.shown_var).pack(side="right")

        self.genre_frame = ttk.Frame(ff)
        self.genre_frame.pack(fill="x", pady=(6, 0))
        self.genre_vars = []
        per_row = 7
        for i, (label, _tag) in enumerate(GENRES):
            v = tk.BooleanVar(value=False)
            self.genre_vars.append(v)
            ttk.Checkbutton(self.genre_frame, text=label, variable=v).grid(
                row=i // per_row, column=i % per_row, sticky="w", padx=8, pady=1)
        mf = ttk.Frame(self.genre_frame)
        mf.grid(row=(len(GENRES) + per_row - 1) // per_row, column=0, columnspan=per_row,
                sticky="w", pady=(6, 0))
        ttk.Label(mf, text="Cocokkan:").pack(side="left")
        self.match_var = tk.StringVar(value="any")
        ttk.Radiobutton(mf, text="Salah satu genre", variable=self.match_var, value="any").pack(side="left", padx=8)
        ttk.Radiobutton(mf, text="Semua genre", variable=self.match_var, value="all").pack(side="left")
        ttk.Button(mf, text="Kosongkan genre", command=self.clear_genres).pack(side="left", padx=14)

        # filter langsung jalan saat mengetik / mencentang (ditunda sebentar agar tidak berat)
        for var in [self.term_var, self.match_var, self.like_var, self.aaa_var] + self.genre_vars:
            var.trace_add("write", self.schedule_filter)

        mid = ttk.Frame(self.root, padding=(10, 0, 10, 10))
        mid.pack(fill="both", expand=True)
        cols = ("name", "rating", "review", "disc", "initial", "final", "end")
        self.tree = ttk.Treeview(mid, columns=cols, show="tree headings", selectmode="browse")
        self.tree.heading("#0", text="\u2665 Like / Cover")
        self.tree.column("#0", width=BAR_W + LIKE_W + COVER_W + 30, stretch=False)
        for col, text, w, anchor in [
                ("name", "Game", 320, "w"), ("rating", "Rating", 110, "w"),
                ("review", "Ulasan", 240, "w"), ("disc", "Diskon", 70, "center"),
                ("initial", "Harga normal", 110, "center"), ("final", "Harga diskon", 110, "center"),
                ("end", "Diskon berakhir", 130, "w")]:
            self.tree.heading(col, text=text, command=lambda c=col: self.sort_by(c))
            self.tree.column(col, width=w, anchor=anchor)
        sb = ttk.Scrollbar(mid, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        self.tree.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        self.tree.bind("<Button-1>", self.on_click)
        self.tree.bind("<Double-1>", self.open_link)
        self.tree.bind("<Button-3>", self.on_right_click)
        self.tree.bind("<KeyPress-l>", lambda _e: self.toggle_selected())
        self.tree.bind("<KeyPress-L>", lambda _e: self.toggle_selected())
        self.menu = tk.Menu(self.root, tearoff=0)
        self.menu.add_command(label="Like / Unlike", command=self.toggle_selected)
        self.menu.add_command(label="Buka di Steam", command=self.open_selected)

    def on_region(self, _=None):
        cfg = REGIONS[self.region_var.get()]
        self.min_var.set(str(cfg["min"]))
        self.max_var.set(str(cfg["max"]))

    def toggle_genres(self):
        if self.genre_frame.winfo_ismapped():
            self.genre_frame.pack_forget()
            self.genre_toggle.config(text="Tampilkan genre")
        else:
            self.genre_frame.pack(fill="x", pady=(6, 0))
            self.genre_toggle.config(text="Sembunyikan genre")

    def clear_genres(self):
        for v in self.genre_vars:
            v.set(False)

    def row_values(self, g):
        cfg = cfg_of(g)
        return (g["name"], stars_text(g.get("pct")), review_text(g), f"-{g['disc']}%",
                fmt_price(g["initial"], cfg), fmt_price(g["final"], cfg), end_text(g))

    def make_photo(self, aid):
        """Gambar kolom pertama (batang ulasan + ikon like + cover) untuk satu game."""
        g = self.games[aid]
        photo = ImageTk.PhotoImage(make_thumb(self.thumbs.get(aid), RCOLOR.get(review_class(g)),
                                              aid in self.liked))
        self.photos[aid] = photo
        return photo

    # ---------- filter hasil ----------
    def read_filter(self):
        self.f_tokens = self.term_var.get().lower().split()
        self.f_tags = {tag for (_, tag), v in zip(GENRES, self.genre_vars) if v.get()}
        self.f_any = self.match_var.get() == "any"
        self.f_like = bool(self.like_var.get())
        self.f_aaa = bool(self.aaa_var.get())

    def matches(self, g):
        return game_matches(g, self.f_tokens, self.f_tags, self.f_any, self.f_like, self.liked, self.f_aaa)

    def schedule_filter(self, *_):
        if self._fjob is not None:
            self.root.after_cancel(self._fjob)
        self._fjob = self.root.after(250, self.apply_filter)

    def apply_filter(self):
        """Tampilkan hanya game yang lolos filter, dengan urutan self.order. Tidak memindai ulang."""
        if self._fjob is not None:
            try:
                self.root.after_cancel(self._fjob)
            except Exception:
                pass
            self._fjob = None
        self.read_filter()
        visible = []
        for iid in self.order:
            if self.matches(self.games[iid]):
                visible.append(iid)
            else:
                self.tree.detach(iid)
        for idx, iid in enumerate(visible):
            self.tree.move(iid, "", idx)
        self.update_shown()

    def update_shown(self):
        if self.f_like:
            total = sum(1 for a in self.games if a in self.liked)
        else:
            total = sum(1 for g in self.games.values() if g.get("src") == "scan")
        parts = []
        if total:
            parts.append(f"Menampilkan {len(self.tree.get_children())} dari {total} game")
            if self.f_tags:
                missing = sum(1 for a, g in self.games.items()
                              if not g.get("tags") and (a in self.liked if self.f_like else g.get("src") == "scan"))
                if missing:
                    parts[0] += f" ({missing} game tanpa data genre tidak ikut)"
        if self.liked:
            parts.append(f"\u2665 {len(self.liked)} game di-like")
        self.shown_var.set("   |   ".join(parts))

    def reset_filter(self):
        """Kosongkan semua filter dan kembali ke hasil awal (urutan hasil pindai)."""
        self.term_var.set("")
        self.match_var.set("any")
        self.like_var.set(False)
        self.aaa_var.set(False)
        self.clear_genres()
        self.order = sorted(self.games, key=lambda a: self.games[a].get("_seq", -1))
        self.sort_rev.clear()
        self.apply_filter()

    def sort_by(self, col):
        """Klik judul kolom untuk mengurutkan. Rating/ulasan memakai skor yang menimbang jumlah ulasan."""
        score = lambda g: (rating_score(g), g.get("nrev") or 0)
        key = {"name": lambda g: g["name"].lower(), "rating": score, "review": score,
               "disc": lambda g: g["disc"], "initial": lambda g: g["initial"],
               "final": lambda g: g["final"], "end": lambda g: g.get("end") or 2 ** 62}[col]
        rev = self.sort_rev.get(col, col in ("rating", "review", "disc"))   # klik pertama: yang terbesar dulu
        self.order.sort(key=lambda i: key(self.games[i]), reverse=rev)
        self.sort_rev[col] = not rev
        self.apply_filter()

    # ---------- like ----------
    def load_snapshots(self):
        """Muat game yang di-like dari database (tersembunyi sampai opsi like dicentang)."""
        for g, blob, _liked_at in self.db.load_all():
            aid = g["appid"]
            if aid in self.games:
                continue
            g["src"] = "like"
            g["_seq"] = -1
            self.games[aid] = g
            self.order.append(aid)
            if blob:
                try:
                    img = Image.open(io.BytesIO(blob)).convert("RGB")
                    img.thumbnail((COVER_W, COVER_H))
                    self.covers[aid] = blob
                    self.thumbs[aid] = img
                except Exception:
                    pass
            self.tree.insert("", "end", iid=aid, image=self.make_photo(aid), values=self.row_values(g))
            self.tree.detach(aid)
        self.update_shown()

    def toggle_like(self, aid):
        g = self.games.get(aid)
        if g is None:
            return
        if aid in self.liked:
            self.liked.discard(aid)
            self.db.remove(aid)
            if g.get("src") == "like":      # hanya ada di database: hilangkan sepenuhnya
                self.drop_game(aid)
                self.order = [a for a in self.order if a in self.games]
        else:
            self.liked.add(aid)
            self.db.add(g, self.covers.get(aid))
        if aid in self.games:
            self.tree.item(aid, image=self.make_photo(aid))
        self.apply_filter()

    def toggle_selected(self):
        sel = self.tree.selection()
        if sel:
            self.toggle_like(sel[0])

    def open_selected(self):
        sel = self.tree.selection()
        if sel:
            webbrowser.open(f"https://store.steampowered.com/app/{sel[0]}/")

    def in_like_zone(self, event):
        """True kalau klik jatuh di area ikon hati (sisi kiri kolom pertama)."""
        iid = self.tree.identify_row(event.y)
        if not iid or self.tree.identify_column(event.x) != "#0":
            return None
        if self.tree.identify_region(event.x, event.y) not in ("tree", "cell"):
            return None
        bbox = self.tree.bbox(iid, "#0")
        if bbox and event.x - bbox[0] <= LIKE_HIT:
            return iid
        return None

    def on_click(self, event):
        iid = self.in_like_zone(event)
        if iid:
            self.toggle_like(iid)

    def on_right_click(self, event):
        iid = self.tree.identify_row(event.y)
        if iid:
            self.tree.selection_set(iid)
            self.menu.tk_popup(event.x_root, event.y_root)

    def open_link(self, event):
        if self.in_like_zone(event):
            return
        iid = self.tree.identify_row(event.y)
        if iid:
            webbrowser.open(f"https://store.steampowered.com/app/{iid}/")

    # ---------- data hasil pindai ----------
    def drop_game(self, aid):
        if self.tree.exists(aid):
            self.tree.delete(aid)
        for d in (self.games, self.covers, self.photos, self.thumbs):
            d.pop(aid, None)

    def remove_scan_games(self):
        """Buang hasil pindai lama. Game yang di-like tetap disimpan sebagai snapshot (tersembunyi)."""
        for aid in [a for a, g in self.games.items() if g.get("src") == "scan"]:
            if aid in self.liked:
                self.games[aid]["src"] = "like"
                self.tree.detach(aid)
            else:
                self.drop_game(aid)
        self.order = [a for a in self.order if a in self.games]

    def add_scan_game(self, g):
        aid = g["appid"]
        g["src"] = "scan"
        self.seq += 1
        g["_seq"] = self.seq
        self.read_filter()
        if aid in self.games:                # sebelumnya hanya snapshot (game yang di-like): segarkan datanya
            self.games[aid] = g
            if aid in self.order:
                self.order.remove(aid)
            self.order.append(aid)
            self.tree.item(aid, image=self.make_photo(aid), values=self.row_values(g))
            if aid in self.liked:
                self.db.update(g)
        else:
            self.games[aid] = g
            self.order.append(aid)
            self.tree.insert("", "end", iid=aid, image=self.make_photo(aid), values=self.row_values(g))
        if self.matches(g):
            self.tree.move(aid, "", "end")
        else:
            self.tree.detach(aid)
        self.update_shown()

    def set_cover(self, aid, data, img):
        self.covers[aid] = data
        self.thumbs[aid] = img
        self.tree.item(aid, image=self.make_photo(aid))
        if aid in self.liked:
            self.db.set_cover(aid, data)

    # ---------- pencarian ----------
    def start_search(self):
        if str(self.btn_search["state"]) == "disabled":
            return
        try:
            lo = float(self.min_var.get().replace(",", ""))
            hi = float(self.max_var.get().replace(",", ""))
            want = int(self.count_var.get())
            delay = max(float(self.delay_var.get()), 0)
        except ValueError:
            messagebox.showerror("Input salah", "Harga, jumlah game, dan jeda harus berupa angka.")
            return
        if lo > hi or want < 1:
            messagebox.showerror("Input salah", "Harga min harus <= harga max, dan jumlah minimal 1.")
            return
        tiers = [(a, b) for (_, a, b), v in zip(TIERS, self.tier_vars) if v.get()]
        if not tiers:
            messagebox.showerror("Input salah", "Pilih minimal satu tingkat diskon.")
            return
        self.cfg = REGIONS[self.region_var.get()]
        self.gen += 1
        self.remove_scan_games()
        self.sort_rev.clear()
        self.update_shown()
        self.cancel = threading.Event()
        self.btn_search.config(state="disabled")
        self.btn_stop.config(state="normal")
        self.status_var.set("Memulai...")
        threading.Thread(target=self.run, daemon=True,
                         args=(self.cfg, lo, hi, tiers, want, delay, self.cancel, self.gen)).start()

    def stop(self):
        self.cancel.set()
        self.status_var.set("Menghentikan...")

    def run(self, cfg, lo, hi, tiers, want, delay, cancel, gen):
        sc = Scraper(cfg["cc"], delay, cancel, lambda m: self.q.put(("status", m)))
        try:
            games = sc.search(lo, hi, tiers, want, lambda g: self.q.put(("game", g, gen)))
            msg = (f"Selesai: {len(games)} game ditemukan." if games
                   else "Tidak ada game yang cocok dengan rentang ini.")
            if 0 < len(games) < want:
                msg += " (di rentang ini hanya ada sebanyak itu)"
            if sc.details_failed:
                msg += " Catatan: tanggal berakhir dan genre gagal diambil dari Steam."
            else:
                if games and sc.got_dates == 0:
                    msg += " Catatan: Steam tidak memberi tanggal berakhir diskon."
                if games and sc.got_tags == 0:
                    msg += " Catatan: data genre tidak tersedia, filter genre tidak akan berfungsi."
                if games and sc.got_pubs == 0:
                    msg += " Catatan: data penerbit tidak tersedia, filter AAA hanya memakai perkiraan harga dan jumlah ulasan."
        except Cancelled:
            msg = "Dihentikan. Hasil sementara tetap bisa di-export."
        except Exception as e:
            msg = f"Error: {e} (hasil sementara tetap bisa di-export)"
        self.q.put(("done", msg))

    def load_cover(self, appid, url, gen):
        try:
            r = requests.get(url, headers=HEADERS, timeout=20)
            r.raise_for_status()
            img = Image.open(io.BytesIO(r.content)).convert("RGB")
            img.thumbnail((COVER_W, COVER_H))
            self.q.put(("cover", appid, gen, r.content, img))
        except Exception:
            pass

    def poll(self):
        try:
            while True:
                kind, *a = self.q.get_nowait()
                if kind == "status":
                    self.status_var.set(a[0])
                elif kind == "game" and a[1] == self.gen:
                    self.add_scan_game(a[0])
                    self.pool.submit(self.load_cover, a[0]["appid"], a[0]["img"], self.gen)
                elif kind == "cover" and a[1] == self.gen and a[0] in self.games:
                    self.set_cover(a[0], a[2], a[3])
                elif kind == "done":
                    self.status_var.set(a[0])
                    self.btn_search.config(state="normal")
                    self.btn_stop.config(state="disabled")
        except queue.Empty:
            pass
        self.root.after(100, self.poll)

    # ---------- export ----------
    def export(self):
        """Export sesuai tampilan saat ini (game yang disaring tidak ikut)."""
        ids = self.tree.get_children()
        if not ids:
            messagebox.showinfo("Kosong", "Belum ada hasil untuk di-export.")
            return
        path = filedialog.asksaveasfilename(defaultextension=".xlsx", initialfile="steam_diskon.xlsx",
                                            filetypes=[("Excel", "*.xlsx")])
        if not path:
            return
        rows = [self.games[i] for i in ids]
        try:
            write_excel(path, rows, self.covers if self.cover_var.get() else {})
        except PermissionError:
            messagebox.showerror("Gagal", "File tidak bisa ditulis. Tutup dulu file itu kalau sedang dibuka di Excel.")
        except Exception as e:
            messagebox.showerror("Gagal export", str(e))
        else:
            messagebox.showinfo("Berhasil", f"{len(rows)} game disimpan ke:\n{path}")

    def on_close(self):
        self.cancel.set()
        self.pool.shutdown(wait=False, cancel_futures=True)
        try:
            self.db.conn.close()
        except Exception:
            pass
        self.root.destroy()


def main():
    try:  # supaya tidak blur di layar Windows beresolusi tinggi
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
