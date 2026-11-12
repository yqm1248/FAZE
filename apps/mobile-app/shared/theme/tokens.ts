/**
 * 设计令牌 —— 运行时实现。
 *
 * 本文件是 `design/tokens/tokens.json` 的 TypeScript 实现，两者必须同步修改。
 * 页面与组件只能从这里取视觉数值，禁止写颜色/字号/间距字面量
 * （ESLint 规则 `no-restricted-syntax` 会拦截 `#RRGGBB` 与 `rgba()` 字面量）。
 *
 * 单位说明：长度一律为 RN 逻辑像素（dp/pt）。原项目使用 rpx，
 * 按 `750rpx = 375dp` 换算，即 `1rpx = 0.5dp`。
 *
 * 取值来源：配色基线取自原项目首页 `pages/index/index.vue`，
 * 其余页面按 `docs/design/页面清单与信息架构.md` 第 4 节映射表归并。
 */

/**
 * 颜色令牌。
 *
 * 分组说明：
 * - `brand`：品牌色，用于主按钮、选中态、链接、AI 强调；
 * - `background`：页面与容器底色；
 * - `text`：四级文字层级；
 * - `border`：描边与分割线，两种语义分开；
 * - `state`：状态色，只表达状态，不参与主题；
 * - `banner`：顶部渐变起止色。
 */
export const color = {
  brand: {
    /** 主色。取自原首页语言切换按钮文字色 `#4e6ef2`（`index.vue:168`）。 */
    primary: '#4E6EF2',
    /** 主色按下态。主色降明度约 15%，用于按钮 `pressed` 反馈。 */
    primaryPressed: '#3D58D6',
    /** 强调色。取自原首页 AI 解答区块标题 `#8A2BE2`（`index.vue:182`），仅用于 AI 相关标题。 */
    accent: '#8A2BE2',
    /** 强调色浅底。用于 AI 标签背景。 */
    accentSoft: '#F1E9FD',
    /**
     * 主色浅底。
     *
     * 对应原项目多处浅蓝底：`#eef4ff`（起诉状提示条）、`#f0f4ff`（入驻页选中卡）、
     * `#eef2ff`（律师详情「资深律师」标签）、`#f5f7ff`（业务擅长标签）。
     * 这四处原本都是淡蓝，统一收敛为同一个令牌，不要用 `accentSoft` 代替：
     * 浅紫会让人误以为与 AI 功能相关。
     */
    primarySoft: '#EEF2FF',
  },

  background: {
    /** 页面底色。原首页 `page { background-color: #F7F8FA }`。 */
    canvas: '#F7F8FA',
    /** 卡片、面板、底部操作栏底色。 */
    surface: '#FFFFFF',
    /** 输入框与模拟搜索框底色。原 `index.vue:184`。 */
    input: '#F5F5F5',
  },

  text: {
    /** 大标题。原首页 slogan。 */
    primary: '#1A1A1A',
    /** 正文与卡片标题。 */
    body: '#333333',
    /** 次要说明。 */
    secondary: '#666666',
    /** 占位符、meta、tab 未选中态。 */
    muted: '#999999',
    /** 品牌色底上的文字。 */
    onBrand: '#FFFFFF',
  },

  border: {
    /** 功能卡描边。原 `index.vue:197`。 */
    subtle: '#F0F0F0',
    /** 列表分割线。原 `index.vue:212`。 */
    divider: '#F5F5F5',
    /** 标签描边、次要边框。 */
    outline: '#EEEEEE',
  },

  state: {
    /**
     * 成功/在线/进行中。
     * 原项目主题色 `#2C9696` 在此降级为状态色：只用于"在线""进行中"标签，
     * 不再作为任何页面的主题色（主题色统一为主色 `#4E6EF2`）。
     */
    success: '#2C9696',
    /**
     * 成功状态的浅底色。
     * 对应原 `background: rgba(44,150,150,0.1)`——把 `#2C9696` 以 10% 叠在白底上的结果，
     * 预先算成实色，避免在业务代码里写 rgba 字面量。
     */
    successSoft: '#EBF5F5',
    /** 评分星标。 */
    warning: '#FF9500',
    /** 退出登录、风险操作。 */
    danger: '#EE0A24',
  },

  banner: {
    /** 顶部渐变起点。原 `index.vue:153`。 */
    start: '#D2E3FB',
    /** 顶部渐变终点，必须等于 `background.canvas`，否则渐变底部会出现硬边。 */
    end: '#F7F8FA',
  },

  /**
   * 半透明浮层色。
   *
   * 用途：品牌渐变底上的半透明圆角块、分割线。
   * 单独建组的原因：RN 没有 CSS 的 `background: rgba(...)` 简写场景，
   * 而"父容器整体 opacity"会把子元素一起变透明（例如启动页的 logo 框），
   * 因此需要一个可以直接作为背景色的半透明值。
   */
  overlay: {
    /** 22% 白。启动页 logo 框底色，对应原 `rgba(255,255,255,0.2)`。 */
    whiteSoft: '#FFFFFF38',
    /** 10% 白。品牌渐变底上的分割线。 */
    whiteFaint: '#FFFFFF1A',
    /** 60% 黑。遮罩层。 */
    scrim: '#00000099',
  },
} as const;

