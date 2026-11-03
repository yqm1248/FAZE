# assets/fonts

段 0 B3 先建目录与 `useFonts` 挂钩；**字体文件在 D2 落地**。

## 为什么需要自备字体

藏文（`bo`）与阿拉伯系（`ug-Arab-CN` / `kk-Arab-CN`）在 Android 系统字体里覆盖不全，
必须随包携带字体文件，否则 `0-A4`（藏文渲染）与 RTL 连写都会出问题。

## D2 要放什么

| 文件 | 用途 | 授权 |
|---|---|---|
| `NotoSansTibetan-Regular.ttf` | 藏文 | **商用授权未定**（段 0 §十一 #8，负责人待给结论） |
| `NotoNaskhArabic-Regular.ttf` | 维吾尔文 / 哈萨克文（阿拉伯字母版） | 同上 |

字体族名必须与 `shared/components/LocalizedText.tsx` 里的 `FONT_TIBETAN` /
`FONT_ARABIC` 常量**完全一致**，否则 `useFonts` 加载了但样式引不到。

## 段 0 的现状

字体文件未落地，`LocalizedText` 引用不存在的字体族名时 RN 会自动回退系统字体——
这是**预期行为**，不是 bug。`0-A4` 的判定在 D2 才做。
