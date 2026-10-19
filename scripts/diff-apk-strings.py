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

The report is written as UTF-8. On a Chinese Windows console the default code page would turn
every line into mojibake, and a diff nobody can read is not a diff.
Exit 0 always - this is a report, not a gate. Use check-apk-version.py to gate a release.
"""
import re
import sys
import zipfile
from pathlib import Path

# The report is Chinese more often than not, and the console on a Chinese Windows box is a
# code page that cannot hold it. Write UTF-8 explicitly rather than letting the code page
# decide what a diff looks like.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

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
    # The option values have to be taken off the argument list themselves. Filtering out
    # everything that starts with "--" would leave "--min 8" with a stray "8" looking like a
    # third path, and the script would answer with its usage instead of the diff.
    args: list[str] = []
    cjk_only = False
    min_len = 0
    rest = sys.argv[1:]
    index = 0
    while index < len(rest):
        arg = rest[index]
        if arg == "--cjk-only":
            cjk_only = True
        elif arg == "--min":
            index += 1
            if index < len(rest) and rest[index].isdigit():
                min_len = int(rest[index])
            else:
                min_len = 8
        elif arg.startswith("--min="):
            min_len = int(arg.split("=", 1)[1]) if arg.split("=", 1)[1].isdigit() else 8
        elif not arg.startswith("-"):
            args.append(arg)
        index += 1
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
