# Steam Diskon Finder

**English** | [Bahasa Indonesia](README.md)

A scanner for discounted Steam games with per-region price and discount filters, genre
filters, a persistent like list, and Excel export. Available for **desktop**
(Python + Tkinter, can be built into a portable `.exe`) and **Android**
(Kotlin + Jetpack Compose).

> **Unofficial.** This project is not affiliated with Valve or Steam. "Steam" is a trademark
> of Valve Corporation.

## Status

Experimental. The core logic (scanning, filtering, likes, Excel export) is tested against fake
data, but it has **not been tried against the live Steam servers**, and the **Android version has
never been compiled**. The app relies on unofficial Steam store endpoints and may stop working if
Steam changes its format. Please report problems through Issues.

## Features

- Scan discounted games per region (Indonesia / US / Japan), prices in the region's currency
- Price range, discount tier checklist (1-24%, 25-49%, 50-74%, 75-100%), unlimited result count
- Covers, star rating, colored review sentiment (color bar on desktop, colored text in Excel),
  discount end date
- **Result filters** without rescanning: name search, genre checklist (FPS, RPG, MMO, Horror,
  Visual Novel, etc.; match "any" or "all"); clear them to return to the original results
- **Likes**: save games to a local database and watch them later via "Only liked" after the app
  restarts. Liked data is refreshed automatically when a game shows up in a new scan
- **AAA filter** (an estimate, see Known limitations) and a **rating sort that accounts for review counts**:
  a game with 100% from 2 reviews no longer beats one with 92% from thousands of reviews
- Sortable results, Excel (`.xlsx`) export of the current view, optional covers
- Android: left sidebar (swipe right) for settings; game pages open in an in-app WebView or the
  browser (your choice)

## Desktop

Requires Python 3.11 or newer (Tkinter ships with the Windows installer; on Linux:
`sudo apt install python3-tk`).

```bash
cd desktop
python -m pip install -r requirements.txt
python steam_diskon_app.py
```

### Building a portable `.exe` (Windows)

Run `desktop/build.bat`. It picks an installed Python 3.11-3.14 through the `py` launcher and
produces `desktop/dist/SteamDiskonFinder.exe`. Avoid Python 3.10.0: a bug there breaks PyInstaller
(fixed in 3.10.1).

The like database (`steam_diskon_likes.db`) lives next to the program/exe, so it travels with a
portable copy. If that folder is not writable, the file falls back to the home folder.

### Tests

```bash
python -m unittest discover -s desktop/tests -v
```

Tests use fake data and a mocked Tkinter, so they need neither internet nor a display.

## Android

See [`android/README.md`](android/README.md) (Indonesian). In short: open the `android/` folder in
Android Studio, wait for Gradle sync, then Run or Build > Build APK(s). The Gradle wrapper
(`gradlew`) is not included yet.

## How it works

- Scanning uses the Steam store search endpoint (sorted by lowest price), then finds the start of
  the price range with a binary search to avoid fetching unnecessary pages.
- Discount end dates, genres (tags), and fallback review data come from the Steam store API
  (`IStoreBrowseService/GetItems`), one request per page of results.
- On rate limiting (HTTP 429) the app waits and retries. The delay between requests is
  configurable; please do not set it too low.

## Privacy

All data (likes, covers) is stored locally on your device. The app only talks to Steam servers and
sends nothing to anyone else.

## Known limitations

- **The AAA filter is only an estimate.** Steam has no official AAA marker. A game counts as AAA if its publisher is in
  the `AAA_PUBLISHERS` list (EA, Ubisoft, Capcom, etc.), or its regular price is above a per-region threshold
  (`AAA_MIN_PRICE`), it has at least `AAA_MIN_REVIEWS` reviews, and it is not tagged Indie. Numbers and list are editable.
- The rating sort uses the Wilson lower bound of the positive percentage and the review count. Games without a
  review count go to the bottom.
- Genre tag IDs live in the `GENRES` list and some are unverified; Steam changed its tag system in
  May 2026. If a genre always comes back empty, update its ID.
- Genre data covers only each game's top 20 tags.
- Liked games that do not appear in a new scan may show stale prices.

## Layout

```
desktop/   desktop app (Python), build.bat, tests
android/   Android Studio project (Kotlin + Compose)
```

## License

MIT, see [LICENSE](LICENSE). Replace `NAMA_KAMU` in the LICENSE file with your name.
