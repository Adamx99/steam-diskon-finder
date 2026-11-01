package com.example.steamdiskon

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Warna ulasan (ARGB). Tukar nilainya di sini kalau mau.
// Steam asli: positif biru, negatif merah.
const val COLOR_POS = 0xFFD32F2FL   // positif = merah
const val COLOR_NEG = 0xFF2E7D32L   // negatif = hijau
const val COLOR_MIX = 0xFFF9A825L   // campuran = kuning

data class Region(
    val label: String,
    val cc: String,
    val sym: String,
    val min: Double,
    val max: Double,
    val dec: Int,
)

val REGIONS = listOf(
    Region("Indonesia (Rp)", "id", "Rp", 10000.0, 100000.0, 0),
    Region("Amerika Serikat (USD)", "us", "\$", 1.0, 10.0, 2),
    Region("Jepang (JPY)", "jp", "\u00a5", 100.0, 1000.0, 0),
)

/** Mata uang sesuai region tempat data game itu diambil. */
fun regionOf(cc: String): Region = REGIONS.firstOrNull { it.cc == cc } ?: REGIONS[0]

data class Tier(val label: String, val lo: Int, val hi: Int)

val TIERS = listOf(
    Tier("1-24%", 1, 24),
    Tier("25-49%", 25, 49),
    Tier("50-74%", 50, 74),
    Tier("75-100%", 75, 100),
)

/**
 * Genre / fitur untuk filter hasil. [tag] = ID tag komunitas Steam.
 * Data tag tiap game diambil dari API toko Steam (maks. 20 tag teratas).
 */
data class Genre(val label: String, val tag: Int)

val GENRES = listOf(
    Genre("Action", 19), Genre("Adventure", 21), Genre("RPG", 122), Genre("FPS", 1663),
    Genre("Shooter", 1774), Genre("Strategy", 9), Genre("Simulation", 599), Genre("Horor", 1667),
    Genre("Visual Novel", 3799), Genre("Puzzle", 1664), Genre("Racing", 699), Genre("Sports", 701),
    Genre("Open World", 1695), Genre("Survival", 1662), Genre("Roguelike", 1716),
    Genre("Platformer", 1625), Genre("Fighting", 1743), Genre("Anime", 4085),
    Genre("Indie", 492), Genre("Casual", 597), Genre("Single-player", 4182),
    Genre("Multiplayer", 3859), Genre("Co-op", 1685), Genre("Online Co-op", 3843),
    Genre("PvP", 1775), Genre("MMO", 128), Genre("MMORPG", 1754),
)

/**
 * [snapshot] = true: game hanya ada di database like (bukan dari hasil pindai saat ini);
 * disembunyikan kecuali opsi "Hanya yang di-like" aktif.
 * [seq] = urutan kedatangan hasil pindai (snapshot = -1).
 */
data class Game(
    val name: String,
    val appid: String,
    val disc: Int,
    val initial: Double,
    val finalPrice: Double,
    val img: String,
    val pct: Int?,
    val nrev: Int?,
    val rlabel: String?,
    val rcls: String?,
    val end: Long?,
    val tags: Set<Int>? = null,
    val pubs: List<String>? = null,
    val cc: String = "id",
    val seq: Long = 0L,
    val snapshot: Boolean = false,
)

enum class SortMode(val label: String) {
    SCAN("Urutan pindai"),
    DISCOUNT("Diskon terbesar"),
    RATING("Rating tertinggi (banyak ulasan)"),
    ENDING("Berakhir terdekat"),
    NAME("Nama A-Z"),
}

// ---- Filter "AAA" ----
// Steam tidak punya penanda AAA resmi, jadi ini PERKIRAAN: game dari penerbit besar, atau game
// mahal (harga normal di atas batas) dengan ulasan sangat banyak dan tanpa tag Indie.
const val INDIE_TAG = 492
const val AAA_MIN_REVIEWS = 2000
val AAA_MIN_PRICE = mapOf("us" to 29.0, "id" to 450000.0, "jp" to 3800.0)   // harga normal minimum per region
val AAA_PUBLISHERS = listOf(
    "electronic arts", "ea", "ubisoft", "activision", "blizzard", "bethesda", "zenimax", "capcom",
    "square enix", "sony", "playstation", "bandai namco", "sega", "take-two", "2k", "rockstar",
    "warner bros", "cd projekt", "konami", "microsoft", "xbox game studios", "valve",
    "koei tecmo", "larian",
)
private val AAA_PUBLISHER_REGEXES = AAA_PUBLISHERS.map { Regex("\\b" + Regex.escape(it) + "\\b") }

fun isBigPublisher(name: String): Boolean {
    val n = name.lowercase()
    return AAA_PUBLISHER_REGEXES.any { it.containsMatchIn(n) }
}

