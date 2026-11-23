#!/usr/bin/env bash
# Builds everything with the plain Android SDK tools (no Gradle / Android Studio).
#
#   ./build.sh            # app + helper + tools
#   ./build.sh app        # phone app       -> dist/n1-remote.apk
#   ./build.sh helper     # box helper      -> dist/n1-standby-helper.apk
#   ./build.sh tools      # box test tools  -> dist/n1-tools.dex
#
# Needs a JDK and the SDK packages "platforms;android-35" and "build-tools;35.0.0".
# Signing key: $KEYSTORE (default n1remote.keystore), created on first run if missing.
# Keep the same keystore for later builds, or Android refuses to install the update.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"
KEYSTORE="${KEYSTORE:-n1remote.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-n1remote}"

[ -f "$JAR" ] || { echo "Android SDK not found at $SDK (set ANDROID_HOME)" >&2; exit 1; }
mkdir -p dist

keystore() {
    [ -f "$KEYSTORE" ] && return
    echo "creating signing key $KEYSTORE"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=N1 Remote" >/dev/null 2>&1
}

compile() { # compile <out> <minSdk> <src dirs...>
    local out=$1 min=$2; shift 2
    mkdir -p "$out/classes" "$out/dex"
    javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
        -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -classpath "$JAR" -d "$out/classes" \
        $(find "$@" -name '*.java') 2>&1 | grep -v -E '^Note: ' || true
    "$BT/d8" --release --min-api "$min" --lib "$JAR" --output "$out/dex" \
        $(find "$out/classes" -name '*.class')
}

apk() { # apk <name> <dir> <minSdk> <targetSdk> <versionCode> <versionName> <output.apk>
    local name=$1 dir=$2 min=$3 target=$4 code=$5 ver=$6 dest=$7 out="build/$1"
    rm -rf "$out"; mkdir -p "$out/gen"
    "$BT/aapt2" compile --dir "$dir/res" -o "$out/res.zip"
    "$BT/aapt2" link -o "$out/base.apk" -I "$JAR" --manifest "$dir/AndroidManifest.xml" \
        --java "$out/gen" --min-sdk-version "$min" --target-sdk-version "$target" \
        --version-code "$code" --version-name "$ver" "$out/res.zip"
    compile "$out" "$min" "$dir/src" "$out/gen"
    cp "$out/base.apk" "$out/unaligned.apk"
    (cd "$out/dex" && zip -q ../unaligned.apk classes.dex)
    "$BT/zipalign" -f -p 4 "$out/unaligned.apk" "$out/aligned.apk"
    keystore
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
        --ks-key-alias "$KS_ALIAS" --out "$dest" "$out/aligned.apk" 2>/dev/null
    "$BT/apksigner" verify "$dest" 2>/dev/null
    rm -f "$dest.idsig"
    echo "built $dest"
}

tools() {
    local out=build/tools
    rm -rf "$out"
    compile "$out" 24 tools
    cp "$out/dex/classes.dex" dist/n1-tools.dex
    echo "built dist/n1-tools.dex"
}

case "${1:-all}" in
    app)    apk app app 28 34 11 2.6.0 dist/n1-remote.apk ;;
    helper) apk helper box-helper 24 25 5 1.4 dist/n1-standby-helper.apk ;;
    tools)  tools ;;
    all)    apk app app 28 34 11 2.6.0 dist/n1-remote.apk
            apk helper box-helper 24 25 5 1.4 dist/n1-standby-helper.apk
            tools ;;
    *)      echo "usage: $0 [app|helper|tools|all]" >&2; exit 2 ;;
esac
