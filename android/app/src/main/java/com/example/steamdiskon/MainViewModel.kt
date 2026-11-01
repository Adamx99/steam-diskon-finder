package com.example.steamdiskon

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatInput(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else v.toString()

private fun toXlsxImage(bytes: ByteArray): XlsxImage? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    val ext = when (opts.outMimeType) {
        "image/png" -> "png"
        "image/jpeg" -> "jpeg"
        else -> return null
    }
    return XlsxImage(bytes, ext, opts.outWidth, opts.outHeight)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LikeStore(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- pengaturan pemindaian ----
    var region by mutableStateOf(REGIONS[0])
    var minText by mutableStateOf(formatInput(REGIONS[0].min))
    var maxText by mutableStateOf(formatInput(REGIONS[0].max))
    var countText by mutableStateOf("100")
    var delayText by mutableStateOf("2")
    val tierChecked = mutableStateListOf(true, true, true, true)

    // ---- filter hasil (bekerja pada hasil yang sudah dipindai) ----
    var term by mutableStateOf("")
    val genreChecked = mutableStateListOf<Boolean>().apply { repeat(GENRES.size) { add(false) } }
    var matchAny by mutableStateOf(true)
    var likeOnly by mutableStateOf(false)
    var aaaOnly by mutableStateOf(false)
    var showGenres by mutableStateOf(true)
    var sort by mutableStateOf(SortMode.SCAN)
    var withCovers by mutableStateOf(false)

    // ---- cara membuka game ----
    /** true = buka di browser; false (bawaan) = buka di WebView di dalam aplikasi. Tersimpan di pengaturan. */
    var openInBrowser by mutableStateOf(prefs.getBoolean("open_in_browser", false))
    /** Alamat halaman yang sedang tampil di WebView dalam aplikasi (null = WebView tertutup). */
    var webUrl by mutableStateOf<String?>(null)
    var webTitle by mutableStateOf("")

    // ---- data ----
    /** Hasil pindai + snapshot game yang di-like (snapshot = true, tersembunyi kecuali opsi like aktif). */
    var games by mutableStateOf<List<Game>>(emptyList())
    var liked by mutableStateOf<Set<String>>(emptySet())
    /** Cover game yang di-like (tersimpan di database, jadi tampil tanpa internet). */
    val covers = mutableStateMapOf<String, ByteArray>()

    var status by mutableStateOf("Siap. Atur harga lalu tekan Cari. Ketuk ikon hati untuk like, ketuk game untuk buka Steam.")
    var running by mutableStateOf(false)

    private var seq = 0L
    private var job: Job? = null

    init {
        loadSnapshots()
    }

    /** Muat game yang di-like dari database. */
    private fun loadSnapshots() {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { store.loadAll() }
                val ids = loaded.map { it.first.appid }.toSet()
                for (t in loaded) {
                    val blob = t.second
                    if (blob != null) covers[t.first.appid] = blob
                }
                liked = liked + ids
                games = loaded.map { it.first } + games.filter { it.appid !in ids }
                if (loaded.isNotEmpty()) {
                    status = "${loaded.size} game di-like dimuat dari database. Centang \"Hanya yang di-like\" untuk melihatnya."
                }
            } catch (e: Exception) {
                status = "Database like gagal dibuka: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun changeOpenInBrowser(v: Boolean) {
        openInBrowser = v
        prefs.edit().putBoolean("open_in_browser", v).apply()
    }

    fun showWeb(url: String, title: String) {
        webTitle = title
        webUrl = url
    }

    fun closeWeb() {
        webUrl = null
    }

    fun setRegionTo(r: Region) {
        region = r
        minText = formatInput(r.min)
        maxText = formatInput(r.max)
    }

    fun clearGenres() {
        for (i in genreChecked.indices) genreChecked[i] = false
    }

    /** Kosongkan semua filter dan kembali ke hasil awal (urutan hasil pindai). */
    fun resetFilter() {
        term = ""
        clearGenres()
        matchAny = true
        likeOnly = false
        aaaOnly = false
        sort = SortMode.SCAN
    }

    // ---------- like ----------
    fun toggleLike(g: Game) {
        val id = g.appid
        if (id in liked) {
            liked = liked - id
            covers.remove(id)
            if (g.snapshot) games = games.filter { it.appid != id }   // hanya ada di database: hilang sepenuhnya
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { store.remove(id) }
                } catch (e: Exception) {
                    status = "Gagal menghapus like: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        } else {
            liked = liked + id
            val cover = covers[id]
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { store.add(g, cover) }
                    if (cover == null) {
                        val bytes = fetchCover(g.img)
                        if (bytes != null && id in liked) {
                            covers[id] = bytes
                            withContext(Dispatchers.IO) { store.setCover(id, bytes) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = "Gagal menyimpan like: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    // ---------- pemindaian ----------
    /** Buang hasil pindai lama. Game yang di-like tetap ada sebagai snapshot (tersembunyi). */
    private fun removeScanGames() {
        games = games.mapNotNull { g ->
            if (g.snapshot) g
            else if (g.appid in liked) g.copy(snapshot = true)
            else null
        }
    }

    /** Gabungkan satu batch hasil pindai; snapshot game yang di-like disegarkan datanya. */
    private fun mergeScan(batch: List<Game>) {
        val existing = games.map { it.appid }.toSet()
        val replaced = HashMap<String, Game>()
        val appended = ArrayList<Game>()
        for (g0 in batch) {
            seq += 1
            val g = g0.copy(snapshot = false, seq = seq)
            if (g.appid in existing) replaced[g.appid] = g else appended.add(g)
        }
        games = games.map { replaced[it.appid] ?: it } + appended
        val refresh = replaced.values.filter { it.appid in liked }
        if (refresh.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { for (g in refresh) store.update(g) }
                } catch (e: Exception) {
                    // gagal menyegarkan snapshot: abaikan
                }
            }
        }
    }

    fun start() {
        if (running) return
        val lo = minText.replace(",", "").toDoubleOrNull()
        val hi = maxText.replace(",", "").toDoubleOrNull()
        val want = countText.trim().toIntOrNull()
        val delaySec = delayText.replace(",", ".").toDoubleOrNull()
        if (lo == null || hi == null || want == null || delaySec == null) {
            status = "Harga, jumlah game, dan jeda harus berupa angka."
            return
        }
        if (lo > hi || want < 1) {
            status = "Harga min harus <= harga max, dan jumlah minimal 1."
            return
        }
        val tiers = TIERS.filterIndexed { i, _ -> tierChecked[i] }
        if (tiers.isEmpty()) {
            status = "Pilih minimal satu tingkat diskon."
            return
        }
        val reg = region
        removeScanGames()
        running = true
        status = "Memulai..."
        job = viewModelScope.launch {
            val client = SteamClient(reg.cc, (maxOf(delaySec, 0.0) * 1000).toLong()) { status = it }
            try {
                val n = client.search(lo, hi, tiers, want) { batch -> mergeScan(batch) }
                var msg = if (n == 0) "Tidak ada game yang cocok dengan rentang ini." else "Selesai: $n game ditemukan."
                if (n in 1 until want) msg += " (di rentang ini hanya ada sebanyak itu)"
                if (client.detailsFailed) {
                    msg += " Catatan: tanggal berakhir dan genre gagal diambil dari Steam."
                } else {
                    if (n > 0 && client.gotDates == 0) msg += " Catatan: Steam tidak memberi tanggal berakhir diskon."
                    if (n > 0 && client.gotTags == 0) msg += " Catatan: data genre tidak tersedia, filter genre tidak akan berfungsi."
                    if (n > 0 && client.gotPubs == 0) msg += " Catatan: data penerbit tidak tersedia, filter AAA hanya memakai perkiraan harga dan jumlah ulasan."
                }
                status = msg
            } catch (e: CancellationException) {
                status = "Dihentikan. Hasil sementara tetap bisa di-export."
                throw e
            } catch (e: Exception) {
                status = "Error: ${e.message ?: e.javaClass.simpleName} (hasil sementara tetap bisa di-export)"
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    // ---------- export ----------
    /** Export sesuai tampilan saat ini ([rows] = daftar yang sedang terlihat, sudah berurutan). */
    fun export(context: Context, uri: Uri, rows: List<Game>) {
        val withCov = withCovers
        status = "Mengekspor ${rows.size} game..."
        viewModelScope.launch {
            try {
                val images = HashMap<String, XlsxImage>()
                if (withCov) {
                    val need = ArrayList<Game>()
                    for (g in rows) {
                        val stored = covers[g.appid]
                        val img = if (stored != null) toXlsxImage(stored) else null
                        if (img != null) images[g.appid] = img else need.add(g)
                    }
                    need.chunked(6).forEachIndexed { ci, chunk ->
                        status = "Mengunduh cover... ${ci * 6}/${need.size}"
                        val res = chunk.map { g -> async { Pair(g.appid, fetchCover(g.img)) } }.awaitAll()
                        for ((id, bytes) in res) {
                            if (bytes != null) {
                                val img = toXlsxImage(bytes)
                                if (img != null) images[id] = img
                            }
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Tidak bisa menulis file")
                    stream.use { XlsxWriter.write(it, rows, images) }
                }
                status = "Export selesai: ${rows.size} game disimpan."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Gagal export: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}
