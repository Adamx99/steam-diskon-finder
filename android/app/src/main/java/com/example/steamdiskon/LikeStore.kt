package com.example.steamdiskon

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

fun gameToJson(g: Game): String {
    val o = JSONObject()
    o.put("name", g.name)
    o.put("appid", g.appid)
    o.put("disc", g.disc)
    o.put("initial", g.initial)
    o.put("final", g.finalPrice)
    o.put("img", g.img)
    o.put("pct", g.pct ?: JSONObject.NULL)
    o.put("nrev", g.nrev ?: JSONObject.NULL)
    o.put("rlabel", g.rlabel ?: JSONObject.NULL)
    o.put("rcls", g.rcls ?: JSONObject.NULL)
    o.put("end", g.end ?: JSONObject.NULL)
    o.put("cc", g.cc)
    val pubs = g.pubs
    if (pubs.isNullOrEmpty()) {
        o.put("pubs", JSONObject.NULL)
    } else {
        val pa = JSONArray()
        for (p in pubs) pa.put(p)
        o.put("pubs", pa)
    }
    val tags = g.tags
    if (tags.isNullOrEmpty()) {
        o.put("tags", JSONObject.NULL)
    } else {
        val arr = JSONArray()
        for (t in tags.sorted()) arr.put(t)
        o.put("tags", arr)
    }
    return o.toString()
}

fun gameFromJson(text: String): Game {
    val o = JSONObject(text)
    val arr = o.optJSONArray("tags")
    var tags: Set<Int>? = null
    if (arr != null && arr.length() > 0) {
        val s = HashSet<Int>()
        for (i in 0 until arr.length()) s.add(arr.getInt(i))
        tags = s
    }
    val pa = o.optJSONArray("pubs")
    var pubs: List<String>? = null
    if (pa != null && pa.length() > 0) {
        val l = ArrayList<String>()
        for (i in 0 until pa.length()) l.add(pa.getString(i))
        pubs = l
    }
    return Game(
        name = o.getString("name"),
        appid = o.getString("appid"),
        disc = o.getInt("disc"),
        initial = o.getDouble("initial"),
        finalPrice = o.getDouble("final"),
        img = o.getString("img"),
        pct = if (o.isNull("pct")) null else o.getInt("pct"),
        nrev = if (o.isNull("nrev")) null else o.getInt("nrev"),
        rlabel = if (o.isNull("rlabel")) null else o.getString("rlabel"),
        rcls = if (o.isNull("rcls")) null else o.getString("rcls"),
        end = if (o.isNull("end")) null else o.getLong("end"),
        tags = tags,
        pubs = pubs,
        cc = o.optString("cc", "id"),
        seq = -1L,
        snapshot = true,
    )
}

/**
 * Database game yang di-like (SQLite di penyimpanan privat aplikasi): data game + cover,
 * supaya tetap ada setelah aplikasi ditutup. Hilang kalau aplikasi di-uninstall.
 * Semua fungsi memblokir: panggil dari Dispatchers.IO.
 */
class LikeStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "steam_diskon_likes.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE likes (appid TEXT PRIMARY KEY, data TEXT NOT NULL, " +
                "cover BLOB, liked_at TEXT NOT NULL, updated_at TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun now(): String = Instant.now().toString()

    /** Semua game yang di-like, terbaru di-like di atas: (game, cover, waktu like). */
    fun loadAll(): List<Triple<Game, ByteArray?, String>> {
        val out = ArrayList<Triple<Game, ByteArray?, String>>()
        readableDatabase.rawQuery(
            "SELECT data, cover, liked_at FROM likes ORDER BY liked_at DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                try {
                    val g = gameFromJson(c.getString(0))
                    val cover = if (c.isNull(1)) null else c.getBlob(1)
                    out.add(Triple(g, cover, c.getString(2)))
                } catch (e: Exception) {
                    // baris rusak dilewati
                }
            }
        }
        return out
    }

    fun add(g: Game, cover: ByteArray?) {
        val t = now()
        val v = ContentValues()
        v.put("appid", g.appid)
        v.put("data", gameToJson(g))
        if (cover != null) v.put("cover", cover) else v.putNull("cover")
        v.put("liked_at", t)
        v.put("updated_at", t)
        writableDatabase.insertWithOnConflict("likes", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Segarkan data game yang sudah di-like (misal setelah muncul lagi di hasil pindai). */
    fun update(g: Game) {
        val v = ContentValues()
        v.put("data", gameToJson(g))
        v.put("updated_at", now())
        writableDatabase.update("likes", v, "appid = ?", arrayOf(g.appid))
    }

    fun setCover(appid: String, cover: ByteArray) {
        val v = ContentValues()
        v.put("cover", cover)
        writableDatabase.update("likes", v, "appid = ?", arrayOf(appid))
    }

    fun remove(appid: String) {
        writableDatabase.delete("likes", "appid = ?", arrayOf(appid))
    }
}
