package com.example.steamdiskon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val PAGE = 50
private const val SEARCH_URL = "https://store.steampowered.com/search/results/"
private const val API_URL = "https://api.steampowered.com/IStoreBrowseService/GetItems/v1"
private const val UA = "Mozilla/5.0"

val sharedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

private val ROW_RE = Regex("<a [^>]*data-ds-appid=\"([\\d,]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
private val TITLE_RE = Regex("<span class=\"title\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
private val DISC_RE = Regex("data-discount=\"(\\d+)\"")
private val FINAL_RE = Regex("discount_final_price[^>]*>(.*?)<", RegexOption.DOT_MATCHES_ALL)
private val ORIG_RE = Regex("discount_original_price[^>]*>(.*?)<", RegexOption.DOT_MATCHES_ALL)
private val IMG_RE = Regex("<img[^>]+src=\"([^\"]+)\"")
private val SPAN_RE = Regex("<span[^>]*search_review_summary[^>]*>", RegexOption.DOT_MATCHES_ALL)
private val RCLS_RE = Regex("search_review_summary\\s+(positive|mixed|negative)")
private val TIP_RE = Regex("data-tooltip-html=\"([^\"]*)\"")
private val PCT_RE = Regex("(\\d+)%\\s+of\\s+the\\s+([\\d,.]+)\\s+user reviews")
private val BR_RE = Regex("<br\\s*/?>")
private val DEC_ENTITY_RE = Regex("&#(\\d+);")
private val HEX_ENTITY_RE = Regex("&#x([0-9a-fA-F]+);")

fun unescapeHtml(s: String): String {
    var t = DEC_ENTITY_RE.replace(s) { m ->
        val cp = m.groupValues[1].toIntOrNull()
        val str = if (cp != null) runCatching { String(Character.toChars(cp)) }.getOrNull() else null
        str ?: m.value
    }
    t = HEX_ENTITY_RE.replace(t) { m ->
        val cp = m.groupValues[1].toIntOrNull(16)
        val str = if (cp != null) runCatching { String(Character.toChars(cp)) }.getOrNull() else null
        str ?: m.value
    }
    return t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
}

fun parsePrice(text: String?, cc: String): Double? {
    val t = (text ?: "").replace('\u00a0', ' ')
    if (cc == "us") {
        val m = Regex("\\d[\\d,]*(?:\\.\\d+)?").find(t) ?: return null
        return m.value.replace(",", "").toDoubleOrNull()
    }
    val d = t.filter { it in '0'..'9' }
    return if (d.isEmpty()) null else d.toDoubleOrNull()
}

/** Ubah results_html Steam jadi daftar game. */
fun parseItems(html: String, cc: String): List<Game> {
    val out = ArrayList<Game>()
    for (m in ROW_RE.findAll(html)) {
        val appid = m.groupValues[1].split(",")[0]
        val body = m.groupValues[2]
        val t = TITLE_RE.find(body) ?: continue
        val d = DISC_RE.find(body) ?: continue
        val f = FINAL_RE.find(body) ?: continue
        val o = ORIG_RE.find(body) ?: continue
        val fin = parsePrice(f.groupValues[1], cc) ?: continue
        val ini = parsePrice(o.groupValues[1], cc) ?: continue

        var pct: Int? = null
        var nrev: Int? = null
        var rlabel: String? = null
        var rcls: String? = null
        val sp = SPAN_RE.find(body)
        if (sp != null) {
            val tag = sp.value
            rcls = RCLS_RE.find(tag)?.groupValues?.get(1)
            val tip = TIP_RE.find(tag)
            if (tip != null) {
                val tt = unescapeHtml(tip.groupValues[1])
                val first = BR_RE.split(tt)[0].trim()
                rlabel = if (first.isEmpty()) null else first
                val pm = PCT_RE.find(tt)
                if (pm != null) {
                    pct = pm.groupValues[1].toIntOrNull()
                    val n = pm.groupValues[2].filter { it in '0'..'9' }.toIntOrNull()
                    nrev = if (n != null && n > 0) n else null
                }
            }
        }
        val imgSrc = IMG_RE.find(body)?.groupValues?.get(1)
        val img = if (imgSrc != null) unescapeHtml(imgSrc)
        else "https://cdn.cloudflare.steamstatic.com/steam/apps/$appid/capsule_sm_120.jpg"

        out.add(
            Game(
                name = unescapeHtml(t.groupValues[1]).trim(),
                appid = appid,
                disc = d.groupValues[1].toInt(),
                initial = ini,
                finalPrice = fin,
                img = img,
                pct = pct,
                nrev = nrev,
                rlabel = rlabel,
                rcls = rcls,
                end = null,
                tags = null,
                cc = cc,
            )
        )
    }
    return out
}

/** Unduh cover (dipakai saat export Excel). */
suspend fun fetchCover(url: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        sharedHttp.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    } catch (e: Exception) {
        null
    }
}

class SteamClient(
    private val cc: String,
    private val delayMs: Long,
    private val onStatus: (String) -> Unit,
) {
    var detailsFailed = false
    var gotDates = 0
    var gotTags = 0
    var gotPubs = 0

    private suspend fun getJson(url: HttpUrl, pauseMs: Long, tries: Int = 8): JSONObject {
        for (attempt in 1..tries) {
            val result: Triple<Int, String?, String>? = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(url).header("User-Agent", UA).build()
                    sharedHttp.newCall(req).execute().use { r ->
                        Triple(r.code, r.header("Retry-After"), r.body?.string() ?: "")
                    }
                } catch (e: IOException) {
                    null
                }
            }
            if (result == null) {
                onStatus("Gagal koneksi, mencoba lagi...")
                delay(5000L * attempt)
                continue
            }
            val (code, retryAfter, body) = result
            if (code == 429) {
                val wait = minOf(retryAfter?.toLongOrNull() ?: (30L * attempt), 300L)
                onStatus("Kena limit Steam (429), menunggu $wait detik...")
                delay(wait * 1000L)
                continue
            }
            if (code !in 200..299) throw IOException("HTTP $code dari Steam")
            delay(pauseMs)
            return JSONObject(body)
        }
        throw IOException("Steam terus menolak/gagal. Coba lagi beberapa menit lagi.")
    }

    private suspend fun fetchPage(start: Int): JSONObject {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("query", "")
            .addQueryParameter("start", start.toString())
            .addQueryParameter("count", PAGE.toString())
            .addQueryParameter("specials", "1")
            .addQueryParameter("sort_by", "Price_ASC")
            .addQueryParameter("category1", "998")
            .addQueryParameter("cc", cc)
            .addQueryParameter("l", "english")
            .addQueryParameter("infinite", "1")
            .build()
        return getJson(url, delayMs)
    }

    private suspend fun parseBatch(json: JSONObject): List<Game> =
        withContext(Dispatchers.Default) { parseItems(json.optString("results_html"), cc) }

    private var noBasic = false

    private suspend fun requestDetails(games: List<Game>, withBasic: Boolean): JSONObject {
        val ids = JSONArray()
        for (g in games) ids.put(JSONObject().put("appid", g.appid.toLong()))
        val dataRequest = JSONObject().put("include_reviews", true).put("include_tag_count", 20)
        if (withBasic) dataRequest.put("include_basic_info", true)
        val payload = JSONObject()
            .put("ids", ids)
            .put("context", JSONObject().put("language", "english").put("country_code", cc.uppercase()))
            .put("data_request", dataRequest)
        val url = API_URL.toHttpUrl().newBuilder()
            .addQueryParameter("input_json", payload.toString())
            .build()
        return getJson(url, 500L, tries = 2)
    }

    /** Ambil tanggal berakhir diskon, genre (tag), penerbit, dan ulasan bila kosong dari API toko Steam. */
    private suspend fun enrich(games: List<Game>): List<Game> {
        val data = try {
            try {
                requestDetails(games, !noBasic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (noBasic) throw e
                noBasic = true   // mungkin include_basic_info ditolak: coba lagi tanpa penerbit
                requestDetails(games, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            detailsFailed = true
            return games
        }
        val arr = data.optJSONObject("response")?.optJSONArray("store_items") ?: return games
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = if (item.has("appid")) item.optLong("appid") else item.optLong("id")
            byId[id.toString()] = item
        }
        return games.map { g ->
            val item = byId[g.appid] ?: return@map g
            val disc = item.optJSONObject("best_purchase_option")
                ?.optJSONArray("active_discounts")?.optJSONObject(0)
            val endRaw = disc?.optLong("discount_end_date", 0L) ?: 0L
            val end: Long? = if (endRaw > 0L) endRaw else null
            if (end != null) gotDates++

            val tagSet = HashSet<Int>()
            val tagIds = item.optJSONArray("tagids")
            if (tagIds != null) {
                for (i in 0 until tagIds.length()) tagSet.add(tagIds.optInt(i))
            } else {
                val tagObjs = item.optJSONArray("tags")
                if (tagObjs != null) {
                    for (i in 0 until tagObjs.length()) {
                        val o = tagObjs.optJSONObject(i) ?: continue
                        if (o.has("tagid")) tagSet.add(o.optInt("tagid"))
                    }
                }
            }
            val tags: Set<Int>? = if (tagSet.isNotEmpty()) tagSet else null
            if (tags != null) gotTags++

            var pubs: List<String>? = null
            val pubArr = item.optJSONObject("basic_info")?.optJSONArray("publishers")
            if (pubArr != null) {
                val names = ArrayList<String>()
                for (i in 0 until pubArr.length()) {
                    val nm = pubArr.optJSONObject(i)?.optString("name", "") ?: ""
                    if (nm.isNotEmpty()) names.add(nm)
                }
                if (names.isNotEmpty()) {
                    pubs = names
                    gotPubs++
                }
            }

            var res = g.copy(end = end, tags = tags, pubs = pubs)
            if (g.pct == null) {
                val rev = item.optJSONObject("reviews")?.optJSONObject("summary_filtered")
                if (rev != null && rev.has("percent_positive")) {
                    val cnt = rev.optInt("review_count", 0)
                    val lbl = rev.optString("review_score_label", "")
                    res = res.copy(
                        pct = rev.optInt("percent_positive"),
                        nrev = if (cnt > 0) cnt else null,
                        rlabel = if (lbl.isEmpty()) null else lbl,
                    )
                }
            }
            res
        }
    }

    /** Binary search halaman pertama yang harganya sudah >= lo. Null = tidak ada hasil sama sekali. */
    private suspend fun findStart(lo: Double): Pair<Int, Int>? {
        val first = fetchPage(0)
        val total = first.optInt("total_count", 0)
        if (total == 0) return null
        if (parseBatch(first).isEmpty()) {
            throw IOException("Tidak ada harga yang terbaca. Format Steam mungkin berubah.")
        }
        if (lo <= 0.0) return Pair(0, total)
        var a = 0
        var b = maxOf(total / PAGE, 1)
        while (a < b) {
            val mid = (a + b) / 2
            val its = parseBatch(fetchPage(mid * PAGE))
            if (its.isEmpty()) b = mid
            else if (its.last().finalPrice >= lo) b = mid
            else a = mid + 1
        }
        return Pair(maxOf(a - 1, 0) * PAGE, total)
    }

    /** Pindai Steam; tiap halaman yang lolos filter harga/diskon dilengkapi detailnya lalu dikirim ke onBatch. */
    suspend fun search(
        lo: Double,
        hi: Double,
        tiers: List<Tier>,
        want: Int,
        onBatch: (List<Game>) -> Unit,
    ): Int {
        onStatus("Mencari posisi harga...")
        val (startAt, total) = findStart(lo) ?: return 0
        var start = startAt
        val seen = HashSet<String>()
        var count = 0
        while (count < want && (total == 0 || start < total)) {
            onStatus("Memindai... $count/$want ditemukan")
            val its = parseBatch(fetchPage(start))
            if (its.isEmpty()) break
            var beyond = 0
            val page = ArrayList<Game>()
            for (g in its) {
                if (g.finalPrice > hi) {
                    beyond++
                    continue
                }
                if (g.finalPrice < lo || g.initial <= g.finalPrice || g.appid in seen) continue
                if (tiers.none { g.disc >= it.lo && g.disc <= it.hi }) continue
                seen.add(g.appid)
                page.add(g)
                if (count + page.size >= want) break
            }
            if (page.isNotEmpty()) {
                val enriched = enrich(page)
                count += enriched.size
                onBatch(enriched)
            }
            start += PAGE
            if (beyond == its.size) break
        }
        return count
    }
}
