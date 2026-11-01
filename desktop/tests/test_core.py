"""
Tes logika inti Steam Diskon Finder dengan data palsu.
Tidak butuh internet atau layar: Tkinter ditiru bila tidak tersedia, dan Steam diganti fungsi palsu.
Jalankan dari akar repo:  python -m unittest discover -s desktop/tests -v
"""
import io
import json
import os
import sys
import tempfile
import threading
import unittest
from unittest.mock import MagicMock

for _m in ("tkinter", "tkinter.ttk", "tkinter.filedialog", "tkinter.messagebox"):
    try:
        __import__(_m)
    except Exception:
        sys.modules[_m] = MagicMock()

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import steam_diskon_app as s  # noqa: E402
from PIL import Image  # noqa: E402

s.ImageTk = MagicMock()  # PhotoImage butuh jendela Tk; tidak dipakai di tes


def make_game(i, final=10000, cc="id", tags=None, name=None):
    return {"name": name or f"Game {i}", "appid": str(i), "disc": 50, "initial": final * 2.0,
            "final": float(final), "img": f"http://x/{i}.jpg", "pct": 85, "nrev": 100,
            "rlabel": "Very Positive", "rcls": "positive", "end": 1793000000, "tags": tags, "cc": cc}


def jpeg_bytes():
    b = io.BytesIO()
    Image.new("RGB", (120, 45), "green").save(b, "JPEG")
    return b.getvalue()


class ParseTests(unittest.TestCase):
    def test_parse_items_with_review(self):
        html = ('<a href="x" data-ds-appid="12"><img src="http://img/12.jpg"><span class="title">A &amp; B</span>'
                '<span class="search_review_summary positive" data-tooltip-html="Very Positive&lt;br&gt;91% of the 1,234 user reviews for this game are positive."></span>'
                '<div data-discount="60"><div class="discount_original_price">Rp 50,000</div>'
                '<div class="discount_final_price">Rp 20,000</div></div></a>')
        g = s.parse_items({"results_html": html}, "id")[0]
        self.assertEqual((g["name"], g["appid"], g["disc"], g["initial"], g["final"]), ("A & B", "12", 60, 50000.0, 20000.0))
        self.assertEqual((g["pct"], g["nrev"], g["rlabel"], g["rcls"], g["cc"]), (91, 1234, "Very Positive", "positive", "id"))

    def test_parse_price_regions(self):
        self.assertEqual(s.parse_price("$4.99", "us"), 4.99)
        self.assertEqual(s.parse_price("Rp 120,000", "id"), 120000.0)
        self.assertEqual(s.parse_price("\u00a5 980", "jp"), 980.0)
        self.assertIsNone(s.parse_price("Free", "us"))


def by_pub(games):
    return {int(g["appid"]): g.get("pubs") for g in games}


