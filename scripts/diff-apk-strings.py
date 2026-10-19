#!/usr/bin/env python3
"""Show what changed between two APKs' DEX string pools.

R8-minified bytecode is close to unreadable, but the string pool is not: if the search logic
differs between two builds, the literals it prints or matches on differ too. This is the fastest
way to answer "did the build I pushed actually contain the fix?" without decompiling anything.

It answers the question that bit us before: a build whose manifest said one version while its dex
still carried the previous release's strings. Comparing the two string pools shows exactly which
literals appeared and which disappeared, so a change that should have happened and did not is
visible as a one-line diff instead of a mystery.

Usage: python diff-apk-strings.py <old.apk> <new.apk> [--cjk-only] [--min N]
       python diff-apk-strings.py <old-dir> <new-dir>   (directories of classes*.dex)
Exit 0 always - this is a report, not a gate. Use check-apk-version.py to gate a release.
"""
import re
import sys
import zipfile
from pathlib import Path

# A run of CJK text is user-facing copy; a run of ASCII identifiers is code.
CJK = re.compile(r"[一-鿿　-〿＀-￯][一-鿿　-〿＀-￯ -~]{3,120}")
ASCII = re.compile(r"[A-Za-z_][A-Za-z0-9_./:<>\- ]{8,120}")


def dex_blobs(source: str) -> list[bytes]:
    """The DEX files of an APK, or of a directory that holds them."""
    path = Path(source)
    if path.is_dir():
        return [p.read_bytes() for p in sorted(path.glob("classes*.dex"))]
    with zipfile.ZipFile(source) as archive:
        return [archive.read(name) for name in archive.namelist() if name.endswith(".dex")]


def strings_of(blobs: list[bytes], cjk_only: bool) -> set[str]:
    blob = b"\n".join(blobs)
    # DEX string data is MUTF-8; decoding as utf-8 with replacement keeps the printable runs
    # and drops the bytes that are not text, which is all this report needs.
    text = blob.decode("utf-8", "replace")
    found = set(CJK.findall(text))
    if not cjk_only:
        found |= set(ASCII.findall(text))
    return {s.strip() for s in found if s.strip()}


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    cjk_only = "--cjk-only" in sys.argv
    min_len = 0
    for arg in sys.argv[1:]:
        if arg.startswith("--min"):
            min_len = int(arg.split("=", 1)[1]) if "=" in arg else 8
    if len(args) != 2:
        print(__doc__)
        return 2

    old, new = args
    a = strings_of(dex_blobs(old), cjk_only)
    b = strings_of(dex_blobs(new), cjk_only)
    only_old = sorted(s for s in a - b if len(s) >= min_len)
    only_new = sorted(s for s in b - a if len(s) >= min_len)

    print(f"{old}: {len(a)} strings")
    print(f"{new}: {len(b)} strings")
    print(f"\nonly in OLD ({len(only_old)}):")
    for s in only_old:
        print("  - " + s)
    print(f"\nonly in NEW ({len(only_new)}):")
    for s in only_new:
        print("  + " + s)
    return 0


if __name__ == "__main__":
    sys.exit(main())
