#!/usr/bin/env python3
"""Verify a built APK really carries the requested version.

Kotlin inlines BuildConfig.VERSION_NAME/VERSION_CODE into bytecode as compile-time
constants. An incremental build can regenerate BuildConfig.java while leaving
compileReleaseKotlin UP-TO-DATE, so the manifest ends up correct while classes.dex
still holds the previous version. aapt dump badging only reads the manifest and
cannot see the split, so this script checks the dex directly.

Usage: python check-apk-version.py <apk> <versionName> <versionCode>
Exit 0 when the dex carries the version, 1 when it does not.
"""
import struct
import sys
import zipfile


def dex_carries_string(apk: str, needle: str) -> bool:
    """VERSION_NAME is a String constant, so it appears verbatim in the dex."""
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.endswith(".dex") and needle.encode() in archive.read(name):
                return True
    return False


def dex_carries_int(apk: str, value: int) -> bool:
    """VERSION_CODE is an int constant: it is compiled into bytecode as raw
    little-endian bytes, not as text. Searching for the decimal string always
    misses, which would look exactly like the stale-constant failure we are
    trying to detect. Decode it the way dex does instead."""
    packed = struct.pack("<i", value)
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.endswith(".dex") and packed in archive.read(name):
                return True
    return False


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    apk, version_name, version_code = sys.argv[1], sys.argv[2], int(sys.argv[3])

    if dex_carries_string(apk, version_name):
        print(f"OK   dex carries versionName={version_name}")
    else:
        print(f"FAIL dex does NOT carry versionName={version_name}")
        return 1

    if dex_carries_int(apk, version_code):
        print(f"OK   dex carries versionCode={version_code}")
    else:
        print(f"FAIL dex does NOT carry versionCode={version_code}")
        print("\nThe manifest is probably fine but the dex holds a stale constant.")
        print("This is the Kotlin BuildConfig inlining trap: run `gradlew :app:clean`")
        print("and rebuild. Do NOT ship this APK.")
        return 1

    print("\nBoth version constants are present in classes.dex.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