/**
 * 深色模式颜色令牌（第 8 段 E-4，tokens.json v1.2.0）。
 *
 * **与 `color` 完全同构**：33 个叶子键一一对应。往 `color` 加令牌时必须同时在这里加一条，
 * 否则 `scripts/check-tokens-sync.py` 会报叶子数不一致——两套令牌不同构，
 * 深色模式下就会出现「某个颜色没值」的黑洞。
 *
 * 取用方式：**不要在组件里直接 import 这个常量**，用 `shared/theme/darkMode.ts` 的
 * `getColorTokens(mode)`——它会按 `settings.theme`（system / on / off）与系统外观选一套。
 *
 * 注意：本段只做**令牌层**。全仓 71 个页面目前仍直接 import 静态的 `color`，
 * 换成响应式取用是独立改造（涉及 71 个文件 + 一个 ThemeContext），不在第 8 段范围。
 */
export const darkColor = {
  brand: {
    /** 主色深色版：在 #111316 上对比度 5.6:1（浅色版只有 3.1:1，不合格） */
    primary: '#7C93F7',
    /** 主色按下态深色版：比 primary 压暗约 12%，保持"按下去更暗"的直觉 */
    primaryPressed: '#5F77E0',
    /** 强调色深色版：紫在深底上要提亮，否则与正文灰分不开 */
    accent: '#B78BF2',
    /** 强调色浅底的深色替代：同色相压暗，用于 AI 标签背景 */
    accentSoft: '#2B2140',
    /** 主色浅底的深色替代：用于提示条、选中卡、标签底 */
    primarySoft: '#1E2542',
    /** 登录页标题色深色版 */
    loginTitle: '#93A2FB',
  },
  background: {
    /** 页面底色深色版：近黑不纯黑，保留与卡片的层次差 */
    canvas: '#111316',
    /** 卡片/面板底色深色版：比 canvas 亮一档 */
    surface: '#1C1F24',
    /** 律师工作台顶栏深色版：比页面更暗，仍用深色区分角色 */
    workbench: '#0B0D12',
    /** 输入框底色深色版：比 surface 再亮一档，让输入区可辨 */
    input: '#272A31',
  },
  text: {
    /** 大标题深色版：接近白但不刺眼 */
    primary: '#F2F3F5',
    /** 正文深色版 */
    body: '#D7DAE0',
    /** 次要说明深色版：在 #1C1F24 上对比度 6.8:1 */
    secondary: '#A6AAB3',
    /** 占位符深色版：仍要满足 4.5:1（在 #1C1F24 上约 4.9:1） */
    muted: '#8B9199',
    /** 品牌色底上的文字：深色模式下品牌底仍是彩色，文字仍用白 */
    onBrand: '#FFFFFF',
  },
  border: {
    /** 细分隔线深色版 */
    subtle: '#2A2E35',
    /** 分割线深色版 */
    divider: '#23272D',
    /** 描边深色版：比分隔线更明显一档 */
    outline: '#303540',
  },
  state: {
    /** 成功色深色版：深底上要提亮到可读 */
    success: '#4FD1C5',
    /** 成功色浅底深色替代 */
    successSoft: '#142B2A',
    /** 警告色深色版 */
    warning: '#FFB340',
    /** 警告色浅底深色替代 */
    warningSoft: '#322415',
    /** 警告深文字深色版：浅色时是"深色文字压浅底"，深色时反过来要亮 */
    warningDeep: '#FFC46B',
    /** 危险色深色版 */
    danger: '#FF5A6E',
    /** 危险色浅底深色替代 */
    dangerSoft: '#3A1A1E',
  },
  icon: {
    /** 文书图标深色版 */
    doc: '#5FE0D5',
    /** 律师图标深色版 */
    lawyer: '#8CA1F8',
    /** 进度图标深色版 */
    progress: '#FFBE4D',
  },
  banner: {
    /** 顶部渐变起点深色版 */
    start: '#1B2A44',
    /** 顶部渐变终点深色版：与 canvas 同色，渐变自然收进页面 */
    end: '#111316',
  },
  overlay: {
    /** 半透明白叠加层深色版：白在深底上更显眼，不透明度从 22% 降到 12% */
    whiteSoft: '#FFFFFF1F',
    /** 更淡的白色叠加层深色版 */
    whiteFaint: '#FFFFFF12',
    /** 遮罩深色版：深色背景上需要更重的遮罩才能压出层次 */
    scrim: '#000000B3',
  },
} as const;

