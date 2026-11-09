/**
 * i18n 资源汇总（段 8 起四语言全量）。
 *
 * 依据：i18n 规范 §4.1（目录结构）、§4.3（14 个 namespace）、§4.4（error.json 用完整 msgKey）。
 * 调用方：`shared/i18n/index.ts`。
 * 注意：
 *   1. **必须是静态 import**（不能用 `fetch`/动态路径）：TS 要据此推出字面量 key 类型，
 *      动态加载会让 `MessageKey` 退化成 string，「缺 key 编译期报错」这条就废了。
 *   2. `error` namespace 的 key 本身就是完整 msgKey（`error.auth.token-expired`），
 *      **不能再加 `error.` 前缀**，所以它单独导出、不参与其它 namespace 的前缀展开。
 *   3. 段 8 起 `bo` / `ug-Arab-CN` / `kk-Arab-CN` 与 zh-Hans 同构（各 1855 key）。
 *      `LangResource` 故意用 `Record<string, unknown>` 擦除三语的 JSON 字面量类型：
 *      只有 zh-Hans 需要参与 `MessageKey` 推导，否则 42 个 JSON 的字面量类型会拖慢 tsc。
 *   4. 缺译仍保留中文兜底（`t()` 里逐级降级），**不在这里做 merge**：
 *      merge 会让「哪个 key 缺译」变得不可观测。
 */

import type { Lang } from '../lang';

import aiJson from './zh-Hans/ai.json';
import authJson from './zh-Hans/auth.json';
import caseJson from './zh-Hans/case.json';
import commonJson from './zh-Hans/common.json';
import contentJson from './zh-Hans/content.json';
import conversationJson from './zh-Hans/conversation.json';
import documentJson from './zh-Hans/document.json';
import errorJson from './zh-Hans/error.json';
import homeJson from './zh-Hans/home.json';
import lawyerJson from './zh-Hans/lawyer.json';
import opsJson from './zh-Hans/ops.json';
import profileJson from './zh-Hans/profile.json';
import tradeJson from './zh-Hans/trade.json';
import workbenchJson from './zh-Hans/workbench.json';

import boAi from './bo/ai.json';
import boAuth from './bo/auth.json';
import boCase from './bo/case.json';
import boCommon from './bo/common.json';
import boContent from './bo/content.json';
import boConversation from './bo/conversation.json';
import boDocument from './bo/document.json';
import boError from './bo/error.json';
import boHome from './bo/home.json';
import boLawyer from './bo/lawyer.json';
import boOps from './bo/ops.json';
import boProfile from './bo/profile.json';
import boTrade from './bo/trade.json';
import boWorkbench from './bo/workbench.json';

import ugAi from './ug-Arab-CN/ai.json';
import ugAuth from './ug-Arab-CN/auth.json';
import ugCase from './ug-Arab-CN/case.json';
import ugCommon from './ug-Arab-CN/common.json';
import ugContent from './ug-Arab-CN/content.json';
import ugConversation from './ug-Arab-CN/conversation.json';
import ugDocument from './ug-Arab-CN/document.json';
import ugError from './ug-Arab-CN/error.json';
import ugHome from './ug-Arab-CN/home.json';
import ugLawyer from './ug-Arab-CN/lawyer.json';
import ugOps from './ug-Arab-CN/ops.json';
import ugProfile from './ug-Arab-CN/profile.json';
import ugTrade from './ug-Arab-CN/trade.json';
import ugWorkbench from './ug-Arab-CN/workbench.json';

import kkAi from './kk-Arab-CN/ai.json';
import kkAuth from './kk-Arab-CN/auth.json';
import kkCase from './kk-Arab-CN/case.json';
import kkCommon from './kk-Arab-CN/common.json';
import kkContent from './kk-Arab-CN/content.json';
import kkConversation from './kk-Arab-CN/conversation.json';
import kkDocument from './kk-Arab-CN/document.json';
import kkError from './kk-Arab-CN/error.json';
import kkHome from './kk-Arab-CN/home.json';
import kkLawyer from './kk-Arab-CN/lawyer.json';
import kkOps from './kk-Arab-CN/ops.json';
import kkProfile from './kk-Arab-CN/profile.json';
import kkTrade from './kk-Arab-CN/trade.json';
import kkWorkbench from './kk-Arab-CN/workbench.json';

/** 除 `error` 之外的 13 个 namespace（键名 = namespace 名）。 */
export const ZH_HANS = {
  ai: aiJson,
  auth: authJson,
  case: caseJson,
  common: commonJson,
  content: contentJson,
  conversation: conversationJson,
  document: documentJson,
  home: homeJson,
  lawyer: lawyerJson,
  ops: opsJson,
  profile: profileJson,
  trade: tradeJson,
  workbench: workbenchJson,
} as const;

/** `error` namespace：key 即完整 msgKey（i18n 规范 §4.4）。 */
export const ZH_HANS_ERROR = errorJson;

/** 单个命名空间的一棵树（值可能是嵌套对象，叶子是字符串）。 */
export type NamespaceTree = Record<string, unknown>;

/** 一种语言的资源：13 个 namespace 树 + 一张 error 平表。 */
export type LangResource = {
  /** namespace → 树。 */
  readonly ns: Readonly<Record<string, NamespaceTree>>;
  /** 完整 msgKey → 文案。 */
  readonly error: Readonly<Record<string, string>>;
};

/**
 * 四语言资源表。
 *
 * `zh-Hans` 保留精确字面量类型（`MessageKey` 由它推导），
 * 其余三语擦成 `LangResource`，tsc 不必为 42 个 JSON 逐个算字面量类型。
 */
export const LANG_RESOURCES: Readonly<Record<Lang, LangResource>> = {
  'zh-Hans': { ns: ZH_HANS, error: ZH_HANS_ERROR },
  bo: {
    ns: {
      ai: boAi,
      auth: boAuth,
      case: boCase,
      common: boCommon,
      content: boContent,
      conversation: boConversation,
      document: boDocument,
      home: boHome,
      lawyer: boLawyer,
      ops: boOps,
      profile: boProfile,
      trade: boTrade,
      workbench: boWorkbench,
    },
    error: boError,
  },
  'ug-Arab-CN': {
    ns: {
      ai: ugAi,
      auth: ugAuth,
      case: ugCase,
      common: ugCommon,
      content: ugContent,
      conversation: ugConversation,
      document: ugDocument,
      home: ugHome,
      lawyer: ugLawyer,
      ops: ugOps,
      profile: ugProfile,
      trade: ugTrade,
      workbench: ugWorkbench,
    },
    error: ugError,
  },
  'kk-Arab-CN': {
    ns: {
      ai: kkAi,
      auth: kkAuth,
      case: kkCase,
      common: kkCommon,
      content: kkContent,
      conversation: kkConversation,
      document: kkDocument,
      home: kkHome,
      lawyer: kkLawyer,
      ops: kkOps,
      profile: kkProfile,
      trade: kkTrade,
      workbench: kkWorkbench,
    },
    error: kkError,
  },
};
