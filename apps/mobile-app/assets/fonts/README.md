# assets/fonts

段 0 D2 落地。**字体文件与 OFL 许可文本都在本目录，必须一起保留**（SIL OFL 1.1 要求随分发保留版权与许可）。

## 文件清单

| 文件 | 大小 | 用途 | 许可 |
|---|---|---|---|
| `NotoSerifTibetan-Variable.ttf` | 2,063,504 B | 藏文（`bo`） | SIL Open Font License 1.1 |
| `NotoNaskhArabic-Variable.ttf` | 307,592 B | 阿拉伯系：维吾尔文 `ug-Arab-CN` / 哈萨克文 `kk-Arab-CN` | SIL Open Font License 1.1 |
| `OFL-NotoSerifTibetan.txt` | 4,383 B | 藏文字体的许可原文 | — |
| `OFL-NotoNaskhArabic.txt` | 4,382 B | 阿拉伯系字体的许可原文 | — |

来源（Google Fonts 官方仓库，2026-11-04 下载）：
- `https://github.com/google/fonts/tree/main/ofl/notoseriftibetan`
- `https://github.com/google/fonts/tree/main/ofl/notonaskharabic`

## 一个必须记下来的取舍：藏文用 Serif，不是 Sans

用户给的是 **Noto Sans Tibetan**（SIL OFL 1.1）。但 2026-11-04 实测：

- `google/fonts` 的 `ofl/` 下**没有** `notosanstibetan` 目录（404）；Google Fonts 上收录的藏文 Noto 只有 **Noto Serif Tibetan**。
- `notofonts/notofonts.github.io` 仓库里按 `fonts/NotoSansTibetan/{hinted,unhinted}/ttf/...` 等路径试了多个，**全部 404**。

**所以段 0 用 Noto Serif Tibetan**（同为 OFL 1.1、覆盖完整、可商用）。若将来拿到 Sans 版，只需改两处：
`shared/theme/fonts.ts` 的 `FONT_ASSETS`（文件映射）与 `FONT_FAMILY.tibetan`（字体族名，必须与 `useFonts` 的 key 一致）。

## 为什么是可变字体（`[wght]`）

Google Fonts 仓库只提供可变字体（`NotoSerifTibetan[wght].ttf`）。`expo-font` 的 `useFonts` 按默认实例加载，
段 0 只用 Regular 字形、不涉及字重切换，所以没有影响。若将来要按 weight 切换，需换成静态实例。

## 加载与使用

- 加载：`app/_layout.tsx` 的 `useFonts(FONT_ASSETS)`，**字体未就绪时只铺底色、不渲染路由树**；
  加载失败则回退系统字体继续渲染（不卡启动屏）。
- 使用：`shared/components/LocalizedText.tsx` 按 Unicode 区段切 run，藏文用 `FONT_FAMILY.tibetan`、
  阿拉伯系用 `FONT_FAMILY.arabic`，其余走系统字体。
- **字体族名与 `useFonts` 的 key 不一致 = 加载了但引不到**（表现是「仍是系统字形」）。
