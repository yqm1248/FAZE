/**
 * i18n 资源汇总（段 0 只有 zh-Hans 有内容，其余三语言降级到它）。
 *
 * 依据：i18n 规范 §4.1（目录结构）、§4.3（14 个 namespace）、§4.4（error.json 用完整 msgKey）。
 * 调用方：`shared/i18n/index.ts`。
 * 注意：
 *   1. **必须是静态 import**（不能用 `fetch`/动态路径）：TS 要据此推出字面量 key 类型，
 *      动态加载会让 `MessageKey` 退化成 string，「缺 key 编译期报错」这条就废了。
 *   2. `error` namespace 的 key 本身就是完整 msgKey（`error.auth.token-expired`），
 *      **不能再加 `error.` 前缀**，所以它单独导出、不参与其它 namespace 的前缀展开。
 *   3. `bo` / `ug-Arab-CN` / `kk-Arab-CN` 段 0 全是 `{}`，`t()` 会逐级降级到 zh-Hans。
 */

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