/**
 * 渐变令牌。
 *
 * `colors` 元组直接传给 `expo-linear-gradient` 的 `colors` 属性，
 * 因此类型必须是 `readonly [string, string, ...string[]]`。
 */
export const gradient = {
  /** 首页顶部 banner：浅蓝过渡到页面底色。 */
  headerBanner: [color.banner.start, color.banner.end] as const,
  /** 认证页、启动页的整屏品牌渐变。 */
  splash: [color.brand.primary, '#3452C8'] as const,
  /** 个人中心头部渐变：主色到底色。 */
  profileHeader: [color.brand.primary, '#6B87F5'] as const,
} as const;

/**
 * 字号令牌。
 *
 * 括号内为对应的原项目 rpx 值，便于与原文件逐一核对。
 */
export const fontSize = {
  /** 24dp —— 首页标语、认证页主标题（原 48rpx）。 */
  h1: 24,
  /** 17dp —— 页面标题（原 34rpx）。 */
  h2: 17,
  /** 16dp —— 区块标题（原 32rpx）。 */
  h3: 16,
  /** 15dp —— 正文、律师姓名（原 30rpx）。 */
  bodyLarge: 15,
  /** 14dp —— 列表正文（原 28rpx）。 */
  body: 14,
  /** 13dp —— 次要说明（原 26rpx）。 */
  bodySmall: 13,
  /** 12dp —— 功能卡文字、标签（原 24rpx）。 */
  caption: 12,
  /** 11dp —— meta 时间、状态标签（原 22rpx）。 */
  micro: 11,
} as const;

/** 字重令牌。RN 中必须用字符串形式的数值。 */
export const fontWeight = {
  regular: '400',
  medium: '500',
  bold: '700',
} as const;

/** 行高令牌。倍数形式，直接用于 `lineHeight` 计算。 */
export const lineHeight = {
  tight: 1.3,
  normal: 1.5,
  relaxed: 1.6,
  loose: 1.8,
} as const;