fun isAaa(g: Game): Boolean {
    val pubs = g.pubs
    if (pubs != null && pubs.any { isBigPublisher(it) }) return true
    val minPrice = AAA_MIN_PRICE[g.cc] ?: AAA_MIN_PRICE.getValue("us")
    if (g.initial < minPrice) return false
    if ((g.nrev ?: 0) < AAA_MIN_REVIEWS) return false
    val tags = g.tags
    if (tags != null && INDIE_TAG in tags) return false
    return true
}

/**
 * Skor peringkat = batas bawah Wilson dari persen ulasan positif dan jumlah ulasan.
 * Contoh: 100% dari 2 ulasan kalah dari 92% dari 5.000 ulasan. Tanpa data jumlah ulasan = -1.
 */
fun ratingScore(g: Game): Double {
    val pct = g.pct ?: return -1.0
    val n = g.nrev ?: return -1.0
    if (n <= 0) return -1.0
    val p = pct / 100.0
    val z = 1.96
    val nn = n.toDouble()
    val denom = 1 + z * z / nn
    val centre = p + z * z / (2 * nn)
    val margin = z * Math.sqrt((p * (1 - p) + z * z / (4 * nn)) / nn)
    return (centre - margin) / denom
}

/** Filter tampilan, sama seperti versi desktop. */
fun gameMatches(
    g: Game,
    tokens: List<String>,
    selTags: Set<Int>,
    anyMode: Boolean,
    likeOnly: Boolean,
    liked: Set<String>,
    aaaOnly: Boolean = false,
): Boolean {
    if (g.snapshot && !likeOnly) return false
    if (likeOnly && g.appid !in liked) return false
    if (aaaOnly && !isAaa(g)) return false
    val name = g.name.lowercase()
    for (t in tokens) {
        if (!name.contains(t)) return false
    }
    if (selTags.isEmpty()) return true
    val tags = g.tags
    if (tags.isNullOrEmpty()) return false
    return if (anyMode) tags.any { it in selTags } else tags.containsAll(selTags)
}

fun computeVisible(
    all: List<Game>,
    liked: Set<String>,
    tokens: List<String>,
    selTags: Set<Int>,
    anyMode: Boolean,
    likeOnly: Boolean,
    aaaOnly: Boolean,
    mode: SortMode,
): List<Game> {
    val filtered = all.filter { gameMatches(it, tokens, selTags, anyMode, likeOnly, liked, aaaOnly) }
    return when (mode) {
        SortMode.SCAN -> filtered.sortedBy { it.seq }
        SortMode.DISCOUNT -> filtered.sortedByDescending { it.disc }
        SortMode.RATING -> filtered.sortedWith(
            compareByDescending<Game> { ratingScore(it) }.thenByDescending { it.nrev ?: 0 }
        )
        SortMode.ENDING -> filtered.sortedBy { it.end ?: Long.MAX_VALUE }
        SortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
    }
}

fun summaryText(shown: Int, total: Int, likedCount: Int, genreActive: Boolean, missingTags: Int): String {
    val parts = ArrayList<String>()
    if (total > 0) {
        var s = "Menampilkan $shown dari $total game"
        if (genreActive && missingTags > 0) s += " ($missingTags game tanpa data genre tidak ikut)"
        parts.add(s)
    }
    if (likedCount > 0) parts.add("\u2665 $likedCount game di-like")
    return parts.joinToString("  |  ")
}

fun reviewClass(g: Game): String? {
    val c = g.rcls
    if (c == "positive" || c == "mixed" || c == "negative") return c
    val p = g.pct ?: return null
    return if (p >= 70) "positive" else if (p >= 40) "mixed" else "negative"
}

fun reviewColor(cls: String?): Long? = when (cls) {
    "positive" -> COLOR_POS
    "mixed" -> COLOR_MIX
    "negative" -> COLOR_NEG
    else -> null
}

fun starsText(pct: Int?): String {
    if (pct == null) return "-"
    val s = pct / 20.0
    val n = Math.round(s).toInt()
    return "\u2605".repeat(n) + "\u2606".repeat(5 - n) + " " + String.format(Locale.US, "%.1f", s)
}

fun reviewText(g: Game): String {
    val p = g.pct ?: return "-"
    val label = g.rlabel ?: (reviewClass(g) ?: "").replaceFirstChar { it.uppercase() }
    val n = g.nrev
    if (n != null && n > 0) {
        val nTxt = String.format(Locale.US, "%,d", n)
        return "$label ($p%, $nTxt ulasan)"
    }
    return "$label ($p%)"
}

fun formatPrice(v: Double, r: Region): String {
    val pattern = if (r.dec > 0) "#,##0.00" else "#,##0"
    val num = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(v)
    return if (r.dec > 0) r.sym + num else r.sym + " " + num
}

private fun fmtEnd(g: Game, pattern: String): String? {
    val e = g.end ?: return null
    return try {
        DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(e))
    } catch (ex: Exception) {
        null
    }
}

fun endDateText(g: Game): String? = fmtEnd(g, "dd MMM yyyy")

fun endTimeText(g: Game): String? = fmtEnd(g, "HH:mm")
