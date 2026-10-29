# PC 管理后台设计稿 · 施工规范

> **读这份文件的人**：接手 `design/admin/` 里某几屏 HTML 的人。
> **不用读的**：只想看设计成什么样的人 —— 直接打开 `design/admin/00-login.html` 起看。
>
> **已完成的范式屏（必须先读源码再动手）**：
> `00-login.html`（唯一不带骨架的页）、`01-dashboard.html`（看板）、`02-user-list.html`（**列表页母版**）。

---

## 1. 你在画什么

法泽 FAZE 是面向新疆伊犁的普惠型法律服务平台，**真实上线产品**。你画的是它的 PC 管理后台。

| 项 | 定论 |
|---|---|
| 页面数 | 26 屏，文件名与编号见《PC 后台开发计划_v1.0》§二 |
| 角色 | **只有「管理员」一个角色**，不做权限细分 |
| 画幅 | **1440×900 固定**，不做响应式 |
| 语言 | 简体中文（多语言与 RTL 放代码层，设计稿不做） |
| 技术底座 | RuoYi-Vue3 3.8.2 + Element Plus，业务页在 `src/views/biz/<domain>/` |
| 气质 | **合理、够用、严谨、有工程性**。不是宣传页，不需要惊艳；要像真的能拿来干活的后台 |

设计稿的用途：给后面的开发照着写 Vue 页面。所以**每一屏都是可落地的**——控件尺寸、行高、间距要贴着 Element Plus 默认值，不要发明框架里没有的交互。

---

## 2. 硬性约束（违反 = 白干）

### 2.1 只改你负责的那几个 HTML

| 文件 | 谁能改 |
|---|---|
| `tokens.css` | **只有 Lead** |
| `components.css` | **只有 Lead** |
| `shell.js` | **只有 Lead** |
| `02-user-list.html` 等已完成屏 | **不要动** |
| 你负责的 `NN-xxx.html` | 你 |

缺组件时：**在你自己页面的 `<style>` 里写局部样式**，类名用「页面名__块名」格式（如 `.ticket__thread`），不要改公共文件，也不要往公共文件里加东西。

### 2.2 每屏 head 必须是这三行 + 页面自有样式

```html
<meta charset="utf-8">
<meta name="dsh-design" content="web 1440x900">
<title>页面中文名 · 法泽管理后台</title>
<link rel="stylesheet" href="tokens.css">
<link rel="stylesheet" href="components.css">
<style>
/* 页面自有样式，带块注释说明为什么这里要单独写 */
</style>
```

`<title>` 由 shell.js 覆盖成「当前页 · 法泽管理后台」，但仍要写对。

**`content` 里的 `1440x900` 不能省。** 设计工作台的 web 类型默认按 1280×800 建画布，只写 `content="web"` 时，骨架 `.shell` 的 `overflow:hidden` 会把页面右侧 160px、下方 100px 直接裁掉，而且不出现滚动条——表现为「所有页面右边显示不完整」。2026-10-30 26 屏全部因此重写过一次 meta。

### 2.3 body 结构固定，不要自己写侧边栏 / 顶栏 / 标签页

侧边栏、顶栏、标签页由 `shell.js` 按 `data-page` 注入。你只写**内容区**：

```html
<body class="shell" data-page="02-user-list">

<aside class="sidebar" id="sidebar"></aside>

<div class="main">
  <header class="navbar" id="navbar"></header>
  <div class="tags-view" id="tags-view"></div>

  <main class="app-container scroll-y">
    <!-- 你的内容全部写在这里 -->
  </main>
</div>

<script src="shell.js"></script>
</body>
```

`data-page` **必须**是你文件的名称（不含 `.html`），因为 `shell.js` 里的 `PAGES` 表靠它决定菜单高亮、面包屑、标签页。写错 = 侧边栏不高亮、面包屑空白。

### 2.4 颜色只能用变量，不许裸色值

