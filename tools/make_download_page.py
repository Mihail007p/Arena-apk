"""Generate a self-contained Arena-download.html (no network, no external links).

Run from anywhere:  python3 tools/make_download_page.py

Reads Arena.apk from the repository root, creates Arena-install.zip (containing
exactly Arena.apk) and writes Arena-download.html with both files embedded as
data URIs, so a browser saves them with the correct filenames.
"""
import base64
import hashlib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "Arena.apk"
ZIP = ROOT / "Arena-install.zip"
OUT = ROOT / "Arena-download.html"
ICON = ROOT / "arena-icon-512.png"

if not APK.is_file():
    raise SystemExit("Arena.apk not found — run ./build.sh first")

apk = APK.read_bytes()
with zipfile.ZipFile(ZIP, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr("Arena.apk", apk)

sha = hashlib.sha256(apk).hexdigest()
apk_b64 = base64.b64encode(apk).decode("ascii")
zip_b64 = base64.b64encode(ZIP.read_bytes()).decode("ascii")
icon_b64 = (base64.b64encode(ICON.read_bytes()).decode("ascii")
            if ICON.is_file() else "")

html = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Скачать Arena.apk</title>
<style>
 :root{color-scheme:dark}body{margin:0;background:#0b0d14;color:#f2f3fa;
 font:16px/1.6 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
 .wrap{max-width:620px;margin:0 auto;padding:32px 20px 60px}
 .card{background:#171b28;border:1px solid #262c3e;border-radius:16px;
 padding:24px;margin:18px 0}h1{font-size:30px;margin:0 0 6px}
 .sub{color:#8b90a6;margin:0}.btn{display:block;text-align:center;
 text-decoration:none;font-weight:600;padding:15px;border-radius:12px;
 margin:10px 0;background:#7c6cff;color:#fff}
 .btn.secondary{background:#232a3d;color:#a99bff}
 code{background:#0b0d14;padding:2px 6px;border-radius:5px;font-size:13px}
 .note{color:#8b90a6;font-size:14px}
</style></head><body><div class="wrap">
 <div class="card"><h1>Arena</h1>
  <p class="sub">Полноэкранный WebView-лаунчер arena.ai · версия 2.0 · __SIZE__ КБ</p>
 </div>
 <div class="card">
  <a class="btn" download="Arena.apk"
     href="data:application/vnd.android.package-archive;base64,__APK_B64__">
     ⬇ Скачать Arena.apk</a>
  <a class="btn secondary" download="Arena-install.zip"
     href="data:application/zip;base64,__ZIP_B64__">
     Скачать ZIP (внутри Arena.apk)</a>
  <p class="note">Файлы вшиты в эту страницу: работает без интернета. Атрибут
   <code>download</code> сохраняет правильное имя. Если встроенный просмотрщик
   блокирует загрузку — откройте страницу в Chrome.</p>
 </div>
 <div class="card"><h2>Проверка</h2>
  <p class="note">SHA-256: <code>__SHA__</code><br>
   Пакет: <code>ai.arena.app</code> · minSdk 23 · targetSdk 33 · подпись v1+v2</p>
 </div>
</div></body></html>
"""

html = (html.replace("__APK_B64__", apk_b64)
            .replace("__ZIP_B64__", zip_b64)
            .replace("__SHA__", sha)
            .replace("__SIZE__", str(round(len(apk) / 1024)))
            .replace("__ICON_B64__", icon_b64))
OUT.write_text(html, encoding="utf-8")
print(f"APK : {APK} ({len(apk)} bytes)")
print(f"ZIP : {ZIP} ({ZIP.stat().st_size} bytes)")
print(f"HTML: {OUT} ({OUT.stat().st_size} bytes)")
print(f"SHA : {sha}")
