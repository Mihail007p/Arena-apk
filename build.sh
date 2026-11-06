#!/usr/bin/env bash
# Builds a signed, installable Arena.apk without Gradle / Android Studio.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# SDK cache lives OUTSIDE /home/user on purpose: workspace snapshots are capped
# at 128 MB / 10,000 files, and the Android SDK is ~386 MB / ~11,000 files.
# Override with ARENA_SDK=/path/to/sdk if you already have build-tools r33.0.2
# and platform android-33-ext5 installed.
SDK="${ARENA_SDK:-/tmp/arena-sdk}"
BT="$SDK/bt/android-13"
AJAR="$SDK/platform/android-33-ext5/android.jar"
KEYTOOL="${ARENA_KEYTOOL:-$(command -v keytool || echo keytool)}"
# Signing credentials: override via environment for real releases.
# NEVER commit arena-release.jks to a public repository (see .gitignore).
STORE_PASS="${ARENA_STORE_PASS:-arena2026}"
KEY_PASS="${ARENA_KEY_PASS:-arena2026}"
KEY_ALIAS="${ARENA_KEY_ALIAS:-arena}"
OUT="$HERE/build"
APK="$HERE/Arena.apk"
BT_URL="https://dl.google.com/android/repository/build-tools_r33.0.2-linux.zip"
PLATFORM_URL="https://dl.google.com/android/repository/platform-33-ext5_r01.zip"

# The sandbox may drop hidden tool caches between sessions — restore them on demand.
ensure_sdk() {
  if [ ! -x "$BT/aapt2" ]; then
    echo "[sdk] downloading Android build-tools r33.0.2 ..."
    mkdir -p "$SDK"
    curl -sSL -o "$SDK/bt.zip" "$BT_URL"
    unzip -q -o "$SDK/bt.zip" -d "$SDK/bt"
  fi
  if [ ! -f "$AJAR" ]; then
    echo "[sdk] downloading Android platform 33 ..."
    mkdir -p "$SDK"
    curl -sSL -o "$SDK/platform.zip" "$PLATFORM_URL"
    unzip -q -o "$SDK/platform.zip" -d "$SDK/platform"
  fi
}

ensure_sdk

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex" "$OUT/apk"

echo "[1/8] Generating launcher icons"
python3 "$HERE/make_icons.py"

echo "[2/8] Compiling resources (aapt2 compile)"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/compiled/res.zip"

echo "[3/8] Linking APK + binary manifest (aapt2 link)"
"$BT/aapt2" link \
  -o "$OUT/apk/base.apk" \
  -I "$AJAR" \
  --manifest "$HERE/AndroidManifest.xml" \
  -R "$OUT/compiled/res.zip" \
  --auto-add-overlay \
  --min-sdk-version 23 \
  --target-sdk-version 33 \
  --version-code 12 \
  --version-name 2.1 \
  --java "$OUT/gen"

echo "[3b/8] Validating injected JavaScript (node --check)"
python3 "$HERE/check_js.py"

echo "[4/8] Compiling Java sources"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJAR" \
      -nowarn -d "$OUT/classes" \
      "$HERE/src/ai/arena/app/MainActivity.java"

echo "[5/8] Dexing (d8)"
"$BT/d8" --lib "$AJAR" --min-api 23 --output "$OUT/dex" \
      $(find "$OUT/classes" -name '*.class' | sort)

echo "[6/8] Packaging classes.dex, aligning"
cp "$OUT/apk/base.apk" "$OUT/apk/unsigned.apk"
(cd "$OUT/dex" && zip -q -9 "$OUT/apk/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

echo "[7/8] Creating signing key and signing APK"
if [ ! -f "$HERE/arena-release.jks" ]; then
  "$KEYTOOL" -genkeypair -v \
    -keystore "$HERE/arena-release.jks" \
    -storetype PKCS12 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Arena Web Wrapper, OU=Mobile, O=Arena App, L=Kemerovo, ST=Kuzbass, C=RU" \
    >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$HERE/arena-release.jks" \
  --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled false \
  --out "$APK" \
  "$OUT/apk/aligned.apk"

echo "[8/8] Verifying signature"
"$BT/apksigner" verify --verbose --print-certs "$APK"

echo
echo "=== RESULT ==="
ls -lh "$APK"
sha256sum "$APK" | tee "$APK.sha256.txt"