/**
 * 间距令牌。
 *
 * 原项目 30rpx（=15dp）是卡片内边距基线，40rpx（=20dp）是页面横向边距基线，
 * 这两个值决定了全站的视觉节奏，不要轻易改动。
 */
export const spacing = {
  /** 2dp —— 原 4rpx。 */
  xxs: 2,
  /** 6dp —— 原 12rpx。 */
  xs: 6,
  /** 8dp —— 原 16rpx。 */
  sm: 8,
  /** 12dp —— 原 24rpx。 */
  md: 12,
  /** 15dp —— 原 30rpx，卡片内边距基线。 */
  lg: 15,
  /** 20dp —— 原 40rpx，页面横向边距基线。 */
  xl: 20,
  /** 25dp —— 原 50rpx。 */
  xxl: 25,
  /** 30dp —— 原 60rpx。 */
  xxxl: 30,
  /** 50dp —— 原 100rpx，顶部刘海安全区预留。 */
  huge: 50,
} as const;

/** 圆角令牌。`pill` 用于胶囊按钮与圆形头像。 */
export const radius = {
  /** 4dp —— 原 8rpx，小标签。 */
  xs: 4,
  /** 6dp —— 原 12rpx，功能卡、搜索框。 */
  sm: 6,
  /** 12dp —— 原 24rpx，主卡片。 */
  md: 12,
  /** 20dp —— 原 40rpx，胶囊按钮。 */
  lg: 20,
  /** 999dp —— 完全圆角。 */
  pill: 999,
} as const;

/** 尺寸令牌。高度、头像、缩略图等跨页面复用的固定尺寸。 */
export const size = {
  /** 底部 tab 内容区高度，不含安全区。 */
  tabBarHeight: 50,
  /** tab 图标尺寸。沿用原 24px 素材的显示尺寸。 */
  tabIconSize: 24,
  /** 自定义导航栏内容高度，不含状态栏。 */
  navBarHeight: 44,
  /** 主按钮高度，原 100rpx。 */
  buttonHeight: 50,
  /** 表单输入行高度，原 90rpx。 */
  inputHeight: 45,
  /** 律师详情头像，原 150rpx。 */
  avatarLarge: 75,
  /** 个人中心头像，原 140rpx。 */
  avatarMedium: 70,
  /** 聊天头像，原 80rpx。 */
  avatarSmall: 40,
  /** 功能卡图标，原 60rpx。 */
  cardIcon: 30,
  /** 律师卡头像宽，原 130rpx。 */
  listThumbWidth: 65,
  /** 律师卡头像高，原 160rpx —— 竖版比例是原设计的特征，勿改为方形。 */
  listThumbHeight: 80,
  /** 发丝线宽度，原 1rpx。 */
  hairline: 0.5,
} as const;

/**
 * 阴影令牌。
 *
 * iOS 使用 `shadowColor/shadowOffset/shadowOpacity/shadowRadius`，
 * Android 使用 `elevation`，两者同时给出以保证双端观感一致。
 */
export const shadow = {
  /** 卡片阴影。原 `box-shadow: 0 4rpx 20rpx rgba(0,0,0,0.05)`。 */
  card: {
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.05,
    shadowRadius: 10,
    elevation: 2,
  },
  /** 高层级卡片阴影。个人中心用户卡使用。 */
  cardRaised: {
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.08,
    shadowRadius: 12,
    elevation: 4,
  },
  /** 主色按钮投影。 */
  pill: {
    shadowColor: color.brand.primary,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 10,
    elevation: 3,
  },
} as const;

/**
 * 聚合导出。
 *
 * 页面按语义分组导入即可，例如 `import { tokens } from '@/shared/theme'`，
 * 使用 `tokens.color.brand.primary`。分组导入（`import { color }`）也支持，
 * 便于在单文件内只取需要的部分。
 */
export const tokens = {
  color,
  gradient,
  fontSize,
  fontWeight,
  lineHeight,
  spacing,
  radius,
  size,
  shadow,
} as const;

export type Tokens = typeof tokens;
