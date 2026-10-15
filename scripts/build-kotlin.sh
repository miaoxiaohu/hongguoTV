#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
  for native_jdk in "$HOME"/.cache/hongguotv/toolchain/jdk-17*/Contents/Home; do
    if [[ -x "$native_jdk/bin/java" ]]; then export JAVA_HOME="$native_jdk"; break; fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.cache/hongguotv/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.cache/hongguotv/android-sdk"
fi
# gradle.properties asks for -Xmx3g, which is fine on a roomy machine but gets the daemon killed
# on a 10 GB box with a small pagefile - especially when another Gradle build is running at the
# same time. Cap the heap on the command line (it overrides gradle.properties), compile Kotlin
# inside the same JVM so there is no second process competing for memory, and keep workers low.
# Override with HONGGUOTV_BUILD_XMX / HONGGUOTV_BUILD_WORKERS on a bigger machine.
build_xmx="${HONGGUOTV_BUILD_XMX:-2600m}"
build_workers="${HONGGUOTV_BUILD_WORKERS:-1}"
"$repo_dir/kotlin-tv/gradlew" -p "$repo_dir/kotlin-tv" \
    "-Dorg.gradle.jvmargs=-Xmx${build_xmx} -Dfile.encoding=UTF-8" \
    -Pkotlin.compiler.execution.strategy=in-process \
    "--max-workers=${build_workers}" \
    :core:test :app:lintRelease :app:assembleRelease --console=plain
# Same directory as the rest of the delivered APKs (out/, not outputs/).
mkdir -p "$repo_dir/out"
native_version="${HONGGUOTV_VERSION_NAME:-$(sed -n 's/.*versionName = .*?: "\([^"]*\)".*/\1/p' "$repo_dir/kotlin-tv/app/build.gradle.kts")}"
[[ "$native_version" =~ ^[0-9][0-9A-Za-z.+-]*$ ]] || { echo "Invalid APK version" >&2; exit 1; }
native_apk="$repo_dir/out/hongguotv-kotlin-$native_version-android8.apk"
cp "$repo_dir/kotlin-tv/app/build/outputs/apk/release/app-release.apk" "$native_apk"
if command -v shasum >/dev/null; then shasum -a 256 "$native_apk"; else sha256sum "$native_apk"; fi
