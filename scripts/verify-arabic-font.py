# -*- coding: utf-8 -*-
"""验证下载到的 Noto Naskh Arabic 字体：字重、变量轴、字符覆盖。"""
import os, json
from fontTools.ttLib import TTFont

OUT = r"E:\dshcode\FAZE\design\ui\fonts"

for name in ["noto-naskh-arabic-400.woff2", "noto-naskh-arabic-700.woff2"]:
    path = os.path.join(OUT, name)
    f = TTFont(path)
    info = {
        "file": name,
        "family": f["name"].getDebugName(1),
        "subfamily": f["name"].getDebugName(2),
        "fullName": f["name"].getDebugName(4),
        "usWeightClass": f["OS/2"].usWeightClass,
        "isVariable": "fvar" in f,
        "tables": sorted(f.keys()),
    }
    if "fvar" in f:
        info["variableAxes"] = [
            {"tag": a.axisTag, "min": a.minValue, "default": a.defaultValue, "max": a.maxValue}
            for a in f["fvar"].axes
        ]
    cmap = set()
    for t in f["cmap"].tables:
        cmap.update(t.cmap.keys())
    info["codepoints"] = len(cmap)
    f.close()
    print(json.dumps(info, ensure_ascii=False, indent=2))

# ---------- 字符覆盖 ----------
uyghur = "ئاەبپتجچخدرزژسشغفقكگڭلمنھوۇۆۈۋېىي"
kazakh_extra = "ٴٵٶٷٸ"
need = sorted(set(uyghur + kazakh_extra))

f = TTFont(os.path.join(OUT, "noto-naskh-arabic-400.woff2"))
cmap = set()
for t in f["cmap"].tables:
    cmap.update(t.cmap.keys())
miss = [c for c in need if ord(c) not in cmap]
print("\nCOVERAGE")
print("  required chars:", len(need))
print("  missing chars :", len(miss))
if miss:
    for c in miss:
        print("    U+%04X %s (%s)" % (ord(c), c, __import__("unicodedata").name(c, "?")))
else:
    print("  -> 全部覆盖，维吾尔文与哈萨克文所需字符均在字体内")

# 关键：阿拉伯文的连写需要 GSUB 表
print("\nSHAPING")
for tag in ["GSUB", "GPOS", "GDEF", "cmap", "locl", "init", "medi", "fina", "isol"]:
    if tag in f:
        print("  %s: present" % tag)
    elif tag in ("init", "medi", "fina", "isol"):
        # 这些是 GSUB 里的 feature，检查是否被引用
        pass
gsub = f["GSUB"].table
feats = sorted({fr.FeatureTag for fr in gsub.FeatureList.FeatureRecord})
print("  GSUB features:", ", ".join(feats))
f.close()
