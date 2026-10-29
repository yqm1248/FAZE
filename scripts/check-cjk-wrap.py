#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check-cjk-wrap.py —— 检查设计稿里「中文被源码换行拆开」产生的多余空格

背景
----
HTML 会把源码里的换行 + 缩进折叠成一个空格。中文正文本不该有词间空格，
所以只要一个句子在源码里换了行，渲染出来就会多一个空格：

    源码：  这个数通常比你
            实际收入低
    渲染：  这个数通常比你 实际收入低

第 6 批做案例详情时第一版就是这么写出来的，走查截图里「比你 实际收入低」
一眼就能看出来，但不逐句读很容易漏。

做法
----
只看 <body> 之后、且不在 <!-- --> 注释内的行。
若「本行去尾空白后以中文字符/中文标点结尾」且「下一行去头空白后
以中文字符/中文标点开头」，判定为会产生多余空格。

不判定的两种情况（它们是**应该**有空格或空白会被折叠掉的）：
  1. 行尾是标签结束 `>`，行首是中文 —— 这多半是块级元素内容的开头，
     浏览器会 strip 前导空白；也可能是中文接数字，那本来就该留空格。
  2. 行尾是中文，行首是 `<` —— 行内标签换行确实也会多空格，但需要判断
     标签内的首字是不是中文，误报率高，这里不自动判，交给人工。

退出码
------
0 = 没发现；1 = 发现了（打印文件名、行号、前后文）
CI / 手工自查都能用。

用法
----
    python scripts/check-cjk-wrap.py
    python scripts/check-cjk-wrap.py design/ui/19-case-detail.html
"""

import re
import sys
from pathlib import Path

# 中日韩统一表意文字 + 中文标点（含全角符号区、弯引号、破折号、省略号、间隔号）
CJK = re.compile(
    r"[\u4e00-\u9fff\u3000-\u303f\uff01-\uff65\u2018\u2019\u201c\u201d\u2014\u2026\u00b7]"
)

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = REPO_ROOT / "design" / "ui"


def scan(path: Path):
    """返回 [(行号, 本行末尾, 下一行开头), ...]；没有则空列表。"""
    lines = path.read_text(encoding="utf-8").split("\n")

    try:
        body_start = next(i for i, line in enumerate(lines) if "<body>" in line)
    except StopIteration:
        return []

    hits = []
    in_comment = False

    for i in range(body_start + 1, len(lines) - 1):
        raw = lines[i]

        # 跳过 <!-- ... --> 区间（注释里的中文换行不渲染，不算问题）
        if in_comment:
            if "-->" in raw:
                in_comment = False
            continue
        if "<!--" in raw and "-->" not in raw:
            in_comment = True
            continue

        cur = raw.rstrip()
        nxt = lines[i + 1].lstrip()
        if not cur or not nxt:
            continue
        if cur.endswith(">") or nxt.startswith("<"):
            continue
        if CJK.search(cur[-1]) and CJK.match(nxt[0]):
            hits.append((i + 1, cur[-18:], nxt[:18]))

    return hits


def main(argv):
    # Windows 控制台默认 GBK，遇到 ⏎ 这类符号会直接抛 UnicodeEncodeError 中断检查，
    # 这里把输出流的错误策略放宽，宁可显示成 ? 也不要让脚本挂掉。
    try:
        sys.stdout.reconfigure(errors="replace")
    except (AttributeError, ValueError):
        pass

    if len(argv) > 1:
        targets = [Path(a) for a in argv[1:]]
    else:
        targets = sorted(DEFAULT_DIR.glob("*.html"))

    total = 0
    for path in targets:
        if not path.is_file():
            print(f"跳过（不存在）：{path}")
            continue
        hits = scan(path)
        if not hits:
            continue
        rel = path.relative_to(REPO_ROOT) if path.is_relative_to(REPO_ROOT) else path
        print(f"\n{rel}  共 {len(hits)} 处")
        for lineno, tail, head in hits:
            print(f"  {lineno}: ...{tail}  ||  {head}...")
        total += len(hits)

    if total:
        print(f"\n合计 {total} 处。这些换行会在渲染时变成多余空格，把两行接起来即可。")
        return 1

    print(f"检查了 {len(targets)} 个文件，没有发现中文换行造成的多余空格。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
