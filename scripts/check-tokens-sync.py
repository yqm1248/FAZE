# -*- coding: utf-8 -*-
"""
校验 design/tokens/tokens.json 与 design/ui/tokens.css 是否一致。

存在的理由：
  第 1 批为了通过对比度检查在 tokens.css 里直接改了 4 个颜色，没回写 tokens.json，
  后续批次又补了 10 个变量（图标三色、渐变、警告色），也没回写。
  结果 JSON 声称自己是「唯一来源」，实际值和线上渲染差了 4 个颜色 + 缺 10 个变量。
  这种偏差靠人肉看不出来，必须有脚本兜住。

用法：
    python scripts/check-tokens-sync.py          # 有偏差则 exit 1
    python scripts/check-tokens-sync.py -v       # 同时打印全部比对项

两类「预期不算偏差」的变量：
  1. 只在 CSS 存在的（@font-face 拼接用的 --font-sans / --font-num / --font-arabic）
  2. 复合值（--shadow-* 在 JSON 里是对象，CSS 里是字符串），无法直接字符串比对
这些在 KNOWN_CSS_ONLY / SKIP 里显式登记，不是静默放过。
"""
import json, re, sys, io, os

# Windows 控制台默认 cp936，中文输出会乱码
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JSON_PATH = os.path.join(ROOT, "design", "tokens", "tokens.json")
CSS_PATH = os.path.join(ROOT, "design", "ui", "tokens.css")

# CSS 变量名 -> tokens.json 路径（"a.b.c" 用点分隔，[n] 表示数组下标）
MAP = {
    "brand-primary": "color.brand.primary.value",
    "brand-primary-pressed": "color.brand.primaryPressed.value",
    "brand-primary-soft": "color.brand.primarySoft.value",
    "brand-accent": "color.brand.accent.value",
    "brand-accent-soft": "color.brand.accentSoft.value",
    "login-title": "color.brand.loginTitle.value",
    "bg-canvas": "color.background.canvas.value",
    "bg-surface": "color.background.surface.value",
    "bg-input": "color.background.input.value",
    "text-primary": "color.text.primary.value",
    "text-body": "color.text.body.value",
    "text-secondary": "color.text.secondary.value",
    "text-muted": "color.text.muted.value",
    "text-on-brand": "color.text.onBrand.value",
    "border-subtle": "color.border.subtle.value",
    "border-divider": "color.border.divider.value",
    "border-outline": "color.border.outline.value",
    "state-success": "color.state.success.value",
    "state-success-soft": "color.state.successSoft.value",
    "state-warning": "color.state.warning.value",
    "state-warning-soft": "color.state.warningSoft.value",
    "state-warning-deep": "color.state.warningDeep.value",
    "state-danger": "color.state.danger.value",
    "icon-doc": "color.icon.doc.value",
    "icon-lawyer": "color.icon.lawyer.value",
    "icon-progress": "color.icon.progress.value",
    "banner-start": "color.banner.start.value",
    "banner-end": "color.banner.end.value",
    "profile-start": "gradient.profileHeader.value[0]",
    "profile-end": "gradient.profileHeader.value[1]",
    "login-btn-start": "gradient.loginButton.value[0]",
    "login-btn-end": "gradient.loginButton.value[1]",
    "fs-h1": "fontSize.h1.value",
    "fs-h2": "fontSize.h2.value",
    "fs-h3": "fontSize.h3.value",
    "fs-lg": "fontSize.bodyLarge.value",
    "fs-md": "fontSize.body.value",
    "fs-sm": "fontSize.bodySmall.value",
    "fs-caption": "fontSize.caption.value",
    "fs-micro": "fontSize.micro.value",
    "fw-regular": "fontWeight.regular.value",
    "fw-medium": "fontWeight.medium.value",
    "fw-bold": "fontWeight.bold.value",
    "lh-tight": "lineHeight.tight.value",
    "lh-normal": "lineHeight.normal.value",
    "lh-relaxed": "lineHeight.relaxed.value",
    "lh-loose": "lineHeight.loose.value",
    "sp-xxs": "spacing.xxs.value",
    "sp-xs": "spacing.xs.value",
    "sp-sm": "spacing.sm.value",
    "sp-md": "spacing.md.value",
    "sp-lg": "spacing.lg.value",
    "sp-xl": "spacing.xl.value",
    "sp-xxl": "spacing.xxl.value",
    "sp-xxxl": "spacing.xxxl.value",
    "sp-huge": "spacing.huge.value",
    "r-xs": "radius.xs.value",
    "r-sm": "radius.sm.value",
    "r-md": "radius.md.value",
    "r-lg": "radius.lg.value",
    "r-pill": "radius.pill.value",
    "screen-w": "size.screenWidth.value",
    "screen-h": "size.screenHeight.value",
    "statusbar-h": "size.statusBarHeight.value",
    "tabbar-h": "size.tabBarHeight.value",
    "safe-bottom": "size.safeAreaBottom.value",
    "navbar-h": "size.navBarHeight.value",
    "btn-h": "size.buttonHeight.value",
    "input-h": "size.inputHeight.value",
}

