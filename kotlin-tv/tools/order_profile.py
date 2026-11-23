"""把仓库根的 playback-profile.json 排成固定键序。

为什么要它：档案里的 medusa 字段表是**顺序敏感**的（protobuf 按写入顺序编码），
所以键序乱了会让人工 review 看不出真正改动。把键序钉死，diff 里只剩真正变了的数据。

用法：

    python kotlin-tv/tools/order_profile.py

键序表在下面 ORDER 里；改结构了先改这里。
"""
import collections
import io
import json
import os

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PATH = os.path.join(REPO, "playback-profile.json")

ORDER = {
    "root": ["schema", "revision", "updatedAt", "note", "v5"],
    "v5": ["magic", "license", "signKey", "sbox", "headers", "deviceLevel", "medusa", "gates", "deviceProfile", "tabQuery", "modelBody"],
    "medusa": ["flags", "device", "env", "envSub13", "nested", "message"],
    "gates": ["model", "tab"],
    "model": ["host", "path", "userAgent"],
    "tab": ["hosts", "path", "userAgent"],
    "spec": ["fields"],
    "field": ["key", "value", "when"],
    "proto": ["tag", "type", "value"],
}


def ordered(obj, kind):
    if not isinstance(obj, dict):
        return obj
    out = collections.OrderedDict()
    for k in ORDER[kind]:
        if k in obj:
            out[k] = obj[k]
    for k in obj:
        if k not in out:
            out[k] = obj[k]
    return out


def fix(obj, kind):
    if kind == "root":
        o = ordered(obj, "root")
        o["v5"] = fix(o["v5"], "v5")
        return o
    if kind == "v5":
        o = ordered(obj, "v5")
        o["medusa"] = fix(o["medusa"], "medusa")
        o["gates"] = fix(o["gates"], "gates")
        o["deviceProfile"] = fix(o["deviceProfile"], "spec")
        o["tabQuery"] = fix(o["tabQuery"], "spec")
        return o
    if kind == "medusa":
        o = ordered(obj, "medusa")
        for k in ["device", "env", "envSub13", "nested", "message"]:
            o[k] = [fix(f, "proto") for f in o[k]]
        return o
    if kind == "gates":
        o = ordered(obj, "gates")
        o["model"] = ordered(o["model"], "model")
        o["tab"] = ordered(o["tab"], "tab")
        return o
    if kind == "spec":
        o = ordered(obj, "spec")
        o["fields"] = [ordered(f, "field") for f in o["fields"]]
        return o
    return obj


def main():
    with io.open(PATH, "r", encoding="utf-8") as f:
        data = json.load(f)
    data = fix(data, "root")
    with io.open(PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2))
        f.write("\n")
    print("已排键顺序：" + PATH)


if __name__ == "__main__":
    main()
