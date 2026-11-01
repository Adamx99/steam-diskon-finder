@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.steamdiskon

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }
}

@Composable
fun App(vm: MainViewModel) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Filter hasil: dihitung ulang tiap filter/urutan/like berubah, tanpa memindai ulang.
    val selTags = GENRES.filterIndexed { i, _ -> vm.genreChecked[i] }.map { it.tag }.toSet()
    val tokens = vm.term.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val rows = remember(vm.games, vm.liked, vm.term, selTags, vm.matchAny, vm.likeOnly, vm.aaaOnly, vm.sort) {
        computeVisible(vm.games, vm.liked, tokens, selTags, vm.matchAny, vm.likeOnly, vm.aaaOnly, vm.sort)
    }
    val relevant = if (vm.likeOnly) vm.games.filter { it.appid in vm.liked } else vm.games.filter { !it.snapshot }
    val summary = summaryText(
        shown = rows.size,
        total = relevant.size,
        likedCount = vm.liked.size,
        genreActive = selTags.isNotEmpty(),
        missingTags = relevant.count { it.tags.isNullOrEmpty() },
    )
    val emptyMessage = if (relevant.isEmpty()) {
        "Geser layar ke kanan (atau ketuk \u2630) untuk membuka menu. Atur pemindaian dan filter, lalu tekan Cari."
    } else {
        "Tidak ada game yang cocok dengan filter. Buka menu lalu ubah filter atau tekan Reset filter."
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) vm.export(context.applicationContext, uri, rows)
    }

    // Ketuk game: buka di WebView dalam aplikasi, atau di browser kalau opsinya dicentang di sidebar.
    val openGame: (Game) -> Unit = { g ->
        val url = "https://store.steampowered.com/app/${g.appid}/?cc=${g.cc}"
        if (vm.openInBrowser) uriHandler.openUri(url) else vm.showWeb(url, g.name)
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    DrawerContent(
                        vm = vm,
                        summary = summary,
                        onSearch = {
                            vm.start()
                            scope.launch { drawerState.close() }
                        },
                        onExport = {
                            if (rows.isEmpty()) {
                                vm.status = "Belum ada hasil untuk di-export."
                            } else {
                                scope.launch { drawerState.close() }
                                launcher.launch("steam_diskon.xlsx")
                            }
                        },
                    )
                }
            },
        ) {
            MainScreen(
                vm = vm,
                rows = rows,
                summary = summary,
                emptyMessage = emptyMessage,
                onMenu = { scope.launch { drawerState.open() } },
                onOpenGame = openGame,
            )
        }

        val url = vm.webUrl
        if (url != null) {
            WebScreen(
                url = url,
                title = vm.webTitle,
                onClose = { vm.closeWeb() },
                onExternal = {
                    uriHandler.openUri(url)
                    vm.closeWeb()
                },
            )
        }
    }
}

/** Tampilan utama: hanya hasil. Menu (sidebar kiri) dibuka dengan geser ke kanan atau ketuk ikon menu. */
@Composable
fun MainScreen(
    vm: MainViewModel,
    rows: List<Game>,
    summary: String,
    emptyMessage: String,
    onMenu: () -> Unit,
    onOpenGame: (Game) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Steam Diskon Finder", fontWeight = FontWeight.Bold, maxLines = 1)
                        if (summary.isNotEmpty()) {
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onMenu) { Text("\u2630", fontSize = 22.sp) } },
                actions = {
                    if (vm.running) {
                        TextButton(onClick = { vm.stop() }) { Text("Stop") }
                    }
                },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(emptyMessage, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.appid }) { g ->
                    GameCard(
                        g = g,
                        liked = g.appid in vm.liked,
                        cover = vm.covers[g.appid],
                        onLike = { vm.toggleLike(g) },
                        onClick = { onOpenGame(g) },
                    )
                }
            }
        }
    }
}

/** Isi sidebar kiri: pemindaian, filter hasil, opsi buka game, lalu tombol Cari / Export di bawah. */
@Composable
fun DrawerContent(vm: MainViewModel, summary: String, onSearch: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            ScanPanel(vm)
            FilterPanel(vm, summary)
            OpenOptions(vm)
            Spacer(Modifier.height(8.dp))
        }
        Surface(tonalElevation = 3.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp)
            ) {
                Text(vm.status, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.running) {
                        Button(onClick = { vm.stop() }, modifier = Modifier.weight(1f)) { Text("Stop") }
                    } else {
                        Button(onClick = onSearch, modifier = Modifier.weight(1f)) { Text("Cari") }
                    }
                    OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export Excel") }
                }
            }
        }
    }
}

/** Pilihan cara membuka game saat diketuk. */
@Composable
fun OpenOptions(vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Buka game", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = vm.openInBrowser, onCheckedChange = { vm.changeOpenInBrowser(it) })
                Text("Buka di browser (bukan di dalam aplikasi)", modifier = Modifier.weight(1f))
            }
            Text(
                "Kalau tidak dicentang, halaman Steam game dibuka di dalam aplikasi.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Halaman Steam game di WebView dalam aplikasi (menutupi layar utama; tombol kembali menutupnya). */
@Composable
fun WebScreen(url: String, title: String, onClose: () -> Unit, onExternal: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onClose) { Text("\u2190", fontSize = 22.sp) } },
                    actions = { TextButton(onClick = onExternal) { Text("Browser") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            webViewClient = WebViewClient()
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            loadUrl(url)
                            webView = this
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.destroy()
                    },
                )
            }
        }
    }
}

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, integer: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** Pengaturan pemindaian (memindai Steam). */
@Composable
fun ScanPanel(vm: MainViewModel) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Steam Diskon Finder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Pemindaian", fontWeight = FontWeight.Medium)

        var menu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Region: ${vm.region.label}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                REGIONS.forEach { r ->
                    DropdownMenuItem(text = { Text(r.label) }, onClick = { vm.setRegionTo(r); menu = false })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Harga min", vm.minText, { vm.minText = it }, Modifier.weight(1f))
            NumField("Harga max", vm.maxText, { vm.maxText = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Jumlah game", vm.countText, { vm.countText = it }, Modifier.weight(1f), integer = true)
            NumField("Jeda (dtk)", vm.delayText, { vm.delayText = it }, Modifier.weight(1f))
        }
        Text("Diskon", fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TIERS.forEachIndexed { i, t ->
                FilterChip(
                    selected = vm.tierChecked[i],
                    onClick = { vm.tierChecked[i] = !vm.tierChecked[i] },
                    label = { Text(t.label) },
                )
            }
        }
    }
}

