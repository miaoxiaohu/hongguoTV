#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""What the site says about the pages the home tab walks.

The app's home (热门) tab is page N of the site's category listing. The page count there is the
site's business and the app cannot work it out for itself, so it is carried along from core
(see ContentRepository.routePage / home). This reads it straight off the site, which is where
the numbers in the code comments came from:

    python tools/probe_home_pages.py           # the first two pages of every route
    python tools/probe_home_pages.py --pages 34

Measured 2026-10-20: every route answers 24 rows a page, 800 rows in total, 34 pages.
"""
import argparse
import json
import urllib.request

SITE = "https://hongguoduanju.com"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)
ROUTES = ("real-drama", "comic-drama", "ai-drama")


def fetch(url):
    request = urllib.request.Request(
        url, headers={"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8", "replace")


def router_data(html):
    """The site's serialized router data, read without executing any of its scripts."""
    marker = html.find("_ROUTER_DATA")
    if marker < 0:
        raise ValueError("no _ROUTER_DATA on the page")
    first = html.find("{", marker)
    depth = 0
    quoted = False
    escaped = False
    for index in range(first, len(html)):
        char = html[index]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted = True
        elif char in "{[":
            depth += 1
        elif char in "}]":
            depth -= 1
            if depth == 0:
                return json.loads(html[first:index + 1])
    raise ValueError("unterminated _ROUTER_DATA")


def category_section(html):
    """The category block. 'category_layout' is a null placeholder; the other one is the real one."""
    loader = router_data(html)["loaderData"]
    for key, value in loader.items():
        if key.startswith("category") and isinstance(value, dict):
            return key, value
    raise ValueError(f"no category block on the page, loader keys={list(loader)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pages", type=int, default=2, help="which page numbers to ask for")
    args = parser.parse_args()

    for route in ROUTES:
        for page in range(1, args.pages + 1):
            url = f"{SITE}/category/{route}" + (f"?page={page}" if page > 1 else "")
            try:
                html = fetch(url)
                key, section = category_section(html)
            except Exception as failure:  # noqa: BLE001 - a probe reports, it does not raise
                print(f"{route} page={page}: FAILED ({failure})")
                continue
            rows = section.get("recommendList") or (
                section.get("categoryData") or {}
            ).get("recommendList") or []
            pagination = section.get("pagination") or {}
            print(
                f"{route} page={page}: key={key} rows={len(rows)} "
                f"pagination={json.dumps(pagination, ensure_ascii=False)}"
            )
        print("-" * 60)


if __name__ == "__main__":
    main()
