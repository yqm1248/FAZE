# -*- coding: utf-8 -*-
"""
职责：为第 1 段 F 组的影像验收生成**可渲染的占位图**（不是真实证件，只用于验证签名 URL 链路）。
调用方：Lead 在 F 组联调前手工执行一次。
数据来源：无（脚本自带图样）。
注意：
  1. 只覆盖 `realname_auth` / `enterprise_auth` / `lawyer_application` 目前引用到的对象 key，
     不改数据库、不改 key（换了 key 反而要改库）。
  2. 这些是**假影像**：上线前必须由真实用户重新上传。段 1 只证明「签名 URL → 能出图」这一段通。
  3. 用系统字体 msyh.ttc 渲染中文，避免出方块。
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

BASE = Path(r"E:\dshcode\faze-files")
FONT = r"C:\Windows\Fonts\msyh.ttc"

# (相对 key, 标题, 副标题)
TARGETS = [
    ("private/realname/202611/3/5410dd05-050c-4365-b184-e13c01813fe4.jpg", "身份证人像面（占位）", "第 1 段 F 组联调用 / 非真实证件"),
    ("private/realname/202611/3/9a045002-658b-49b2-ae43-78d7555b7b9a.jpg", "身份证国徽面（占位）", "第 1 段 F 组联调用 / 非真实证件"),
    ("private/license/202611/3/775b98cd-874f-415a-963f-87bb6be7eba7.jpg", "营业执照（占位）", "企业认证审核联调用"),
    ("private/license/202611/3/9e9e79de-825d-4def-b1fd-fabe4322844e.jpg", "律师执业证（占位）", "入驻审核联调用"),
    ("private/license/202611/2/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg", "律师执业证（占位）", "入驻审核联调用"),
]


def make(title: str, subtitle: str) -> Image.Image:
    img = Image.new("RGB", (640, 400), (232, 238, 247))
    draw = ImageDraw.Draw(img)
    # 边框
    draw.rectangle([8, 8, 631, 391], outline=(120, 144, 178), width=3)
    # 水印斜线（提醒这是占位图）
    for x in range(-400, 700, 46):
        draw.line([(x, 400), (x + 400, 0)], fill=(210, 220, 234), width=2)
    try:
        f_big = ImageFont.truetype(FONT, 34)
        f_small = ImageFont.truetype(FONT, 18)
    except OSError:
        f_big = ImageFont.load_default()
        f_small = ImageFont.load_default()
    draw.text((60, 150), title, font=f_big, fill=(38, 60, 92))
    draw.text((60, 210), subtitle, font=f_small, fill=(90, 110, 138))
    draw.text((60, 250), "PLACEHOLDER - NOT A REAL DOCUMENT", font=f_small, fill=(150, 120, 120))
    return img


def main() -> None:
    img = make(*TARGETS[0][1:])
    for rel, title, subtitle in TARGETS:
        out = BASE / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        if title != TARGETS[0][1]:
            img = make(title, subtitle)
        img.save(out, "JPEG", quality=82)
        print(f"{rel}  ->  {out.stat().st_size} B")


if __name__ == "__main__":
    main()