- 文字用品牌色时用 `var(--brand-text)`（`#3d52d8`），**不要**用 `var(--brand-primary)` —— 后者当文字只有 4.24:1，不到 4.5 的无障碍线。
- `var(--brand-primary)` 只用于背景、边框、图表线、图标。
- 禁止 `#RRGGBB`、`rgba()`、`hsl()` 字面量。
- 深浅两档：白底/浅底上的次级文字用 `--text-secondary`(#666)，**不要**用 `--text-muted`(#6b7280) —— 它压在选中行 `--bg-selected` 上只有 4.32:1。

### 2.5 尺寸贴着 RuoYi / Element Plus

| 项 | 值 | 变量 |
|---|---|---|
| 侧边栏 / 顶栏 / 标签页 | 200 / 50 / 34 px | 框架层写死，**不可改** |
| 内容区 padding | 20px | `--content-pad` |
| 控件高度 | 32px（小 28） | `--ctrl-h` |
| 表格行高 / 表头 | 48 / 40 px | `--row-h` / `--thead-h` |
| 圆角 | 控件 4、卡片 8 | `--radius-ctrl` / `--r-lg` |
| 间距 | 只准 4/8/12/16/20/24/32/40/48 | `--sp-1`…`--sp-12` |

### 2.6 高度预算：内容区可视高度 **776px**

1440×900 减去顶栏 50、标签页 34、内容区上下 padding 40 = **776px**。

**尽量一屏放下，不要出现滚动。** 列表页按这个算：

| 块 | 高度 |
|---|---|
| 页头（标题+描述+右侧按钮） | 46 + 下间距 16 = 62 |
| 查询栏卡片 | 64（padding 16 上下 + 控件 32） |
| 间距 | 16 |
| 表格卡：工具栏 48 + 表头 40 + **数据行 × 48** + 分页 56 | |
| **合计装得下的数据行数** | **10 行**（62+64+16+48+40+480+56 = 266） |

所以默认就是 **10 行数据**。少了显得空，多了要滚动。详情页/表单页按 776 自己排，宁可有少量滚动也不要硬挤。

---

## 3. 可用组件（都在 `components.css`，直接用，不要重写）

### 骨架
`.shell` `.main` `.sidebar` `.navbar` `.tags-view` `.app-container` `.scroll-y`

### 页头
```html
<div class="page-head">
  <div>
    <h1 class="page-head__title">页面标题</h1>
    <p class="page-head__desc">一句话说明这页能干什么、有什么后果</p>
  </div>
  <div class="page-head__actions"><button class="btn">…</button></div>
</div>
```

### 卡片
`.card` / `.card--flush`（表格通栏，左右不留 padding）/ `.card__head` / `.card__title` / `.card__extra` / `.card__body`

### 查询栏
```html
<div class="card">
  <form class="query-bar">
    <div class="field-inline">
      <label class="field-inline__label" for="x">标签</label>
      <input class="input" id="x" placeholder="…">
    </div>
    <div class="query-bar__actions">
      <button class="btn btn--primary">查询</button>
      <button class="btn" type="reset">重置</button>
    </div>
  </form>
</div>
```
`.query-bar .input` / `.select` 宽 180，`.input--wide` 宽 220。查询项超过 4 个会换行——换行后高度变 96，记得从数据行里扣 1 行。

### 工具栏 + 表格 + 分页
`.toolbar` `.toolbar__left` `.toolbar__right` `.toolbar__count`
`.table-wrap` `.table` `.table--stripe` `.check`（复选框，24px 点击区/16px 视觉）
单元格：`.cell-id` `.cell-strong` `.cell-muted` `.cell-num`（右对齐数字）`.cell-stack` `.cell-stack__sub` `.cell-person` `.cell-person__avatar` `.table__ops`
`.pagination` `.pagination__total` `.page-btn` `.page-btn--active` `.page-btn--disabled` `.pagination__size`

**列宽必须用 `<colgroup>` + 百分比**，合计 100%，不要靠内容撑：

```html
<table class="table xxx-table">
  <colgroup>
    <col class="c-check"><col class="c-a"><col class="c-b">…
  </colgroup>
```
并在页面 `<style>` 里写 `.xxx-table col.c-a { width: 18%; }`。

### 状态标签
`.tag` + `.tag--dot`（前置小圆点，状态类都带）+ 语义色 `.tag--primary`（进行中）/ `.tag--success`（已完成、正常、通过）/ `.tag--warning`（待处理、审核中）/ `.tag--danger`（驳回、封禁、退款中）/ `.tag--gray`（未认证、已关闭）+ `.tag--outline`（分类用）

### 按钮
`.btn`（次操作）/ `.btn--primary`（**一屏只能有一个**）/ `.btn--danger`（驳回、封禁）/ `.btn--danger-solid`（确认弹窗里的终局操作）/ `.btn--text`（表格操作列）/ `.btn--text.is-danger` / `.btn--sm` / `.icon-btn`

### 表单
`.input` `.select` `.textarea` `.search-field` `.switch` `.switch--on` `.checkbox/radio` `.upload-box` `.img-cell` `.img-cell__tag`
`.form-grid`（两列）/ `.form-grid--1` / `.form-row` / `.form-row--wide` / `.form-label` / `.form-label.is-required` / `.form-hint` / `.form-error`

### 抽屉 / 弹窗
`.drawer-mask` `.drawer` `.drawer--wide` `.drawer__head` `.drawer__title` `.drawer__body` `.drawer__foot`
`.modal`（520 宽，居中）`.modal__head` `.modal__title` `.modal__body` `.modal__foot`
**抽屉/弹窗是覆盖态**，同一屏里要么展示列表、要么展示抽屉。需要两者同时出现时，说明这是「打开抽屉后的状态」，那这一屏就只画抽屉那版。

### 详情
`.desc-grid`（两列）/ `.desc-grid--3` / `.desc-item` / `.desc-item--stack` / `.desc-label` / `.desc-value` / `.desc-value--strong`
`.timeline` / `.tl-item` / `.tl-item--active` / `.tl-item__title` / `.tl-item__meta` / `.tl-item__note`
`.subtabs` / `.subtab` / `.subtab--active` / `.tab-panel`
`.divider` `.kv` `.kv__k` `.kv__v` `.avatar` `.avatar--lg`

### 数据与图表
`.stat-grid`（4 列）/ `.stat` / `.stat__label` / `.stat__value`（`<small>` 放单位）/ `.stat__foot` / `.stat__delta--up|down`
`.chart` `.chart__legend` `.legend-item` `.bar-list` `.bar-row` `.bar-row__track` `.bar-row__fill` `.bar-row__value`
**图表一律用内联 SVG 手绘**，不引外链图片、不引图表库。参考 `01-dashboard.html` 里的折线图写法（`viewBox` + 网格线 + `polyline` + 面积 `path`）。

### 左右分栏（审核队列、工单、案件用）
`.split`（380px + 自适应，高度 100%）`.panel` `.panel__head` `.panel__title` `.panel__body` `.panel__foot`
`.rec`（列表里一条记录）/ `.rec--active` / `.rec__top` / `.rec__title` / `.rec__meta` / `.rec__text`

### 空态 / 提示
`.empty` `.empty__art` `.empty__title` `.empty__desc`（空态）
`.skeleton`（加载骨架）
`.alert` `.alert--warning` `.alert--danger` `.alert--success`（提示条）
`.chips` `.chip` `.chip--active` `.chip__count`（快速筛选）

### 工具类
`.row` `.row--between` `.row--top` `.col` `.grow` `.mono` `.num` `.nowrap` `.text-primary` `.text-muted` `.text-danger` `.text-success` `.text-sm` `.text-xs` `.text-strong`
`.mt-1`…`.mt-5` `.mb-2`…`.mb-4`

---

## 4. 每屏写多少内容才算够

**必须**：
1. **真实中文文案**，不要 Lorem ipsum、不要「示例文字」「标题一」。人名用新疆地区的真实感姓名（汉族 + 维吾尔族混排），地名用伊犁州真实县市（伊宁市、奎屯市、霍尔果斯市、察布查尔县、伊宁县、霍城县、新源县、特克斯县、昭苏县、尼勒克县、巩留县）。
2. **状态覆盖全**：列表里要同时出现成功/警告/危险三种状态的样本，别一屏全是「正常」。
3. **数字合理**：金额带两位小数（`¥ 1,280.00`），订单号 `FAZE-2026-0082`，用户 ID `U-10284`，手机号 `138****6621`，时间 `2026-10-29 21:14`。金额、单号、时间用 `.mono` 等宽。
4. **操作列有真实动作**：「详情 / 编辑 / 驳回 / 下架」，不是「操作」两个字。
5. **文件顶部块注释**：写明这屏的职责、依据（《PC 后台开发计划》哪一条）、接口状态（有/缺）、以及为什么这么排。

**不要**：占位图片链接（用 `.img-cell` 里内联 SVG 画个证件轮廓）、emoji 当图标、渐变、发光、大圆角、彩色卡片满屏。

---

## 5. 自查清单（交之前逐条过）

- [ ] `data-page` 与文件名一致
- [ ] 没有改动 `tokens.css` / `components.css` / `shell.js` / 别人的屏
- [ ] 所有颜色来自变量，没有 `#` / `rgba()` / `hsl()` 字面量
- [ ] 品牌色文字用 `--brand-text`，次级文字用 `--text-secondary`
- [ ] 内容高度 ≤ 776px（列表页 10 行）
- [ ] 每个列表页都有查询栏、工具栏、分页
- [ ] 一屏只有一个 `.btn--primary`
- [ ] 状态标签带 `.tag--dot`，语义色对（待处理=warning、驳回=danger、完成=success）
- [ ] 图标是内联 SVG 线性图标（24 网格，`stroke-width` 1.8，`round` 端点），不用 emoji
- [ ] 页内链接指向真实存在的文件（`10-order-list.html` 之类）；**本批可能指向别人还没画的页，那是允许的**，但文件名必须和计划表一致
- [ ] 中文标点全角，中英文之间留空格