/** Filter hasil (bagian terpisah): cari nama, genre, hanya yang di-like. Tidak memindai ulang. */
@Composable
fun FilterPanel(vm: MainViewModel, summary: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Filter hasil", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Menyaring hasil yang sudah dipindai, tanpa memindai ulang. Kosongkan untuk kembali ke hasil awal.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (summary.isNotEmpty()) {
                Text(summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }

            OutlinedTextField(
                value = vm.term,
                onValueChange = { vm.term = it },
                label = { Text("Cari nama game") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = vm.likeOnly,
                    onClick = { vm.likeOnly = !vm.likeOnly },
                    label = { Text("\u2665 Hanya yang di-like") },
                )
                FilterChip(
                    selected = vm.aaaOnly,
                    onClick = { vm.aaaOnly = !vm.aaaOnly },
                    label = { Text("Hanya game AAA") },
                )
                TextButton(onClick = { vm.resetFilter() }) { Text("Reset filter") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Genre / fitur", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.showGenres = !vm.showGenres }) {
                    Text(if (vm.showGenres) "Sembunyikan" else "Tampilkan")
                }
                TextButton(onClick = { vm.clearGenres() }) { Text("Kosongkan") }
            }
            if (vm.showGenres) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GENRES.forEachIndexed { i, gn ->
                        FilterChip(
                            selected = vm.genreChecked[i],
                            onClick = { vm.genreChecked[i] = !vm.genreChecked[i] },
                            label = { Text(gn.label) },
                        )
                    }
                }
            }
            Text("Cocokkan genre:", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.matchAny, onClick = { vm.matchAny = true }, label = { Text("Salah satu") })
                FilterChip(selected = !vm.matchAny, onClick = { vm.matchAny = false }, label = { Text("Semua") })
            }

            Text("Urutkan", fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortMode.values().forEach { m ->
                    FilterChip(selected = vm.sort == m, onClick = { vm.sort = m }, label = { Text(m.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = vm.withCovers, onCheckedChange = { vm.withCovers = it })
                Text("Sertakan cover di Excel (lebih lambat)")
            }
        }
    }
}

/** Ikon like: hati merah penuh = di-like, hati abu-abu = belum. */
@Composable
fun HeartIcon(liked: Boolean) {
    val color = if (liked) Color(0xFFE53935) else Color(0xFF9E9E9E).copy(alpha = 0.6f)
    Canvas(modifier = Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(color = color, radius = w * 0.26f, center = Offset(w * 0.3f, h * 0.32f))
        drawCircle(color = color, radius = w * 0.26f, center = Offset(w * 0.7f, h * 0.32f))
        val tri = Path().apply {
            moveTo(w * 0.06f, h * 0.42f)
            lineTo(w * 0.94f, h * 0.42f)
            lineTo(w * 0.5f, h * 0.92f)
            close()
        }
        drawPath(path = tri, color = color)
    }
}

@Composable
fun CenterCell(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
fun GameCard(g: Game, liked: Boolean, cover: ByteArray?, onLike: () -> Unit, onClick: () -> Unit) {
    val rColor = reviewColor(reviewClass(g))?.let { Color(it) }
    val region = regionOf(g.cc)
    val coverBitmap = remember(cover) {
        cover?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(6.dp)
                        .height(45.dp)
                        .background(rColor ?: Color.Gray)
                )
                // Tombol like: di sebelah kiri cover
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onLike),
                    contentAlignment = Alignment.Center,
                ) { HeartIcon(liked) }
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 120.dp, height = 45.dp),
                    )
                } else {
                    AsyncImage(
                        model = g.img,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 120.dp, height = 45.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(starsText(g.pct), color = Color(0xFFF5A623), fontSize = 13.sp)
                    Text(
                        reviewText(g),
                        color = rColor ?: MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // Diskon + harga + tanggal berakhir: rata tengah
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CenterCell(Modifier.weight(1f)) {
                    Text(
                        "-${g.disc}%",
                        color = Color(0xFFBEEE11),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF4C6B22))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                CenterCell(Modifier.weight(1f)) {
                    Text(
                        formatPrice(g.initial, region),
                        textDecoration = TextDecoration.LineThrough,
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CenterCell(Modifier.weight(1f)) {
                    Text(
                        formatPrice(g.finalPrice, region),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                CenterCell(Modifier.weight(1f)) {
                    val d = endDateText(g)
                    if (d == null) {
                        Text("-", textAlign = TextAlign.Center)
                    } else {
                        Text("Berakhir", fontSize = 10.sp, textAlign = TextAlign.Center)
                        Text(d, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Text(endTimeText(g) ?: "", fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