class ScannerTests(unittest.TestCase):
    reject_basic = False

    def setUp(self):
        self.params = []
        self.reject_basic = False

        def html_of(i):
            f = 1000 * i
            return (f'<a href="x" data-ds-appid="{i}"><img src="http://img/{i}.jpg">'
                    f'<span class="title">{"Zombie" if i % 2 == 0 else "Game"} {i}</span>'
                    f'<div data-discount="50"><div class="discount_original_price">Rp {f*2:,}</div>'
                    f'<div class="discount_final_price">Rp {f:,}</div></div></a>')

        test = self

        def fake_get(scraper, url, params, delay, tries=8):
            if url == s.URL:
                self.params.append(dict(params))
                ids = list(range(1, 61))
                return {"results_html": "".join(html_of(i) for i in ids[params["start"]:params["start"] + 50]),
                        "total_count": len(ids)}
            req = json.loads(params["input_json"])
            if test.reject_basic and "include_basic_info" in req["data_request"]:
                raise RuntimeError("HTTP 400")
            items = []
            for x in req["ids"]:
                a = x["appid"]
                it = {"appid": a, "best_purchase_option": {"active_discounts": [{"discount_end_date": 1793000000}]}}
                if req["data_request"].get("include_basic_info") and a % 7 == 0:
                    it["basic_info"] = {"publishers": [{"name": "Ubisoft"}]}
                if a % 10 != 0:
                    it["tagids"] = ([1667] if a % 2 == 0 else []) + ([1663] if a % 3 == 0 else []) + [492]
                items.append(it)
            return {"response": {"store_items": items}}

        self._orig = s.Scraper._get
        s.Scraper._get = fake_get

    def tearDown(self):
        s.Scraper._get = self._orig

    def test_scan_filters_and_collects_tags(self):
        sc = s.Scraper("id", 0, threading.Event(), lambda m: None)
        games = sc.search(5000, 100000, [(1, 100)], 1000, lambda g: None)
        self.assertEqual(len(games), 56)
        self.assertEqual((sc.got_dates, sc.got_tags, sc.got_pubs), (56, 50, 8))
        self.assertEqual(by_pub(games)[7], ["Ubisoft"])
        by = {int(g["appid"]): g for g in games}
        self.assertEqual(by[12]["tags"], {1667, 1663, 492})
        self.assertIsNone(by[10]["tags"])
        self.assertTrue(all("term" not in p and "tags" not in p for p in self.params))

    def test_basic_info_rejected_falls_back(self):
        self.reject_basic = True
        sc = s.Scraper("id", 0, threading.Event(), lambda m: None)
        games = sc.search(5000, 100000, [(1, 100)], 1000, lambda g: None)
        self.assertTrue(sc.no_basic and not sc.details_failed)
        self.assertEqual((len(games), sc.got_tags, sc.got_pubs), (56, 50, 0))

    def test_want_limits_results(self):
        sc = s.Scraper("id", 0, threading.Event(), lambda m: None)
        self.assertEqual(len(sc.search(5000, 100000, [(1, 100)], 7, lambda g: None)), 7)


class FilterTests(unittest.TestCase):
    def test_game_matches(self):
        g = make_game(12, tags={1667, 1663}, name="Zombie 12")
        self.assertTrue(s.game_matches(g, ["zom", "12"], set(), True))
        self.assertFalse(s.game_matches(g, ["zom", "13"], set(), True))
        self.assertTrue(s.game_matches(g, [], {1667, 9}, True))
        self.assertFalse(s.game_matches(g, [], {1667, 9}, False))
        self.assertFalse(s.game_matches(make_game(1, tags=None), [], {1667}, True))
        snap = dict(make_game(2), src="like")
        self.assertFalse(s.game_matches(snap, [], set(), True))
        self.assertTrue(s.game_matches(snap, [], set(), True, True, {"2"}))
        self.assertFalse(s.game_matches(snap, [], set(), True, True, set()))


class RankingTests(unittest.TestCase):
    def test_many_votes_beat_few_votes(self):
        few = dict(make_game(1), pct=100, nrev=2)
        many = dict(make_game(2), pct=92, nrev=5000)
        unknown = dict(make_game(3), pct=95, nrev=None)
        self.assertGreater(s.rating_score(many), s.rating_score(few))
        self.assertEqual(s.rating_score(unknown), -1.0)
        ordered = sorted([few, unknown, many], key=s.rating_score, reverse=True)
        self.assertEqual([g["appid"] for g in ordered], ["2", "1", "3"])

    def test_more_votes_win_at_same_percentage(self):
        a = dict(make_game(1), pct=95, nrev=200)
        b = dict(make_game(2), pct=95, nrev=20000)
        self.assertGreater(s.rating_score(b), s.rating_score(a))


