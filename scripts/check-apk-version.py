#!/usr/bin/env python3
"""Verify a built APK really carries the requested version.

Kotlin inlines BuildConfig.VERSION_NAME/VERSION_CODE into bytecode as compile-time
constants. An incremental build can regenerate BuildConfig.java while leaving
compileReleaseKotlin UP-TO-DATE, so the manifest ends up correct while classes.dex
still holds the previous version. aapt dump badging only reads the manifest and
cannot see the split, so this script checks the dex directly.

The trap this guards against is real, but the first version of this check was
wrong in a way that made it useless: it looked for the versionCode as a 4-byte
little-endian int, which is only the encoding dex uses for a full `const`
instruction. Any code that fits a signed 16-bit int is emitted as the narrower
`const/16` instead, so every versionCode below 32768 - which is all of them,
since this project's codes are major*1000000 + minor*1000 + patch - was reported
as missing even when the build was perfect. It now accepts every width dex uses.

Searching raw bytes is a heuristic: a 2-byte sequence can occur by chance in a
multi-megabyte dex. So the check also takes the *previous* version name and
asserts it is gone, which is the actual failure signature - a stale build
carries the old string even when the manifest is right.

Asking the caller for the previous version name is a trap of its own: the
caller has to remember which build was on the device, and a wrong guess makes
the check pass on a stale dex. Measured 2026-10-25: a build that still carried
0.9.60 passed the check because the caller said the previous version was
0.9.61. So the check also looks for *every* version of our own major in the dex
and fails when more than one is there. The manifest cannot catch this - only the
dex knows what the Kotlin classes really inlined.

Usage: python check-apk-version.py <apk> <versionName> <versionCode> [previousVersionName]
Exit 0 when the dex carries the version, 1 when it does not, 2 when the arguments are wrong.
"""
import re
import struct
import sys
import zipfile
from pathlib import Path


def _dex_blobs(apk: str):
    with zipfile.ZipFile(apk) as archive:
        return {name: archive.read(name) for name in archive.namelist() if name.endswith(".dex")}


def dex_carries_string(blobs, needle: str) -> bool:
    """VERSION_NAME is a String constant, so it appears verbatim in the dex."""
    return any(needle.encode() in blob for blob in blobs.values())


def dex_versions_of_our_major(blobs, major: int) -> set:
    """Every x.y.z in the dex that belongs to our own major version.

    A stale build keeps the old version name inlined in the Kotlin classes, so
    the dex holds two of them. Only our major is considered: the libraries in
    this APK carry their own version numbers ("4.12.0", "1.8.0"), and those are
    not evidence of anything.
    """
    found = set()
    for blob in blobs.values():
        for match in re.findall(rb"\d{1,3}\.\d{1,3}\.\d{1,3}", blob):
            text = match.decode("ascii")
            major_text, minor_text, patch_text = text.split(".")
            if int(major_text) != major:
                continue
            if int(minor_text) > 999 or int(patch_text) > 999:
                continue
            found.add(text)
    return found


def dex_carries_int(blobs, value: int) -> bool:
    """VERSION_CODE is an int constant compiled into bytecode, never as text.

    dex picks the narrowest instruction that holds the value - `const/4` for a
    nibble, `const/16` up to a signed 16-bit int, `const` above that - all
    little-endian. A versionCode of 9018 is 0x233A, so it is stored as the two
    bytes 3a 23; checking only the four-byte form misses every code this project
    will ever produce.
    """
    widths = []
    if -8 <= value <= 7:
        widths.append(struct.pack("<b", value))
    if -32768 <= value <= 32767:
        widths.append(struct.pack("<h", value))
    if -2 ** 31 <= value <= 2 ** 31 - 1:
        widths.append(struct.pack("<i", value))
    return any(packed in blob for packed in widths for blob in blobs.values())


def main() -> int:
    if len(sys.argv) not in (4, 5):
        print(__doc__)
        return 2
    apk, version_name, version_code_text = sys.argv[1], sys.argv[2], sys.argv[3]
    previous = sys.argv[4] if len(sys.argv) == 5 else None
    # This is the last gate before a release, so a wrong version code has to produce a
    # verdict, not a traceback. The code has to be a plain non-negative integer: "0.1.1" in
    # the versionCode slot means the name and the code were put in the wrong order.
    # isdecimal() and not isdigit(): the latter accepts "²", which int() then refuses.
    if not version_code_text.isdecimal():
        print(f"FAIL versionCode must be a non-negative integer, got {version_code_text!r}")
        print("\nThe arguments are <apk> <versionName> <versionCode> [previousVersionName].")
        print("A version name in the versionCode slot means the two were swapped.")
        return 2
    version_code = int(version_code_text)
    if not Path(apk).is_file():
        print(f"FAIL APK not found: {apk}")
        return 2
    blobs = _dex_blobs(apk)
    if not blobs:
        print("FAIL no classes.dex in the APK")
        return 1

    if dex_carries_string(blobs, version_name):
        print(f"OK   dex carries versionName={version_name}")
    else:
        print(f"FAIL dex does NOT carry versionName={version_name}")
        print("\nThe dex was not rebuilt after the version changed.")
        print("Run `gradlew :app:clean` and rebuild. Do NOT ship this APK.")
        return 1

    if dex_carries_int(blobs, version_code):
        print(f"OK   dex carries versionCode={version_code}")
    else:
        print(f"FAIL dex does NOT carry versionCode={version_code}")
        print("\nThe manifest is probably fine but the dex holds a stale constant.")
        print("This is the Kotlin BuildConfig inlining trap: run `gradlew :app:clean`")
        print("and rebuild. Do NOT ship this APK.")
        return 1

    if previous:
        if dex_carries_string(blobs, previous):
            print(f"FAIL dex still carries the old versionName={previous}")
            print("\nThe manifest and the dex disagree - this is the stale build, not a probe problem.")
            return 1
        print(f"OK   dex no longer carries the old versionName={previous}")

    # The check above only knows about the one version the caller remembered. The dex
    # knows all of them: a stale build inlines the old name next to the new one, so
    # more than one version of our major in the dex means the classes were not rebuilt.
    parts = version_name.split(".")
    if len(parts) == 3 and all(part.isdecimal() for part in parts):
        ours = dex_versions_of_our_major(blobs, int(parts[0]))
        others = sorted(ours - {version_name})
        if others:
            print(f"FAIL dex also carries {', '.join(others)} next to {version_name}")
            print("\nThe Kotlin classes still hold an older version name: this is the stale")
            print("build, whatever the caller thought the previous version was.")
            print("Run `gradlew :app:clean` and rebuild. Do NOT ship this APK.")
            return 1
        print(f"OK   dex carries no other {parts[0]}.x.y version")

    print("\nBoth version constants are present in classes.dex.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
