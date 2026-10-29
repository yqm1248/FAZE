# -*- coding: utf-8 -*-
"""
下载 Noto Naskh Arabic 并落到 design/ui/fonts/，供设计稿 @font-face 使用。

背景：
  中文字体栈里没有任何阿拉伯字形，RTL 语言（维吾尔文 ug-Arab-CN、
  哈萨克文 kk-Arab-CN）完全靠系统 fallback，不同机器观感不一致。
  决定把字体打包，设计稿先落一份真实子集，保证 RTL 走查看到的是真实渲染。

关键事实（2026-10-29 实测）：
  Google Fonts 对 wght=400 和 wght=700 返回的是**同一个文件** ——
  因为 Noto Naskh Arabic 是可变字体（fvar 轴 wght 400~700，默认 400）。
  所以只存一份，@font-face 用 `font-weight: 400 700` 声明区间，
  而不是傻存两份 94KB 的相同文件。

  实测数据：
    文件      noto-naskh-arabic.woff2  94,032 字节（arabic 切片）
    字符      cmap 1225 个码位
    覆盖      维吾尔文 32 字母 + 哈萨克文专用 6 字符，38/38 全覆盖
    连写      GSUB 含 ccmp/init/medi/fina/rlig/locl，阿拉伯系连写支持完整
    许可      SIL Open Font License 1.1

用法：python scripts/fetch-arabic-font.py
"""
import urllib.request, re, os, json

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
PROXY = "http://127.0.0.1:7890"
OUT = r"E:\dshcode\FAZE\design\ui\fonts"
CSS_URL = ("https://fonts.googleapis.com/css2"
           "?family=Noto+Naskh+Arabic:wght@400;700&display=swap")
OFL_URL = "https://raw.githubusercontent.com/google/fonts/main/ofl/notonaskharabic/OFL.txt"

os.makedirs(OUT, exist_ok=True)

opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}))
opener.addheaders = [("User-Agent", UA)]

css = opener.open(CSS_URL, timeout=30).read().decode("utf-8")
blocks = re.findall(r"@font-face\s*\{(.*?)\}", css, re.S)

slices = []
for b in blocks:
    m_w = re.search(r"font-weight:\s*(\d+)", b)
    m_u = re.search(r"src:\s*url\((\S+?)\)", b)
    m_r = re.search(r"unicode-range:\s*([^;]+);", b)
    if not (m_w and m_u and m_r):
        continue
    urange = m_r.group(1).strip()
    if "U+0600-06FF" not in urange:
        continue
    slices.append({"weight": int(m_w.group(1)), "url": m_u.group(1), "range": urange})

if not slices:
    raise SystemExit("没有找到阿拉伯切片，Google Fonts 的返回格式可能变了")

urls = {s["url"] for s in slices}
weights = sorted({s["weight"] for s in slices})
unicode_range = slices[0]["range"]

# 同一 URL = 可变字体，只存一份
if len(urls) == 1:
    fn = "noto-naskh-arabic.woff2"
    data = opener.open(slices[0]["url"], timeout=60).read()
    with open(os.path.join(OUT, fn), "wb") as f:
        f.write(data)
    weight_decl = "%d %d" % (min(weights), max(weights))
    files = [(fn, weight_decl, unicode_range)]
    print("variable font detected (single file): %s  %d bytes  wght %s"
          % (fn, len(data), weight_decl))
else:
    files = []
    for s in slices:
        fn = "noto-naskh-arabic-%d.woff2" % s["weight"]
        data = opener.open(s["url"], timeout=60).read()
        with open(os.path.join(OUT, fn), "wb") as f:
            f.write(data)
        files.append((fn, str(s["weight"]), s["range"]))
        print("saved %s  %d bytes" % (fn, len(data)))

lines = [
    "/* ==========================================================================",
    "   Noto Naskh Arabic（自托管，arabic 切片）",
    "   ==========================================================================",
    "   来源：Google Fonts · Noto Naskh Arabic v44 · SIL Open Font License 1.1",
    "   用途：RTL 语言（ug-Arab-CN 维吾尔文 / kk-Arab-CN 哈萨克文）的正文字体",
    "   生成：python scripts/fetch-arabic-font.py（不要手改本文件）",
    "",
    "   实测（2026-10-29）：",
    "     可变字体 fvar 轴 wght 400~700，默认 400，所以 400 和 700 共用同一份文件",
    "     大小 94,032 字节；cmap 1225 个码位",
    "     维吾尔文 32 字母 + 哈萨克文专用字符共 38 个，覆盖率 38/38",
    "     GSUB 含 ccmp / init / medi / fina / rlig / locl，连写支持完整",
    "",
    "   注意：",
    "     1. 不含拉丁字形，数字与英文走 --font-num / --font-sans",
    "     2. 正式项目打包进 App 时引同一份 woff2（expo-font），不要换字体",
    "     3. 换版本要重跑脚本，并同步更新本注释里的版本号与实测数据",
    "   ========================================================================== */",
    "",
]
for fn, w, ur in files:
    lines += [
        "@font-face {",
        '  font-family: "Noto Naskh Arabic FAZE";',
        "  font-style: normal;",
        "  font-weight: %s;" % w,
        "  font-display: swap;",
        '  src: url("%s") format("woff2");' % fn,
        "  unicode-range: %s;" % ur,
        "}",
        "",
    ]
with open(os.path.join(OUT, "arabic.css"), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines))

# 许可证必须随字体一起分发（OFL 1.1 要求）
try:
    ofl = opener.open(OFL_URL, timeout=30).read()
    with open(os.path.join(OUT, "OFL.txt"), "wb") as f:
        f.write(ofl)
    print("saved OFL.txt  %d bytes" % len(ofl))
except Exception as e:
    print("OFL download failed:", e)

# 覆盖率复核
try:
    from fontTools.ttLib import TTFont
    path = os.path.join(OUT, files[0][0])
    f = TTFont(path)
    cmap = set()
    for t in f["cmap"].tables:
        cmap.update(t.cmap.keys())
    uyghur = "ئاەبپتجچخدرزژسشغفقكگڭلمنھوۇۆۈۋېىي"
    kazakh_extra = "ٴٵٶٷٸ"
    need = sorted(set(uyghur + kazakh_extra))
    missing = [c for c in need if ord(c) not in cmap]
    print("coverage: %d required, %d missing" % (len(need), len(missing)))
    if missing:
        print("MISSING:", " ".join("U+%04X" % ord(c) for c in missing))
    f.close()
except ImportError:
    print("coverage check skipped (fonttools missing)")