class AAATests(unittest.TestCase):
    def test_publisher_names(self):
        self.assertTrue(s.is_big_publisher("Ubisoft"))
        self.assertTrue(s.is_big_publisher("EA Sports"))
        self.assertTrue(s.is_big_publisher("2K Games"))
        self.assertFalse(s.is_big_publisher("Seaside Games"))
        self.assertFalse(s.is_big_publisher("Tiny Indie Studio"))

    def test_is_aaa(self):
        self.assertTrue(s.is_aaa(dict(make_game(1, 10000), pubs=["Capcom"], nrev=5)))          # penerbit besar
        self.assertTrue(s.is_aaa(dict(make_game(2, 300000), nrev=8000, tags={19})))            # harga + ulasan
        self.assertFalse(s.is_aaa(dict(make_game(3, 300000), nrev=8000, tags={19, 492})))      # tag Indie
        self.assertFalse(s.is_aaa(dict(make_game(4, 10000), nrev=8000)))                       # terlalu murah
        self.assertFalse(s.is_aaa(dict(make_game(5, 300000), nrev=100)))                       # ulasan sedikit
        self.assertTrue(s.is_aaa(dict(make_game(6, 20.0, "us"), nrev=5000)))
        self.assertFalse(s.is_aaa(dict(make_game(7, 7.5, "us"), nrev=5000)))

    def test_aaa_filter(self):
        big = dict(make_game(1, 300000), nrev=8000)
        small = dict(make_game(2, 10000), nrev=50)
        self.assertTrue(s.game_matches(big, [], set(), True, aaa_only=True))
        self.assertFalse(s.game_matches(small, [], set(), True, aaa_only=True))
        self.assertTrue(s.game_matches(small, [], set(), True))


class LikeDBTests(unittest.TestCase):
    def test_roundtrip(self):
        db = s.LikeDB(os.path.join(tempfile.mkdtemp(), "likes.db"))
        g = dict(make_game(5, tags={1, 2}), pubs=["Capcom"])
        db.add(g, b"cover")
        self.assertEqual(db.ids(), {"5"})
        loaded, cover, _ = db.load_all()[0]
        self.assertEqual((loaded["tags"], cover, loaded["pubs"]), ({1, 2}, b"cover", ["Capcom"]))
        g["final"] = 123.0
        db.update(g)
        self.assertEqual(db.load_all()[0][0]["final"], 123.0)
        db.remove("5")
        self.assertEqual(db.ids(), set())


class FakeTree:
    def __init__(self):
        self.items, self.all = [], {}

    def insert(self, parent, idx, iid=None, **kw):
        assert iid not in self.all
        self.all[iid] = kw
        self.items.append(iid)

    def exists(self, iid): return iid in self.all

    def detach(self, iid):
        if iid in self.items:
            self.items.remove(iid)

    def move(self, iid, parent, idx):
        if iid in self.items:
            self.items.remove(iid)
        self.items.append(iid) if idx == "end" else self.items.insert(idx, iid)

    def delete(self, iid):
        self.detach(iid)
        self.all.pop(iid)

    def item(self, iid, **kw): self.all[iid].update(kw)
    def get_children(self): return tuple(self.items)


class Var:
    def __init__(self, v): self.v = v
    def get(self): return self.v
    def set(self, v): self.v = v


