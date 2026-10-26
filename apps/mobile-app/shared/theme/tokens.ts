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