# 只在 CSS 存在的变量：字体栈是 CSS 专属投影，JSON 里用字体家族名表达
KNOWN_CSS_ONLY = {
    "font-sans": "中文系统字体栈，JSON 里表达为 typography.fontFamily.default = System",
    "font-num": "数字字体栈，JSON 里为 typography.fontFamily.numeric",
    "font-arabic": "阿拉伯系字体栈，JSON 里为 typography.fontFamily.arabic",
}

# 复合值，不做字符串比对
SKIP_PREFIX = ("shadow-",)


def load_json(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def resolve(obj, dotted):
    """按 'a.b.c' / 'a.value[0]' 取值。"""
    m = re.match(r"^(.*)\[(\d+)\]$", dotted)
    if m:
        arr = resolve(obj, m.group(1))
        return arr[int(m.group(2))]
    cur = obj
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return "<路径不存在>"
        cur = cur[part]
    return cur


def norm(v):
    """统一成可比较的字符串：#FFF/#FFFFFF、15px/'15'、1.75 都视作相等。"""
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    s = str(v).strip()
    s = s.strip("'\"")
    # CSS 里带单位，JSON 里是裸数字
    s = re.sub(r"px$", "", s, flags=re.I)
    if re.match(r"^#[0-9a-fA-F]{3}$", s):
        s = "#" + "".join(c * 2 for c in s[1:])
    return s.upper()


def parse_css(path):
    with io.open(path, encoding="utf-8") as f:
        css = f.read()
    # 先剥掉注释，避免注释里的 --xxx: 被误抓
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    m = re.search(r":root\s*\{(.*?)\}", css, re.S)
    if not m:
        raise SystemExit("tokens.css 里找不到 :root 块")
    out = {}
    for name, val in re.findall(r"--([\w-]+)\s*:\s*([^;]+);", m.group(1)):
        out[name] = val.strip()
    return out


def main():
    verbose = "-v" in sys.argv
    data = load_json(JSON_PATH)
    css = parse_css(CSS_PATH)

    print("tokens.json version :", data["$meta"]["version"])
    print("tokens.css 变量数   :", len(css))
    print("映射表条目数        :", len(MAP))

    errors, checked = [], 0

    for name, path in sorted(MAP.items()):
        if name not in css:
            errors.append("CSS 缺变量 --%s（JSON 期望 %s）" % (name, path))
            continue
        want = resolve(data, path)
        if want == "<路径不存在>":
            errors.append("JSON 缺路径 %s（CSS 有 --%s = %s）" % (path, name, css[name]))
            continue
        checked += 1
        got = norm(css[name])
        exp = norm(want)
        if got != exp:
            errors.append("值不一致 --%s : CSS=%s  JSON(%s)=%s" % (name, got, path, exp))
        elif verbose:
            print("  ok  --%-22s %s" % (name, got))

    # CSS 里有、但既不在映射表也不在白名单里的变量
    for name in sorted(css):
        if name in MAP or name in KNOWN_CSS_ONLY:
            continue
        if name.startswith(SKIP_PREFIX):
            continue
        errors.append("CSS 有未登记的变量 --%s = %s（要么加进 MAP，要么加进 KNOWN_CSS_ONLY）"
                      % (name, css[name]))

    # 反向：MAP 里登记了但 CSS 没有（上面已覆盖），再查 JSON 里有字体家族是否登记
    if "arabic" in data.get("typography", {}).get("fontFamily", {}):
        if "font-arabic" not in css:
            errors.append("JSON 声明了 arabic 字体，但 tokens.css 没有 --font-arabic")

    print("比对通过项          :", checked)
    print("")
    if errors:
        print("发现 %d 个问题：" % len(errors))
        for e in errors:
            print("  ! " + e)
        return 1
    print("OK：tokens.json 与 tokens.css 一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
