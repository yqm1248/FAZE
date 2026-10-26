/**
 * 原项目位图素材的统一导出口。
 *
 * 为什么要有这一层：原素材文件名包含空格（`if-group 2.png`、`fa5-history-fas 3.png`），
 * 且后续可能替换为品牌素材。页面若直接写 `require('../../assets/legacy/if-group 2.png')`，
 * 一旦换名就要全局搜索替换。此处把"文件名"与"语义名"解耦。
 *
 * 素材来源：`E:\uniapp1\项目\FazeApp\static\`，保留原始文件名与原始像素尺寸，未做二次压缩。
 * 现状（事实）：`logo.png` 为 uni-app 默认模板图标，`ls1.jpg` 右下角带 AI 生成水印，
 * 本次按需求沿用，替换时只改本文件。
 */

/* eslint-disable @typescript-eslint/no-require-imports -- RN 的 Image source 必须使用 require 静态引用，才能被 Metro 打包。 */

/** 素材语义名与文件路径的对照。 */
export const LEGACY_IMAGES = {
  /* 底部 tab 图标（24×24）。原项目未提供选中态图标，选中差异由文字颜色表达。 */
  tabHome: require('./dibu1.png') as number,
  tabLawyer: require('./dibu2.png') as number,
  tabAgent: require('./znt.png') as number,
  tabCase: require('./dibu3.png') as number,
  tabProfile: require('./dibu4.png') as number,

  /* 首页三宫格功能图标。 */
  iconDocument: require('./iconPark-folder.png') as number,
  iconGroup: require('./if-group 2.png') as number,
  iconHistory: require('./fa5-history-fas 3.png') as number,

  /* 智能体页面。 */
  agentAvatar: require('./znt1.png') as number,
  agentInputAttach: require('./znt2.png') as number,
  agentInputVoice: require('./znt3.png') as number,
  agentInputSend: require('./znt6.png') as number,
  agentNavAction1: require('./znt4.png') as number,
  agentNavAction2: require('./znt5.png') as number,

  /* 品牌 logo。原为 uni-app 模板图标（72×72）。 */
  appLogo: require('./logo.png') as number,

  /* 律师头像（竖版人像）。 */
  lawyerPortrait1: require('./ls1.jpg') as number,
  lawyerPortrait2: require('./ls2.jpg') as number,
  lawyerPortrait3: require('./ls3.jpg') as number,
  lawyerPortrait4: require('./ls4.jpg') as number,
} as const;

export type LegacyImageKey = keyof typeof LEGACY_IMAGES;
