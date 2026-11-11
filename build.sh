#!/bin/bash
# Drops Reminder — manual offline APK build.
# Gradle's daemon cannot start in this sandbox, so this script drives the
# toolchain directly: kotlinc -> aapt2 -> d8 -> zipalign -> apksigner.
# Every input is local (SDK, cached jars); no network is used.
set -e
cd "$(dirname "$0")"

SDK="$HOME/workspace/.android-sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
# Persistent JDK (system /usr/lib/jvm is ephemeral and may vanish on VM
# replacement). Also on PATH: the d8 wrapper script execs `java`.
J17="$HOME/workspace/.jdk/bin/java"
export PATH="$HOME/workspace/.jdk/bin:$PATH"
KCP="$(cat .kotlinc-cp.txt)"
STDLIB="/home/hatch/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.20/7388d355f7cceb002cd387ccb7ab3850e4e0a07f/kotlin-stdlib-2.0.20.jar"
SRC="app/src/main"
OUT="out/app"
GEN="$OUT/gen"
CLASSES="$OUT/classes"

echo "=== 1. aapt2 compile (resources) ==="
rm -rf "$OUT"
mkdir -p "$OUT" "$GEN" "$CLASSES"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "=== 2. aapt2 link (manifest + R.java + unsigned apk) ==="
ASSETS_ARGS=()
[ -d "$SRC/assets" ] && ASSETS_ARGS=(-A "$SRC/assets")
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$GEN" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name "1.0" \
  "${ASSETS_ARGS[@]}" \
  "$OUT/res.zip"

echo "=== 3. kotlinc (core + app + R.java) ==="
KT_SOURCES=$(find core/src/main/kotlin "$SRC/kotlin" -name "*.kt" 2>/dev/null)
R_JAVA=$(find "$GEN" -name "*.java")
# shellcheck disable=SC2086
"$J17" -Xmx1g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -cp "$ANDROID_JAR:$STDLIB" \
  -jvm-target 1.8 \
  -d "$CLASSES" $KT_SOURCES $R_JAVA 2>&1 | grep -v "^warning:" || true
NCLASS=$(find "$CLASSES" -name "*.class" | wc -l)
echo "compiled classes: $NCLASS"
[ "$NCLASS" -lt 10 ] && { echo "FATAL: too few classes compiled"; exit 1; }

echo "=== 4. d8 (dex) ==="
mkdir -p "$OUT/dex"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 \
  --output "$OUT/dex" \
  $(find "$CLASSES" -name "*.class") "$STDLIB" 2>&1 | grep -vi "warning" || true
ls -la "$OUT/dex/classes.dex"

echo "=== 5. add classes.dex to apk ==="
cp "$OUT/app-unsigned.apk" "$OUT/app-unaligned.apk"
zip -j -q "$OUT/app-unaligned.apk" "$OUT/dex/classes.dex"
unzip -l "$OUT/app-unaligned.apk" | grep -E "classes.dex|AndroidManifest"

echo "=== 6. zipalign ==="
"$BT/zipalign" -f 4 "$OUT/app-unaligned.apk" "$OUT/app-aligned.apk"

echo "=== 7. apksigner (debug key) ==="
if [ ! -f debug.keystore ]; then
  "$HOME/workspace/.jdk/bin/keytool" -genkeypair -keystore debug.keystore \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/NewMedicine-debug.apk" "$OUT/app-aligned.apk"

echo "=== 8. verify ==="
"$BT/apksigner" verify --print-certs "$OUT/NewMedicine-debug.apk" | head -4
"$BT/aapt2" dump badging "$OUT/NewMedicine-debug.apk" | head -3

cp "$OUT/NewMedicine-debug.apk" ./NewMedicine-debug.apk
ls -la NewMedicine-debug.apk
echo "BUILD OK: $(pwd)/NewMedicine-debug.apk"
