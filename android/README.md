# Steam Diskon Finder (Android)

Aplikasi Android native (Kotlin + Jetpack Compose), bukan wrapper. Jalan sendiri di HP,
tanpa PC. PC hanya dipakai sekali untuk membangun (build) APK-nya.
Fiturnya disamakan dengan versi desktop.

## Cara pakai
Tampilan utama hanya menampilkan hasil. Semua pengaturan ada di **sidebar kiri**:
geser layar ke kanan, atau ketuk ikon menu (☰) di pojok kiri atas.

1. Di sidebar, atur region, rentang harga, jumlah game, dan tingkat diskon di bagian
   **Pemindaian**, lalu tekan **Cari** (di bagian bawah sidebar). Sidebar menutup sendiri
   dan hasil muncul di tampilan utama. Selama memindai, tombol **Stop** ada di pojok kanan atas.
   Status (kemajuan pemindaian, pesan galat) tampil di sidebar, tepat di atas tombol Cari.
2. Setelah ada hasil, bagian **Filter hasil** (di sidebar) menyaring daftar tanpa memindai ulang:
   - kolom cari nama (semua kata harus ada di nama game)
   - ceklist genre/fitur (FPS, MMO, RPG, Single-player, Multiplayer, Horor, Visual Novel, dll),
     cocokkan "Salah satu" atau "Semua" genre
   - opsi **Hanya yang di-like**
   - opsi **Hanya game AAA** (perkiraan: penerbit besar, atau harga normal tinggi + ulasan sangat banyak + bukan Indie)
   - **Reset filter** atau kosongkan filter untuk kembali ke hasil awal
3. Ketuk **ikon hati** di sebelah kiri cover untuk like / batal like.
4. Ketuk kartu game untuk membuka halaman Steam-nya. Bawaannya dibuka **di dalam aplikasi**
   (WebView); tombol panah kiri atau tombol Kembali menutupnya, tombol "Browser" membukanya
   di browser. Centang **Buka di browser** di sidebar kalau mau selalu memakai browser
   (pilihan ini diingat setelah aplikasi ditutup).
5. **Export Excel** (di bagian bawah sidebar) menyimpan daftar sesuai tampilan saat ini
   (game yang disaring tidak ikut), lewat pemilih file Android (tanpa izin storage).

## Urutan rating
Urutan **Rating tertinggi (banyak ulasan)** memakai skor yang menimbang jumlah ulasan, sehingga game
dengan 100% dari 1-2 ulasan tidak berada di atas game dengan persen tinggi dari ribuan ulasan.

## Like dan database
- Game yang di-like disimpan di database SQLite di penyimpanan privat aplikasi
  (data game + cover). Setelah aplikasi dibuka ulang, centang **Hanya yang di-like**
  untuk memantau game tersimpan, tanpa memindai ulang.
- Kalau game yang di-like muncul lagi di hasil pindai baru, datanya (harga, ulasan,
  tanggal berakhir) otomatis diperbarui di database. Game yang tidak muncul tetap
  menampilkan data terakhir yang tersimpan, jadi harganya bisa sudah basi.
- Pindai ulang membuang hasil lama dari daftar, tapi game yang di-like tidak ikut terbuang.
- Database ikut terhapus kalau aplikasi di-uninstall.

## Cara membangun APK
1. Pasang Android Studio, lalu File > Open dan pilih folder ini.
2. Tunggu Gradle sync selesai (butuh internet; kalau diminta upgrade plugin, boleh).
3. Colok HP (USB debugging aktif) lalu klik Run, atau Build > Build APK(s).
4. APK ada di `app/build/outputs/apk/debug/app-debug.apk`. Kirim ke HP dan install
   (izinkan "install dari sumber tidak dikenal").

## Catatan
- Kode ini BELUM pernah dikompilasi atau dijalankan, karena lingkungan saya tidak punya
  Android SDK maupun akses ke Steam. Kalau Android Studio menampilkan error, kirim pesannya.
- Warna ulasan ada di `Models.kt` (COLOR_POS / COLOR_NEG / COLOR_MIX).
- Daftar genre ada di `Models.kt` (GENRES), tiap genre = nama + ID tag Steam.
  Data tag tiap game diambil dari API toko Steam (maks. 20 tag teratas).
