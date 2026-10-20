#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in "$HOME"/.cache/hongguotv/toolchain/jdk-17*/Contents/Home; do
    [[ -x "$candidate/bin/java" ]] && export JAVA_HOME="$candidate" && break
  done
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.cache/hongguotv/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.cache/hongguotv/android-sdk"
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
if [[ ! -f android/app/debug.keystore ]]; then
  "${JAVA_HOME:+$JAVA_HOME/bin/}keytool" -genkeypair -noprompt -storetype JKS \
    -keystore android/app/debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname 'CN=Android Debug,O=Android,C=US'
fi
# Sideloadable release bundle signed with the development key; no Metro required.
# Use a private release key for public distribution.
(cd android && ./gradlew assembleRelease -PreactNativeArchitectures=armeabi-v7a,arm64-v8a --console=plain)
apk_src="android/app/build/outputs/apk/release/app-release.apk"

# The delivered file name has to carry the version the APK really has. package.json is a
# different source from android/app/build.gradle, where the APK's versionName/versionCode
# come from, and the two only agree by accident - a name built from the wrong one lies.
# So read the version back from the built APK with aapt, the same way the release flow
# reads it (scripts/publish-tv-update.py).
aapt_bin=""
aapt_major=-1
for candidate in "$ANDROID_HOME"/build-tools/*/aapt; do
  [[ -x "$candidate" ]] || continue
  candidate_major="$(basename "$(dirname "$candidate")")"
  candidate_major="${candidate_major%%.*}"
  [[ "$candidate_major" =~ ^[0-9]+$ ]] || continue
  if (( candidate_major > aapt_major )); then
    aapt_major="$candidate_major"
    aapt_bin="$candidate"
  fi
done
if [[ -z "$aapt_bin" ]]; then
  echo "::error::no aapt found in $ANDROID_HOME/build-tools; cannot read the built APK's version" >&2
  exit 1
fi
badging="$("$aapt_bin" dump badging "$apk_src" || true)"
package_line="$(printf '%s\n' "$badging" | grep -m1 '^package: ' || true)"
version="$(printf '%s\n' "$package_line" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
version_code="$(printf '%s\n' "$package_line" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
if [[ -z "$version" || -z "$version_code" ]]; then
  echo "::error::could not read versionName/versionCode out of $apk_src" >&2
  exit 1
fi
echo "APK version: $version (versionCode $version_code)"

# package.json is still worth a look: when the two disagree, one of them was edited without
# the other, and the APK that was just built is the one that is right.
if command -v node >/dev/null; then
  package_version="$(node -p "require('./package.json').version" 2>/dev/null || true)"
  if [[ -n "$package_version" && "$package_version" != "$version" ]]; then
    echo "::warning::package.json says $package_version, the APK says $version; the APK wins" >&2
  fi
fi

mkdir -p outputs
apk_out="outputs/hongguotv-${version}-android8.apk"
cp "$apk_src" "$apk_out"
if command -v shasum >/dev/null; then shasum -a 256 "$apk_out" > outputs/SHA256SUMS; else sha256sum "$apk_out" > outputs/SHA256SUMS; fi
