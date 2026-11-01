@echo off
cd /d "%~dp0"
if not exist steam_diskon_app.py goto nosrc

set PYV=
for %%V in (3.13 3.12 3.14 3.11) do (
  if not defined PYV (
    py -%%V -c "import tkinter" >nul 2>&1
    if not errorlevel 1 set PYV=%%V
  )
)
if not defined PYV goto nopy

echo Memakai Python %PYV%
py -%PYV% --version

py -%PYV% -m pip install --upgrade pip
if errorlevel 1 goto fail
py -%PYV% -m pip install --upgrade requests openpyxl pillow pyinstaller
if errorlevel 1 goto fail
py -%PYV% -m PyInstaller --noconfirm --clean --onefile --windowed --name SteamDiskonFinder steam_diskon_app.py
if errorlevel 1 goto fail

if not exist "dist\SteamDiskonFinder.exe" goto noexe
echo.
echo BERHASIL. Aplikasi portable ada di:
echo %cd%\dist\SteamDiskonFinder.exe
explorer dist
pause
exit /b 0

:nosrc
echo.
echo ERROR: steam_diskon_app.py harus ada di folder yang sama dengan build.bat
pause
exit /b 1

:nopy
echo.
echo ERROR: tidak ketemu Python 3.11 - 3.14 yang punya tkinter lewat perintah py.
echo Ketik py -0 di cmd untuk melihat daftar Python yang terdeteksi.
pause
exit /b 1

:noexe
echo.
echo Build selesai tapi SteamDiskonFinder.exe tidak ditemukan di folder dist.
echo Kemungkinan dihapus antivirus. Cek Windows Security, Protection history.
pause
exit /b 1

:fail
echo.
echo GAGAL. Scroll ke atas dan baca pesan error, cari kata ERROR.
pause
exit /b 1