class AppLogicTests(unittest.TestCase):
    def setUp(self):
        self.dbpath = os.path.join(tempfile.mkdtemp(), "likes.db")

    def new_app(self):
        a = s.App.__new__(s.App)
        a.root = MagicMock()
        a._fjob = None
        a.tree = FakeTree()
        a.term_var, a.match_var, a.like_var, a.shown_var = Var(""), Var("any"), Var(False), Var("")
        a.aaa_var = Var(False)
        a.genre_vars = [Var(False) for _ in s.GENRES]
        a.games, a.covers, a.photos, a.thumbs = {}, {}, {}, {}
        a.order, a.seq, a.sort_rev = [], 0, {}
        a.f_tokens, a.f_tags, a.f_any, a.f_like, a.f_aaa = [], set(), True, False, False
        a.db = s.LikeDB(self.dbpath)
        a.liked = a.db.ids()
        return a

    @staticmethod
    def shown(a): return sorted(int(i) for i in a.tree.get_children())

    @staticmethod
    def thumb():
        im = Image.open(io.BytesIO(jpeg_bytes())).convert("RGB")
        im.thumbnail((120, 45))
        return im

    def test_like_flow_across_restart(self):
        a1 = self.new_app()
        for i in range(1, 6):
            a1.add_scan_game(make_game(i))
        a1.set_cover("2", jpeg_bytes(), self.thumb())
        a1.toggle_like("2")
        a1.toggle_like("4")
        self.assertEqual(a1.db.ids(), {"2", "4"})
        a1.db.conn.close()

        a2 = self.new_app()                     # program dibuka ulang
        a2.load_snapshots()
        self.assertEqual(self.shown(a2), [])    # tersembunyi sampai opsi like dicentang
        a2.like_var.set(True)
        a2.apply_filter()
        self.assertEqual(self.shown(a2), [2, 4])

        a2.like_var.set(False)                  # pindai baru menyegarkan data like
        a2.apply_filter()                       # (di aplikasi asli dipicu otomatis oleh trace)
        for i in (1, 2, 3):
            a2.add_scan_game(make_game(i, final=7000 if i == 2 else 10000))
        self.assertEqual(self.shown(a2), [1, 2, 3])
        refreshed = {g["appid"]: g for g, _, _ in a2.db.load_all()}["2"]
        self.assertEqual(refreshed["final"], 7000.0)

        a2.remove_scan_games()                  # pindai ulang: yang di-like tetap ada
        self.assertEqual(set(a2.games), {"2", "4"})

        a2.like_var.set(True)
        a2.apply_filter()
        a2.toggle_like("4")                     # un-like snapshot menghapusnya total
        self.assertNotIn("4", a2.games)
        self.assertEqual(a2.db.ids(), {"2"})

        for i in (7, 8):
            a2.add_scan_game(make_game(i))
        a2.reset_filter()
        self.assertEqual(self.shown(a2), [7, 8])
        self.assertFalse(a2.like_var.get())

    def test_filter_sort_reset(self):
        a = self.new_app()
        for i in range(1, 9):
            a.add_scan_game(make_game(i, tags={1667} if i % 2 == 0 else {492}, name=f"{'Zombie' if i % 2 == 0 else 'Game'} {i}"))
        scan_order = list(a.tree.get_children())
        a.term_var.set("zombie")
        a.apply_filter()
        self.assertEqual(self.shown(a), [2, 4, 6, 8])
        a.term_var.set("")
        for (n, _), v in zip(s.GENRES, a.genre_vars):
            v.set(n == "Horor")
        a.apply_filter()
        self.assertEqual(self.shown(a), [2, 4, 6, 8])
        a.sort_by("name")
        a.reset_filter()
        self.assertEqual(list(a.tree.get_children()), scan_order)

    def test_sort_rating_prefers_many_votes_and_aaa_filter(self):
        a = self.new_app()
        a.add_scan_game(dict(make_game(1, 300000), pct=100, nrev=2))
        a.add_scan_game(dict(make_game(2, 300000), pct=92, nrev=9000))
        a.add_scan_game(dict(make_game(3, 10000), pct=97, nrev=40000, tags={492}))
        a.sort_by("rating")                                  # klik pertama: terbaik dulu
        self.assertEqual(list(a.tree.get_children()), ["3", "2", "1"])
        a.aaa_var.set(True)
        a.apply_filter()
        self.assertEqual(self.shown(a), [2])                 # hanya yang lolos perkiraan AAA
        a.reset_filter()
        self.assertFalse(a.aaa_var.get())
        self.assertEqual(self.shown(a), [1, 2, 3])


class ExcelTests(unittest.TestCase):
    def test_per_region_number_format(self):
        import openpyxl
        path = os.path.join(tempfile.mkdtemp(), "t.xlsx")
        s.write_excel(path, [make_game(1, 10000, "id"), make_game(2, 5.99, "us")], {"1": jpeg_bytes()})
        ws = openpyxl.load_workbook(path).active
        self.assertEqual((ws["G2"].number_format, ws["G3"].number_format), ("#,##0", "#,##0.00"))
        self.assertEqual(len(ws._images), 1)
        self.assertEqual(ws["E2"].font.color.rgb, "00D32F2F")


if __name__ == "__main__":
    unittest.main()
