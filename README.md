# Steam Diskon Finder

[English](README.en.md) | **Bahasa Indonesia**

Pemindai game yang sedang diskon di Steam, dengan filter harga dan diskon per region,
filter genre, daftar like yang tersimpan, dan export Excel. Tersedia untuk **desktop**
(Python + Tkinter, bisa dijadikan `.exe` portable) dan **Android** (Kotlin + Jetpack Compose).

> **Bukan produk resmi.** Proyek ini tidak berafiliasi dengan Valve atau Steam. "Steam"
> adalah merek dagang Valve Corporation.

## Status

Proyek eksperimental. Logika inti (pemindaian, filter, like, export Excel) dites dengan data
palsu, tetapi **belum dicoba langsung ke server Steam**, dan **versi Android belum pernah
dikompilasi**. Aplikasi memakai endpoint toko Steam yang tidak resmi, jadi bisa berhenti
bekerja kalau Steam mengubah format. Laporkan masalah lewat Issues.

## Fitur

- Pindai game diskon per region (Indonesia / AS / Jepang), harga mengikuti mata uang region
- Rentang harga, ceklist tingkat diskon (1-24%, 25-49%, 50-74%, 75-100%), jumlah hasil bebas
- Cover, rating bintang, ulasan berwarna (batang warna di desktop, teks berwarna di Excel),
  tanggal berakhir diskon
- **Filter hasil** tanpa memindai ulang: cari nama, ceklist genre (FPS, RPG, MMO, Horor,
  Visual Novel, dll; cocok "salah satu" atau "semua"), kosongkan untuk kembali ke hasil awal
- **Like**: simpan game ke database lokal, lalu pantau lewat opsi "Hanya yang di-like" setelah
  aplikasi dibuka ulang. Data like disegarkan otomatis saat game muncul lagi di hasil pindai
- **Filter game AAA** (perkiraan, lihat bagian Batasan) dan **urutan rating yang menimbang jumlah ulasan**:
  game dengan 100% dari 2 ulasan tidak lagi mengalahkan game dengan 92% dari ribuan ulasan
- Urutkan hasil, export Excel (`.xlsx`) sesuai tampilan, dengan opsi menyertakan cover
- Android: sidebar kiri (geser ke kanan) untuk pengaturan, halaman game dibuka di WebView dalam
  aplikasi atau di browser (bisa dipilih)

## Desktop

Butuh Python 3.11 atau lebih baru (Tkinter sudah termasuk di installer Windows; di Linux:
`sudo apt install python3-tk`).

```bash
cd desktop
python -m pip install -r requirements.txt
python steam_diskon_app.py
```

### Membuat `.exe` portable (Windows)

Jalankan `desktop/build.bat`. Skrip memilih Python 3.11-3.14 yang terpasang lewat launcher `py`
dan menghasilkan `desktop/dist/SteamDiskonFinder.exe`. Jangan pakai Python 3.10.0: ada bug yang
membuat PyInstaller gagal (diperbaiki di 3.10.1).

Database like (`steam_diskon_likes.db`) disimpan di folder yang sama dengan program/exe, jadi ikut
portable. Kalau folder itu tidak bisa ditulis, file pindah ke folder home.

### Tes

```bash
python -m unittest discover -s desktop/tests -v
```

Tes memakai data palsu dan Tkinter tiruan, jadi tidak butuh internet atau layar.

## Android

Lihat [`android/README.md`](android/README.md). Singkatnya: buka folder `android/` di Android Studio,
tunggu Gradle sync, lalu Run atau Build > Build APK(s). Wrapper Gradle (`gradlew`) belum
disertakan.

## Cara kerja

- Pemindaian memakai endpoint pencarian toko Steam (diurutkan dari harga termurah), lalu mencari
  titik awal rentang harga dengan binary search agar tidak memindai halaman yang tidak perlu.
- Tanggal berakhir diskon, genre (tag), dan ulasan cadangan diambil dari API toko Steam
  (`IStoreBrowseService/GetItems`) per halaman hasil.
- Kalau terkena batas kecepatan (HTTP 429), aplikasi menunggu lalu mencoba lagi. Jeda antar request
  bisa diatur. Mohon jangan menurunkannya terlalu rendah.

## Privasi

Semua data (daftar like, cover) disimpan lokal di perangkatmu. Aplikasi hanya terhubung ke server
Steam dan tidak mengirim data ke pihak lain.

## Batasan yang diketahui

- **Filter AAA hanyalah perkiraan.** Steam tidak punya penanda AAA resmi. Game dianggap AAA kalau penerbitnya ada di
  daftar `AAA_PUBLISHERS` (EA, Ubisoft, Capcom, dll), atau harga normalnya di atas batas per region
  (`AAA_MIN_PRICE`), ulasannya minimal `AAA_MIN_REVIEWS`, dan tidak bertag Indie. Angka dan daftarnya bisa diubah.
- Urutan rating memakai batas bawah Wilson dari persen positif dan jumlah ulasan. Game tanpa data jumlah
  ulasan diletakkan paling bawah.
- ID tag genre ada di daftar `GENRES` dan sebagian belum diverifikasi; Steam mengubah sistem tag
  pada Mei 2026. Kalau ada genre yang selalu kosong, perbarui ID-nya.
- Data genre hanya mencakup 20 tag teratas tiap game.
- Data game yang di-like tapi tidak muncul di pindai baru bisa sudah kedaluwarsa (harga lama).

## Struktur

```
desktop/   aplikasi desktop (Python), build.bat, tes
android/   proyek Android Studio (Kotlin + Compose)
```

## Lisensi

MIT, lihat [LICENSE](LICENSE). Ganti `NAMA_KAMU` di file LICENSE dengan namamu.
