#!/usr/bin/env python3
import requests
import sys

apk_path = r"D:\document\project\hongguoTV\kotlin-tv\app\build\outputs\apk\release\app-release.apk"
# Try with query parameter
url = "http://192.168.1.26:10000/api/upload?filename=app-release.apk"

with open(apk_path, "rb") as f:
    files = {"file": ("app-release.apk", f, "application/octet-stream")}
    r = requests.post(url, files=files)
    print(r.text)
    sys.exit(0 if r.ok else 1)
